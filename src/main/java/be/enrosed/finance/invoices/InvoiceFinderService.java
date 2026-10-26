package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import be.enrosed.media.MediaService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The Facturen finder: one read-only list over container invoices and files, company costs, our sales documents and the
 * Odoo moves ENROSED does not already show. The ENROSED read and the live Odoo search run side by side; Odoo gets at
 * most 8 s per request and never holds the ENROSED rows. The only writes are the money-free marks on Odoo moves.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class InvoiceFinderService {
    private static final Logger LOG = Logger.getLogger(InvoiceFinderService.class);
    static final Duration ODOO_BUDGET = Duration.ofSeconds(8);
    static final int MAX_ODOO_CALLS = 3;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_FILE_BYTES = 15 * 1024 * 1024;
    static final Set<String> IN_TYPES = Set.of("in_invoice", "in_refund");
    static final Set<String> OUT_TYPES = Set.of("out_invoice", "out_refund");
    static final List<String> IN_SCOPES = List.of("ALL", "CONTAINERS", "COSTS", "ODOO_NEW", "NO_FILE");
    static final List<String> OUT_SCOPES = List.of("ALL", "INVOICES", "CREDIT_NOTES", "ODOO");
    private static final Pattern KEY = Pattern.compile("^(pi|pd|cost|odoo|so):[1-9][0-9]{0,17}$");

    public record Query(String direction, String scope, String q, LocalDate from, LocalDate to, Long orderId, String cursor,
                        int limit) {}
    public record OdooState(String state /* READY | DEMO | NOT_CONNECTED | ERROR */, String code, String message,
                            boolean included) {}
    public record Page(List<InvoiceRow> rows, String nextCursor, int erpCount, OdooState odoo) {}
    public record MarkRequest(String resolution, Long purchaseOrderId, String note) {}
    /** A media file read for its preview; {@code bytes} null when it is larger than 15 MB. */
    public record MediaFile(String name, String contentType, byte[] bytes) {}

    @Inject ErpInvoiceRows erpRows;
    @Inject OdooInvoices odoo;
    @Inject OdooDocumentMarks marks;
    @Inject CurrentActor actor;
    @Inject MediaService media;
    @ConfigProperty(name = "enrosed.invoice-finder.new-since") Optional<String> newSinceText;

    private volatile Optional<LocalDate> newSince;

    /** Reads the start date once at startup, so an unparsable value logs its one WARN there. */
    void readStartDate(@Observes StartupEvent event) {
        newSince();
    }

    /** {@code enrosed.invoice-finder.new-since}: blank or unparsable means no start date. */
    Optional<LocalDate> newSince() {
        Optional<LocalDate> known = newSince;
        if (known != null) return known;
        synchronized (this) {
            if (newSince != null) return newSince;
            String text = newSinceText == null ? "" : newSinceText.orElse("").strip();
            Optional<LocalDate> parsed = Optional.empty();
            if (!text.isEmpty()) {
                try {
                    parsed = Optional.of(LocalDate.parse(text));
                } catch (DateTimeParseException invalid) {
                    LOG.warnf("enrosed.invoice-finder.new-since '%s' is not a yyyy-MM-dd date; the finder uses no start date", text);
                }
            }
            newSince = parsed;
            return parsed;
        }
    }

    // ------------------------------------------------------------------------------------------------------------ list

    public Page find(Query request) {
        Query query = validate(request);
        String hash = InvoiceFinderCursor.filterHash(query.direction(), query.scope(), query.q(), query.from(), query.to(),
                query.orderId());
        InvoiceFinderCursor.Decoded cursor = InvoiceFinderCursor.decode(query.cursor(), hash);
        OdooInvoices.Availability availability = odoo.availability();
        boolean connected = "READY".equals(availability.state()) || "DEMO".equals(availability.state());
        boolean odooPart = hasOdooPart(query.direction(), query.scope()) && connected && (cursor == null || cursor.odoo());
        ErpInvoiceRows.Dedup dedup = erpRows.dedup(connected ? availability.namespace() : null);
        Optional<LocalDate> start = newSince();
        LocalDate today = LocalDate.now(ErpInvoiceRows.BRUSSELS);

        OdooInvoiceSearch.Query first = null;
        if (odooPart) {
            OdooInvoiceSearch.Cursor after = cursor == null ? null : odooCursor(cursor.position());
            first = odooQuery(query, dedup, start, InvoiceSearchText.odooWords(query.q(), dedup.markContainers()), after);
        }
        long started = System.nanoTime();
        Instant deadline = Instant.now().plus(ODOO_BUDGET);
        OdooInvoiceSearch.Query firstQuery = first;
        Future<OdooInvoiceSearch.Page> firstCall = first == null ? null : OdooDeadline.start(() -> odoo.search(firstQuery));

        // Meanwhile: the ENROSED rows on this thread.
        ErpInvoiceRows.Built built = erpRows.build(query.direction(), firstCall != null);
        List<InvoiceRow> erp = filter(built, query);
        int erpCount = erp.size();
        List<InvoiceRow> erpAfter = erp.stream().sorted(InvoiceFinderCursor.ROW_ORDER)
                .filter(row -> cursor == null || InvoiceFinderCursor.after(InvoiceFinderCursor.Position.of(row), cursor.position()))
                .toList();
        long erpMs = (System.nanoTime() - started) / 1_000_000;

        OdooState state;
        List<InvoiceRow> kept = new ArrayList<>();
        InvoiceFinderCursor.Position scanStop = null;
        boolean odooMore = false;
        int calls = 0;
        long odooMs = 0;
        if (firstCall != null) {
            OdooInvoiceSearch.Page page = null;
            String errorCode = null;
            String errorMessage = null;
            try {
                page = OdooDeadline.await(firstCall, deadline);
            } catch (TimeoutException late) {
                errorCode = "TIMEOUT";
                errorMessage = "Odoo reageert niet.";
            } catch (OdooFailure failure) {
                errorCode = failure.code;
                errorMessage = failure.userMessage();
            }
            calls = 1;
            if (page == null) {
                state = new OdooState("ERROR", errorCode, errorMessage, false);
            } else {
                OdooInvoiceSearch.Move lastScanned = null;
                boolean more = page.more();
                for (OdooInvoiceSearch.Move move : page.moves()) {
                    lastScanned = move;
                    if (built.match() == null || built.match().keyFor(move) == null)
                        kept.add(odooRow(move, dedup, availability, start, today));
                }
                while (kept.size() < query.limit() && more && lastScanned != null && calls < MAX_ODOO_CALLS
                        && Instant.now().isBefore(deadline)) {
                    OdooInvoiceSearch.Query next = withCursor(first,
                            new OdooInvoiceSearch.Cursor(lastScanned.date(), lastScanned.id()));
                    OdooInvoiceSearch.Page following;
                    try {
                        following = OdooDeadline.await(OdooDeadline.start(() -> odoo.search(next)), deadline);
                    } catch (TimeoutException | OdooFailure stop) {
                        break; // what was kept stays; the cursor continues after the last scanned move
                    }
                    calls++;
                    more = following.more();
                    for (OdooInvoiceSearch.Move move : following.moves()) {
                        lastScanned = move;
                        if (built.match() == null || built.match().keyFor(move) == null)
                            kept.add(odooRow(move, dedup, availability, start, today));
                    }
                    if (following.moves().isEmpty()) break;
                }
                if (more && lastScanned != null && kept.size() < query.limit())
                    scanStop = new InvoiceFinderCursor.Position(lastScanned.date(), InvoiceFinderCursor.ODOO_RANK, lastScanned.id());
                odooMore = more;
                state = new OdooState(availability.state(), null, null, true);
            }
            odooMs = (System.nanoTime() - started) / 1_000_000;
        } else if (odooPart) {
            state = new OdooState(availability.state(), null, null, true); // nothing can match (e.g. a container without marks)
        } else {
            state = new OdooState(availability.state(), availability.code(), null, false);
        }

        InvoiceFinderCursor.Merged merged = InvoiceFinderCursor.merge(erpAfter, kept, scanStop, odooMore, query.limit());
        String nextCursor = merged.next() == null ? null : InvoiceFinderCursor.encode(merged.next(), merged.odoo(), hash);
        LOG.debugf("invoice-finder dir=%s scope=%s erpMs=%d odooMs=%d odooCalls=%d rows=%d", query.direction(), query.scope(),
                erpMs, odooMs, calls, merged.rows().size());
        return new Page(merged.rows(), nextCursor, erpCount, state);
    }

    /** Defaults filled in; 400 with a code for anything the finder cannot answer. */
    static Query validate(Query request) {
        if (request == null) throw InvoiceFinderErrors.invalid("INVALID_SCOPE");
        String direction = request.direction() == null || request.direction().isBlank() ? "IN"
                : request.direction().strip().toUpperCase(Locale.ROOT);
        List<String> scopes = switch (direction) {
            case "IN" -> IN_SCOPES;
            case "OUT" -> OUT_SCOPES;
            default -> throw InvoiceFinderErrors.invalid("INVALID_SCOPE");
        };
        String scope = request.scope() == null || request.scope().isBlank() ? "ALL" : request.scope().strip().toUpperCase(Locale.ROOT);
        if (!scopes.contains(scope)) throw InvoiceFinderErrors.invalid("INVALID_SCOPE");
        String q = request.q() == null ? "" : request.q();
        if (q.length() > InvoiceSearchText.MAX_QUERY) throw InvoiceFinderErrors.invalid("INVALID_QUERY");
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to()))
            throw InvoiceFinderErrors.invalid("INVALID_PERIOD");
        if (request.limit() < 1 || request.limit() > 100) throw InvoiceFinderErrors.invalid("INVALID_LIMIT");
        if (request.orderId() != null && request.orderId() <= 0) throw InvoiceFinderErrors.invalid("INVALID_ORDER");
        return new Query(direction, scope, q.strip(), request.from(), request.to(), request.orderId(), request.cursor(),
                request.limit());
    }

    static boolean hasOdooPart(String direction, String scope) {
        return "OUT".equals(direction) || "ALL".equals(scope) || "ODOO_NEW".equals(scope);
    }

    /** The ENROSED rows of the request's scope, period, container and search words. */
    static List<InvoiceRow> filter(ErpInvoiceRows.Built built, Query query) {
        List<InvoiceSearchText.Needle> needles = InvoiceSearchText.needles(query.q());
        List<InvoiceRow> rows = new ArrayList<>();
        for (InvoiceRow row : built.rows()) {
            if (!inScope(row, query.direction(), query.scope())) continue;
            if (query.from() != null && (row.sortDate() == null || row.sortDate().isBefore(query.from()))) continue;
            if (query.to() != null && (row.sortDate() == null || row.sortDate().isAfter(query.to()))) continue;
            if (query.orderId() != null && (row.container() == null || row.container().id() != query.orderId())) continue;
            if (!needles.isEmpty()) {
                String text = built.searchText().getOrDefault(row.key(), ErpInvoiceRows.searchText(row));
                if (!InvoiceSearchText.matches(InvoiceSearchText.Hay.of(text, row.amount(), row.amountEur()), needles)) continue;
            }
            rows.add(row);
        }
        return rows;
    }

    static boolean inScope(InvoiceRow row, String direction, String scope) {
        String source = row.source();
        boolean container = InvoiceRow.CONTAINER_INVOICE.equals(source) || InvoiceRow.CONTAINER_FILE.equals(source);
        boolean cost = InvoiceRow.COST.equals(source);
        boolean sales = InvoiceRow.SALES.equals(source);
        if ("OUT".equals(direction)) {
            return switch (scope) {
                case "ALL" -> sales;
                case "INVOICES" -> sales && "INVOICE".equals(row.kind());
                case "CREDIT_NOTES" -> sales && "CREDIT_NOTE".equals(row.kind());
                default -> false;
            };
        }
        return switch (scope) {
            case "ALL" -> container || cost;
            case "CONTAINERS" -> container;
            case "COSTS" -> cost;
            case "NO_FILE" -> (container || cost) && row.missingFile();
            default -> false;
        };
    }

    /** The Odoo cursor of a list position: an ENROSED row lets every Odoo move of that day still come. */
    static OdooInvoiceSearch.Cursor odooCursor(InvoiceFinderCursor.Position position) {
        if (position == null || position.sortDate() == null) return null;
        return position.rank() < InvoiceFinderCursor.ODOO_RANK ? new OdooInvoiceSearch.Cursor(position.sortDate(), null)
                : new OdooInvoiceSearch.Cursor(position.sortDate(), position.id());
    }

    private static OdooInvoiceSearch.Query withCursor(OdooInvoiceSearch.Query query, OdooInvoiceSearch.Cursor after) {
        return new OdooInvoiceSearch.Query(query.direction(), query.moveTypes(), query.words(), query.from(), query.to(),
                query.excludeMoveIds(), query.onlyMoveIds(), after, query.limit());
    }

    /** Pure, unit-tested: the Odoo query of a request page (exclusions, start date, words, cursor). null: no Odoo call. */
    static OdooInvoiceSearch.Query odooQuery(Query request, ErpInvoiceRows.Dedup dedup, Optional<LocalDate> newSince,
                                             List<OdooInvoiceSearch.Word> words, OdooInvoiceSearch.Cursor after) {
        /* Words were typed but none is usable for Odoo ("1/2", "&"): asking Odoo without words would list every move. */
        if ((words == null || words.isEmpty()) && !InvoiceSearchText.words(request.q()).isEmpty()) return null;
        Set<String> types;
        Set<Long> exclude = new TreeSet<>();
        LocalDate from = request.from();
        switch (request.direction() + "/" + request.scope()) {
            case "IN/ALL" -> {
                types = IN_TYPES;
                exclude.addAll(dedup.registered().keySet());
            }
            case "IN/ODOO_NEW" -> {
                types = IN_TYPES;
                exclude.addAll(dedup.registered().keySet());
                // A move whose cost was deleted in ENROSED is not booked any more: it is new again (FIX2, 2026-10-26).
                exclude.addAll(dedup.ticaMoves());
                exclude.addAll(dedup.marks().keySet());
                if (newSince != null && newSince.isPresent() && (from == null || from.isBefore(newSince.get())))
                    from = newSince.get();
            }
            case "OUT/ALL", "OUT/ODOO" -> {
                types = OUT_TYPES;
                exclude.addAll(dedup.outbox().keySet());
            }
            case "OUT/INVOICES" -> {
                types = Set.of("out_invoice");
                exclude.addAll(dedup.outbox().keySet());
            }
            case "OUT/CREDIT_NOTES" -> {
                types = Set.of("out_refund");
                exclude.addAll(dedup.outbox().keySet());
            }
            default -> {
                return null;
            }
        }
        Set<Long> only = null;
        if (request.orderId() != null) {
            only = new TreeSet<>();
            for (OdooDocumentMarks.Mark mark : dedup.marks().values())
                if (OdooDocumentMarks.CONTAINER.equals(mark.resolution()) && request.orderId().equals(mark.purchaseOrderId()))
                    only.add(mark.moveId());
            if (only.isEmpty() || exclude.containsAll(only)) return null; // nothing Odoo could answer
            only = Collections.unmodifiableSet(only);
        }
        return new OdooInvoiceSearch.Query(request.direction(), new TreeSet<>(types), words == null ? List.of() : words, from,
                request.to(), Collections.unmodifiableSet(exclude), only, after, request.limit());
    }

    /**
     * Pure, unit-tested: status + statusReason of an incoming/outgoing Odoo move (§3.4). A move whose cost was deleted in
     * ENROSED is simply not booked (FIX2, 2026-10-26): it follows the rules of any unbooked move and, when that makes it
     * NEW, keeps COST_REMOVED only as information ("kost eerder verwijderd"), as Nieuw in Odoo lists it.
     */
    static String[] odooStatus(OdooInvoiceSearch.Move move, ErpInvoiceRows.Dedup dedup, Optional<LocalDate> newSince,
                               LocalDate today) {
        if ("cancel".equals(move.state())) return new String[] {"CANCELLED", null};
        if (IN_TYPES.contains(move.moveType())) {
            if (dedup.ticaMoves().contains(move.id())) return new String[] {"TICA", null};
            OdooDocumentMarks.Mark mark = dedup.marks().get(move.id());
            if (mark != null) return new String[] {OdooDocumentMarks.CONTAINER.equals(mark.resolution()) ? "CONTAINER" : "HANDLED", null};
            if (newSince != null && newSince.isPresent() && move.date() != null && move.date().isBefore(newSince.get()))
                return new String[] {"HANDLED", "BEFORE_START"};
            return new String[] {"NEW", dedup.removed().contains(move.id()) ? "COST_REMOVED" : null};
        }
        String payment = move.paymentState() == null ? "" : move.paymentState();
        return switch (payment) {
            case "paid", "in_payment" -> new String[] {"PAID", null};
            case "partial" -> new String[] {"PARTIAL", null};
            case "reversed" -> new String[] {"SETTLED", null};
            default -> new String[] {
                    move.dueDate() != null && today != null && move.dueDate().isBefore(today) ? "OVERDUE" : "OPEN", null};
        };
    }

    /**
     * Why an incoming move cannot be registered as a cost here, first reason in the contract's order; null: it can. A
     * deleted cost blocks nothing any more (FIX2): registering books a new cost (OdooCostImportStore, again = true).
     */
    static String blockedBy(OdooInvoiceSearch.Move move, ErpInvoiceRows.Dedup dedup, boolean demo) {
        if (!IN_TYPES.contains(move.moveType())) return "OUTGOING";
        if (!"draft".equals(move.state()) && !"posted".equals(move.state())) return "CANCELLED";
        if (move.invoiceDate() == null) return "NO_DATE";
        if (move.amountTotal() == null || move.amountTotal().signum() <= 0) return "ZERO";
        if (!"EUR".equals(move.currency())) return "NOT_EUR";
        if (dedup.ticaMoves().contains(move.id())) return "TICA";
        if (dedup.marks().containsKey(move.id())) return "MARKED";
        if (demo) return "DEMO";
        return null;
    }

    /** An Odoo move as a finder row (E). */
    static InvoiceRow odooRow(OdooInvoiceSearch.Move move, ErpInvoiceRows.Dedup dedup, OdooInvoices.Availability availability,
                              Optional<LocalDate> newSince, LocalDate today) {
        boolean incoming = IN_TYPES.contains(move.moveType());
        String[] status = odooStatus(move, dedup, newSince, today);
        OdooDocumentMarks.Mark mark = incoming ? dedup.marks().get(move.id()) : null;
        String blocked = blockedBy(move, dedup, "DEMO".equals(availability.state()));
        InvoiceRow.MarkView markView = mark == null ? null
                : new InvoiceRow.MarkView(mark.resolution(), mark.purchaseOrderId(), mark.note(), mark.markedAt(), mark.markedBy());
        InvoiceRow.OdooFacts facts = new InvoiceRow.OdooFacts(move.id(), blankToNull(move.name()), move.moveType(), move.state(),
                move.paymentState(), blocked == null, blocked, markView);
        InvoiceRow.ContainerRef container = mark != null && OdooDocumentMarks.CONTAINER.equals(mark.resolution())
                ? dedup.markContainers().get(move.id()) : null;
        List<InvoiceRow.Link> links = new ArrayList<>();
        if (container != null) links.add(new InvoiceRow.Link("CONTAINER", container.id(), container.label(), null, null));
        Long settlement = dedup.ticaSettlements().get(move.id());
        if (settlement != null) links.add(new InvoiceRow.Link("TICA", settlement, "TICA", null, null));
        List<InvoiceRow.FileRef> files = new ArrayList<>();
        for (OdooDocumentService.Attachment attachment : move.attachments() == null
                ? List.<OdooDocumentService.Attachment>of() : move.attachments()) {
            String kind = attachment.mimeType() != null && attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("image/")
                    ? "IMAGE" : ErpInvoiceRows.fileKind(attachment.name(), attachment.mimeType());
            files.add(new InvoiceRow.FileRef("ODOO_ATTACHMENT", attachment.id(), null, move.id(), attachment.name(),
                    blankToNull(attachment.mimeType()), kind, attachment.size(), false));
        }
        String number = blankToNull(move.ref()) != null ? move.ref().strip() : blankToNull(move.name());
        LocalDate date = move.invoiceDate() != null ? move.invoiceDate() : move.date();
        BigDecimal amount = move.amountTotal() == null ? null : move.amountTotal().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal amountEur = move.amountTotalEur() == null ? null
                : move.amountTotalEur().setScale(2, java.math.RoundingMode.HALF_UP);
        return new InvoiceRow("odoo:" + move.id(), InvoiceRow.ODOO, incoming ? "IN" : "OUT",
                move.moveType() != null && move.moveType().endsWith("_refund") ? "CREDIT_NOTE" : "INVOICE", null, number,
                date, move.date(), "INVOICE", move.dueDate(), amount, blankToNull(move.currency()), amountEur, null, null,
                new InvoiceRow.Party(blankToNull(move.partner()), "ODOO_PARTNER", move.partnerId()), null, null, null, null,
                status[0], status[1], "ODOO", blankToNull(move.peppolState()), move.id(), facts, container, List.copyOf(links),
                List.copyOf(files), files.isEmpty());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ------------------------------------------------------------------------------------------------------- one row

    /** The row, ignoring filters, or the row that now stands for it; 404 when unknown or trashed. */
    public InvoiceRow row(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw InvoiceFinderErrors.invalid("INVALID_KEY");
        String prefix = key.substring(0, key.indexOf(':'));
        long id = Long.parseLong(key.substring(key.indexOf(':') + 1));
        switch (prefix) {
            case "pi", "cost" -> {
                return byKey(erpRows.build("IN", false), key).orElseThrow(() -> new NotFoundException("Factuur", key));
            }
            case "pd" -> {
                ErpInvoiceRows.Built built = erpRows.build("IN", false);
                return byKey(built, key).or(() -> built.rows().stream()
                                .filter(row -> InvoiceRow.CONTAINER_INVOICE.equals(row.source()))
                                .filter(row -> row.files().stream().anyMatch(file ->
                                        "PURCHASE_DOCUMENT".equals(file.type()) && file.id() == id))
                                .findFirst())
                        .orElseThrow(() -> new NotFoundException("Factuur", key));
            }
            case "so" -> {
                return byKey(erpRows.build("OUT", false), key).orElseThrow(() -> new NotFoundException("Factuur", key));
            }
            default -> {
                return odooRow(id);
            }
        }
    }

    private static Optional<InvoiceRow> byKey(ErpInvoiceRows.Built built, String key) {
        return built.rows().stream().filter(row -> key.equals(row.key())).findFirst();
    }

    private InvoiceRow odooRow(long moveId) {
        OdooInvoices.Availability availability = connectedOrFail();
        return odooRow(moveId, availability, () -> findMove(moveId));
    }

    /** The row of an Odoo key from the database first; {@code lookup} asks Odoo for the move only when needed. */
    private InvoiceRow odooRow(long moveId, OdooInvoices.Availability availability,
                               Supplier<OdooInvoiceSearch.Move> lookup) {
        ErpInvoiceRows.Dedup dedup = erpRows.dedup(availability.namespace());
        Long costId = dedup.registered().get(moveId);
        if (costId != null) {
            Optional<InvoiceRow> cost = byKey(erpRows.build("IN", false), "cost:" + costId);
            if (cost.isPresent()) return cost.get();
        }
        Long salesId = dedup.outbox().get(moveId);
        if (salesId != null) {
            Optional<InvoiceRow> sales = byKey(erpRows.build("OUT", false), "so:" + salesId);
            if (sales.isPresent()) return sales.get();
        }
        OdooInvoiceSearch.Move move = lookup.get();
        if (move == null) throw new NotFoundException("Odoo-factuur", moveId);
        ErpInvoiceRows.Built built = erpRows.build(IN_TYPES.contains(move.moveType()) ? "IN" : "OUT", true);
        String matched = built.match() == null ? null : built.match().keyFor(move);
        if (matched != null) {
            Optional<InvoiceRow> row = byKey(built, matched);
            if (row.isPresent()) return row.get();
        }
        return odooRow(move, dedup, availability, newSince(), LocalDate.now(ErpInvoiceRows.BRUSSELS));
    }

    /**
     * One move from Odoo within the list's budget ({@link #ODOO_BUDGET}); a hanging Odoo answers UNAVAILABLE instead of
     * holding the request for the client's own timeout.
     */
    private OdooInvoiceSearch.Move findMove(long moveId) {
        try {
            return OdooDeadline.await(OdooDeadline.start(() -> odoo.find(moveId)), Instant.now().plus(ODOO_BUDGET));
        } catch (TimeoutException late) {
            throw new OdooFailure("UNAVAILABLE");
        }
    }

    private OdooInvoices.Availability connectedOrFail() {
        OdooInvoices.Availability availability = odoo.availability();
        if (!"READY".equals(availability.state()) && !"DEMO".equals(availability.state()))
            throw new OdooFailure(availability.code() == null ? "NOT_CONFIGURED" : availability.code());
        return availability;
    }

    // ------------------------------------------------------------------------------------------------------------ marks

    public InvoiceRow mark(long moveId, MarkRequest request) {
        OdooInvoices.Availability availability = connectedOrFail();
        String resolution = request == null || request.resolution() == null ? "" : request.resolution().strip();
        Long orderId = request == null ? null : request.purchaseOrderId();
        String note = request == null || request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (!OdooDocumentMarks.HANDLED.equals(resolution) && !OdooDocumentMarks.CONTAINER.equals(resolution))
            throw InvoiceFinderErrors.invalid("INVALID_MARK", "Kies Afgehandeld of Hoort bij container.");
        if (OdooDocumentMarks.CONTAINER.equals(resolution) && (orderId == null || orderId <= 0))
            throw InvoiceFinderErrors.invalid("INVALID_MARK", "Kies de container waar deze factuur bij hoort.");
        if (OdooDocumentMarks.HANDLED.equals(resolution) && orderId != null)
            throw InvoiceFinderErrors.invalid("INVALID_MARK", "Afgehandeld hoort bij geen container.");
        if (note != null && note.length() > 200)
            throw InvoiceFinderErrors.invalid("INVALID_MARK", "De notitie mag maximaal 200 tekens bevatten.");
        OdooInvoiceSearch.Move move = findMove(moveId);
        if (move == null) throw new NotFoundException("Odoo-factuur", moveId);
        if (!IN_TYPES.contains(move.moveType())) throw InvoiceFinderErrors.invalid("NOT_INCOMING");
        if ("cancel".equals(move.state())) throw new BusinessRuleException("Deze factuur is geannuleerd in Odoo.");
        ErpInvoiceRows.Dedup dedup = erpRows.dedup(availability.namespace());
        if (dedup.registered().containsKey(moveId)) throw new BusinessRuleException("Deze factuur is al als kost geregistreerd.");
        ErpInvoiceRows.Built built = erpRows.build("IN", true);
        if (built.match() != null && built.match().keyFor(move) != null)
            throw new BusinessRuleException("Deze factuur staat al in ENROSED.");
        if (dedup.ticaMoves().contains(moveId)) throw new BusinessRuleException("Een TICA-afrekening verwerk je bij TICA.");
        if (OdooDocumentMarks.CONTAINER.equals(resolution) && erpRows.liveContainer(orderId) == null)
            throw new NotFoundException("Inkooporder", orderId);
        marks.put(availability.namespace(), moveId, resolution, orderId, note, actor.name());
        return odooRow(move, erpRows.dedup(availability.namespace()), availability, newSince(),
                LocalDate.now(ErpInvoiceRows.BRUSSELS));
    }

    /**
     * Idempotent: clears the mark when there is one and answers the row as it stands now. Odoo is asked for the move
     * before the mark is cleared, so a failing Odoo leaves the mark as it was and the refusal the screen shows is true.
     */
    public InvoiceRow clearMark(long moveId) {
        OdooInvoices.Availability availability = connectedOrFail();
        if (moveId <= 0) throw new OdooFailure("INVALID_REQUEST");
        OdooInvoiceSearch.Move move = findMove(moveId);
        marks.clear(availability.namespace(), moveId);
        return odooRow(moveId, availability, () -> move);
    }

    // ------------------------------------------------------------------------------------------------------------ files

    /** A media asset's bytes for its preview (at most 15 MB; larger: bytes null). */
    @Transactional
    public MediaFile mediaFile(long assetId) {
        MediaService.FileRef file = media.file(assetId);
        try (InputStream data = file.data()) {
            if (file.sizeBytes() > MAX_FILE_BYTES) return new MediaFile(file.originalFilename(), file.contentType(), null);
            byte[] bytes = data.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) return new MediaFile(file.originalFilename(), file.contentType(), null);
            return new MediaFile(file.originalFilename(), file.contentType(), bytes);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
