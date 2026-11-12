package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The staff side of Veilingverkopen that only reads: the list of meldingen with their parts per
 * source, the summary the nav count / home card / Te doen task read (no ledger work), and the
 * analytics rows by sold_on. Open always means the open-part rule ({@link PartnerOpenParts}).
 */
@ApplicationScoped
public class PartnerSalesStaffService {

    static final int DEFAULT_PAGE = 100;
    static final int MAX_PAGE = 200;
    /** The round-2 list answers at most this many meldingen (its totals cover every match). */
    static final int MAX_FILTERED = 1000;
    static final int SCAN_CHUNK = 200;
    static final int LOAD_CHUNK = 500;
    static final Duration RECENT = Duration.ofDays(14);

    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerReportReader reader;
    private final PartnerProductTiles tiles;
    private final PartnerOpenParts openParts;
    private final SalesRepositories.Customers customers;
    private final PurchaseOrderService purchaseOrders;

    public PartnerSalesStaffService(PartnerPortalRepositories.Reports reports, PartnerPortalRepositories.Lines lines,
                                    PartnerReportReader reader, PartnerProductTiles tiles, PartnerOpenParts openParts,
                                    SalesRepositories.Customers customers, PurchaseOrderService purchaseOrders) {
        this.reports = reports;
        this.lines = lines;
        this.reader = reader;
        this.tiles = tiles;
        this.openParts = openParts;
        this.customers = customers;
        this.purchaseOrders = purchaseOrders;
    }

    /* ------------------------------------------------------------------ list */

    /** The states the round-2 list filters by (Loaded.state() plus OPEN = not fully invoiced, not withdrawn, and ALL). */
    public static final List<String> STATES = List.of(PartnerReportReader.STATE_UNCONFIRMED, PartnerReportReader.STATE_CONFIRMED,
            PartnerReportReader.STATE_INVOICED, PartnerReportReader.STATE_VOIDED, "OPEN", "ALL");

    /** The round-2 list's criteria; every one optional (state null = ALL). */
    public record ListQuery(Long customerId, String state, String channel, LocalDate from, LocalDate to,
                            Long buyerCustomerId, Integer limit) {}

    /**
     * Round 1's list, kept for older screens: newest id first. status OPEN = still something to invoice
     * (OPEN and PARTLY_INVOICED), INVOICED = something invoiced (INVOICED and PARTLY_INVOICED), ALL
     * (default) = everything, withdrawn ones included. {@code before} is an exclusive id cursor; limit
     * 1..200 (default 100).
     */
    @Transactional
    public PartnerPortalViews.StaffPage list(Long customerId, String status, Long before, Integer limit) {
        String filter = status == null || status.isBlank() ? "ALL" : status.strip().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "INVOICED", "ALL").contains(filter)) throw new BusinessRuleException("Kies open, gefactureerd of alles");
        int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        List<PartnerReportReader.Loaded> picked = new ArrayList<>();
        boolean more = false;
        Long cursor = before;
        scan:
        while (true) {
            List<PartnerSaleReportEntity> chunk = reports.pageAll(customerId, cursor, SCAN_CHUNK);
            if (chunk.isEmpty()) break;
            for (PartnerReportReader.Loaded loaded : reader.load(chunk)) {
                if (!matches(filter, loaded.status())) continue;
                if (picked.size() == size) {
                    more = true;
                    break scan;
                }
                picked.add(loaded);
            }
            if (chunk.size() < SCAN_CHUNK) break;
            cursor = chunk.getLast().id;
        }
        return new PartnerPortalViews.StaffPage(views(picked), more, totals(customerId), PartnerPortalViews.API_VERSION, null);
    }

    /**
     * Round 2's list (contract §6 + v2-D): the meldingen of a partner, channel, sold-on period, buyer and
     * state (UNCONFIRMED / CONFIRMED / INVOICED / VOIDED by {@link PartnerReportReader.Loaded#state()},
     * OPEN = the first two, ALL = everything), newest sold-on day first, then the higher id, at most
     * {@code limit} (1..1000, default 1000) with {@code more} when the filter matched more. filteredTotals
     * sums every match (past the cap too), withdrawn meldingen left out; totals stays the round-1 open
     * totals (for this partner, or for all).
     */
    @Transactional
    public PartnerPortalViews.StaffPage list(ListQuery query) {
        String state = query.state() == null || query.state().isBlank() ? "ALL" : query.state().strip().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) throw new BusinessRuleException("Kies te bevestigen, bevestigd, gefactureerd, ingetrokken, open of alles");
        String channel = query.channel() == null || query.channel().isBlank() ? null : query.channel().strip().toUpperCase(Locale.ROOT);
        if (channel != null && !List.of(PartnerPortalRules.CHANNEL_AUCTION, PartnerPortalRules.CHANNEL_DIRECT).contains(channel))
            throw new BusinessRuleException("Kies veiling of direct");
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to()))
            throw new BusinessRuleException("De begindatum ligt na de einddatum");
        int size = query.limit() == null ? MAX_FILTERED : Math.max(1, Math.min(MAX_FILTERED, query.limit()));
        List<PartnerSaleReportEntity> found = reports.filtered(query.customerId(), channel, query.from(), query.to(),
                query.buyerCustomerId());
        PartnerProductTiles.Catalog catalog = tiles.load();
        List<PartnerReportReader.Loaded> picked = new ArrayList<>();
        boolean more = false;
        int count = 0;
        int salePieces = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal costs = BigDecimal.ZERO;
        BigDecimal buyback = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (int start = 0; start < found.size(); start += LOAD_CHUNK) {
            for (PartnerReportReader.Loaded loaded : reader.load(found.subList(start, Math.min(found.size(), start + LOAD_CHUNK)))) {
                if (!matchesState(state, loaded.state())) continue;
                if (picked.size() < size) picked.add(loaded);
                else more = true;
                if (loaded.report().isVoided()) continue;
                PartnerPortalViews.Totals totals = reader.view(loaded, catalog).totals();
                count++;
                salePieces += totals.salePieces();
                gross = gross.add(totals.grossEur());
                costs = costs.add(totals.commissionEur()).add(totals.transportEur()).add(totals.extraCostEur());
                buyback = buyback.add(totals.buybackEur());
                net = net.add(totals.netEur());
            }
        }
        PartnerPortalViews.FilteredTotals filtered = new PartnerPortalViews.FilteredTotals(count, salePieces,
                PartnerNumbers.moneyOrZero(gross), PartnerNumbers.moneyOrZero(costs), PartnerNumbers.moneyOrZero(buyback),
                PartnerNumbers.moneyOrZero(net));
        return new PartnerPortalViews.StaffPage(views(picked), more, totals(query.customerId()), PartnerPortalViews.API_VERSION,
                filtered);
    }

    private static boolean matchesState(String filter, String state) {
        return switch (filter) {
            case "ALL" -> true;
            case "OPEN" -> PartnerReportReader.STATE_UNCONFIRMED.equals(state) || PartnerReportReader.STATE_CONFIRMED.equals(state);
            default -> filter.equals(state);
        };
    }

    /** One melding as staff see it (an archive row of an edit too: the audit trail reads old versions by id). */
    @Transactional
    public PartnerPortalViews.StaffReport report(long reportId) {
        PartnerSaleReportEntity report = reports.findById(reportId);
        if (report == null) throw new NotFoundException("Melding", reportId);
        return views(reader.load(List.of(report))).getFirst();
    }

    /** These meldingen as staff see them, in the given order (archive rows too: read by id). */
    @Transactional
    public List<PartnerPortalViews.StaffReport> reports(List<Long> reportIds) {
        List<PartnerSaleReportEntity> found = new ArrayList<>();
        for (Long id : reportIds) {
            PartnerSaleReportEntity report = id == null ? null : reports.findById(id);
            if (report != null) found.add(report);
        }
        return views(reader.load(found));
    }

    private static boolean matches(String filter, String status) {
        return switch (filter) {
            case "OPEN" -> PartnerPortalRules.STATUS_OPEN.equals(status) || PartnerPortalRules.STATUS_PARTLY_INVOICED.equals(status);
            case "INVOICED" -> PartnerPortalRules.STATUS_INVOICED.equals(status) || PartnerPortalRules.STATUS_PARTLY_INVOICED.equals(status);
            default -> true;
        };
    }

    private List<PartnerPortalViews.StaffReport> views(List<PartnerReportReader.Loaded> loaded) {
        if (loaded.isEmpty()) return List.of();
        PartnerProductTiles.Catalog catalog = tiles.load();
        Set<Long> containerIds = new HashSet<>();
        Set<Long> customerIds = new HashSet<>();
        for (PartnerReportReader.Loaded item : loaded) {
            if (item.report().customerId != null) customerIds.add(item.report().customerId);
            for (PartnerSaleReportLineEntity line : item.lines()) {
                if (line.purchaseOrderId != null) containerIds.add(line.purchaseOrderId);
            }
        }
        Map<Long, PurchaseOrderName> containers = purchaseOrders.names(containerIds);
        Map<Long, String> names = customerNames(customerIds);
        List<PartnerPortalViews.StaffReport> result = new ArrayList<>();
        for (PartnerReportReader.Loaded item : loaded) {
            PartnerSaleReportEntity report = item.report();
            List<PartnerPortalViews.StaffPart> parts = new ArrayList<>();
            for (PartnerSaleReportLineEntity line : item.lines()) {
                if (!line.isSale() && !line.isBuyback()) continue;
                long productId = line.productId == null ? 0 : line.productId;
                PartnerPortalViews.ProductTile tile = catalog.tile(productId);
                PartnerPortalQueries.DocumentState holder = item.rule().holder(line);
                PurchaseOrderName container = line.purchaseOrderId == null ? null : containers.get(line.purchaseOrderId);
                parts.add(new PartnerPortalViews.StaffPart(line.id, line.entryNo == null ? 0 : line.entryNo,
                        line.lineType, productId, tile.name(), tile.variant(), line.purchaseOrderId,
                        line.purchaseOrderId == null ? null : container != null ? container.displayName()
                                : PurchaseOrderName.display(line.purchaseOrderId, null, null),
                        line.quantityOrZero(), PartnerNumbers.money(line.grossEur), PartnerNumbers.money(line.netEur),
                        PartnerNumbers.unit(line.unitCostEur), holder == null ? null : holder.id(),
                        holder == null ? null : holder.number(), item.rule().isOpen(line, report.isVoided()),
                        PartnerNumbers.moneyOrZero(line.commissionEur), PartnerNumbers.moneyOrZero(line.transportEur),
                        PartnerNumbers.moneyOrZero(line.extraCostEur),
                        line.quantityOrZero() * Math.max(1, tile.piecesPerSalesUnit())));
            }
            long customerId = report.customerId == null ? 0 : report.customerId;
            result.add(PartnerPortalViews.StaffReport.of(reader.view(item, catalog), item.staffCanEdit(),
                    item.staffCanUndo(), customerId,
                    names.get(customerId), report.createdBy, report.voidedAt, report.voidedBy, report.voidReason, parts,
                    report.revisionOf));
        }
        return result;
    }

    /* --------------------------------------------------------------- summary */

    /**
     * Cheap: a few small queries over the open parts and one per partner name, no ledger. Round 2: per
     * melding with an open part whether it is confirmed (staff confirmed it, or a document holds one of
     * its parts): unconfirmedReports wait for Bevestigen, confirmedOpen* are what Factureren takes now.
     * The pieces read only the products of the open sale parts (no catalogue).
     */
    @Transactional
    public PartnerPortalViews.Summary summary() {
        Map<Long, Open> perCustomer = openPerCustomer(null);
        Map<Long, Instant> last = reports.lastCreatedPerCustomer();
        Set<Long> customerIds = new HashSet<>(perCustomer.keySet());
        customerIds.addAll(last.keySet());
        Map<Long, String> names = customerNames(customerIds);
        List<PartnerPortalViews.PartnerSummary> partners = new ArrayList<>();
        Open all = new Open();
        Instant lastAny = null;
        for (Long customerId : customerIds.stream().sorted().toList()) {
            Open open = perCustomer.getOrDefault(customerId, new Open());
            all.add(open);
            Instant at = last.get(customerId);
            if (at != null && (lastAny == null || at.isAfter(lastAny))) lastAny = at;
            partners.add(new PartnerPortalViews.PartnerSummary(customerId, names.get(customerId), open.reports.size(),
                    open.saleQuantity, PartnerNumbers.moneyOrZero(open.net), at, open.unconfirmed().size(),
                    open.confirmed.size(), open.confirmedSaleQuantity, PartnerNumbers.moneyOrZero(open.confirmedNet),
                    open.salePieces, open.confirmedSalePieces));
        }
        int recent = reports.liveCreatedSince(Instant.now().minus(RECENT)).size();
        return new PartnerPortalViews.Summary(all.reports.size(), all.saleQuantity, PartnerNumbers.moneyOrZero(all.net),
                lastAny, recent, partners, PartnerPortalViews.API_VERSION, all.unconfirmed().size(), all.confirmed.size(),
                all.confirmedSaleQuantity, PartnerNumbers.moneyOrZero(all.confirmedNet), all.salePieces,
                all.confirmedSalePieces);
    }

    /** The open totals of one partner, or of every partner when customerId is null. */
    PartnerPortalViews.OpenTotals totals(Long customerId) {
        Open all = new Open();
        openPerCustomer(customerId).values().forEach(all::add);
        return new PartnerPortalViews.OpenTotals(all.reports.size(), all.saleQuantity, PartnerNumbers.moneyOrZero(all.net));
    }

    private Map<Long, Open> openPerCustomer(Long onlyCustomer) {
        Set<Long> released = openParts.releasing(lines.liveInvoiceIds());
        List<Object[]> open = lines.openParts(released);
        Set<Long> withOpenParts = new HashSet<>();
        for (Object[] row : open) withOpenParts.add(((PartnerSaleReportEntity) row[1]).id);
        Set<Long> held = new HashSet<>(lines.reportsWithHeldParts(withOpenParts, released));
        Set<Long> soldProducts = new HashSet<>();
        for (Object[] row : open) {
            PartnerSaleReportLineEntity line = (PartnerSaleReportLineEntity) row[0];
            if (line.isSale() && line.productId != null) soldProducts.add(line.productId);
        }
        Map<Long, Integer> factors = tiles.piecesPerSalesUnit(soldProducts);
        Map<Long, Open> result = new HashMap<>();
        for (Object[] row : open) {
            PartnerSaleReportLineEntity line = (PartnerSaleReportLineEntity) row[0];
            PartnerSaleReportEntity report = (PartnerSaleReportEntity) row[1];
            if (report.customerId == null || (onlyCustomer != null && !onlyCustomer.equals(report.customerId))) continue;
            Open sums = result.computeIfAbsent(report.customerId, key -> new Open());
            boolean confirmed = report.confirmedAt != null || held.contains(report.id);
            sums.reports.add(report.id);
            if (confirmed) sums.confirmed.add(report.id);
            if (line.isSale()) {
                int pieces = line.quantityOrZero() * (line.productId == null ? 1 : factors.getOrDefault(line.productId, 1));
                sums.saleQuantity += line.quantityOrZero();
                sums.salePieces += pieces;
                if (confirmed) {
                    sums.confirmedSaleQuantity += line.quantityOrZero();
                    sums.confirmedSalePieces += pieces;
                }
            }
            if (line.netEur != null) {
                sums.net = sums.net.add(line.netEur);
                if (confirmed) sums.confirmedNet = sums.confirmedNet.add(line.netEur);
            }
        }
        return result;
    }

    private static final class Open {
        final Set<Long> reports = new HashSet<>();
        final Set<Long> confirmed = new HashSet<>();
        int saleQuantity;
        int confirmedSaleQuantity;
        int salePieces;
        int confirmedSalePieces;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal confirmedNet = BigDecimal.ZERO;

        Set<Long> unconfirmed() {
            Set<Long> result = new HashSet<>(reports);
            result.removeAll(confirmed);
            return result;
        }

        void add(Open other) {
            reports.addAll(other.reports);
            confirmed.addAll(other.confirmed);
            saleQuantity += other.saleQuantity;
            confirmedSaleQuantity += other.confirmedSaleQuantity;
            salePieces += other.salePieces;
            confirmedSalePieces += other.confirmedSalePieces;
            net = net.add(other.net);
            confirmedNet = confirmedNet.add(other.confirmedNet);
        }
    }

    /* ------------------------------------------------------------- analytics */

    /**
     * Every non-voided melding sold in [from, to] (either may be null), no cap: one row per melding
     * and one per melding x product, so the drill always sums to the card.
     */
    @Transactional
    public PartnerPortalViews.Analytics analytics(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw new BusinessRuleException("De begindatum ligt na de einddatum");
        List<PartnerSaleReportEntity> found = reports.liveSoldBetween(from, to);
        List<PartnerPortalViews.AnalyticsReport> rows = new ArrayList<>();
        List<PartnerPortalViews.AnalyticsProduct> products = new ArrayList<>();
        if (found.isEmpty()) return new PartnerPortalViews.Analytics(rows, products);
        PartnerProductTiles.Catalog catalog = tiles.load();
        for (int start = 0; start < found.size(); start += LOAD_CHUNK) {
            for (PartnerReportReader.Loaded loaded : reader.load(found.subList(start, Math.min(found.size(), start + LOAD_CHUNK)))) {
                PartnerPortalViews.PartnerReport view = reader.view(loaded, catalog);
                PartnerSaleReportEntity report = loaded.report();
                boolean open = !PartnerPortalRules.STATUS_INVOICED.equals(loaded.status());
                PartnerPortalViews.Totals totals = view.totals();
                /* What is still open, part by part (the open-part rule, as the summary counts it). */
                Map<Long, Integer> openSaleByProduct = new HashMap<>();
                Map<Long, BigDecimal> openNetByProduct = new HashMap<>();
                for (PartnerSaleReportLineEntity line : loaded.lines()) {
                    if (!(line.isSale() || line.isBuyback()) || line.productId == null
                            || !loaded.rule().isOpen(line, report.isVoided())) continue;
                    if (line.isSale()) openSaleByProduct.merge(line.productId, line.quantityOrZero(), Integer::sum);
                    openNetByProduct.merge(line.productId, line.netEur == null ? BigDecimal.ZERO : line.netEur, BigDecimal::add);
                }
                /* The same in pieces, with the factor every tile of this answer carries. */
                Map<Long, Integer> openPiecesByProduct = new HashMap<>();
                openSaleByProduct.forEach((productId, quantity) -> openPiecesByProduct.put(productId,
                        quantity * Math.max(1, catalog.tile(productId).piecesPerSalesUnit())));
                /* buybackEur is the terugkoopkost (-net, with its commission and transport): gross - costs - buyback = net. */
                rows.add(new PartnerPortalViews.AnalyticsReport(report.id, view.number(),
                        report.customerId == null ? 0 : report.customerId, report.channel, report.soldOn, open,
                        totals.saleQuantity(), totals.buybackQuantity(), totals.grossEur(),
                        PartnerNumbers.money(totals.commissionEur().add(totals.transportEur()).add(totals.extraCostEur())),
                        totals.buybackEur(), totals.netEur(),
                        openSaleByProduct.values().stream().mapToInt(Integer::intValue).sum(),
                        PartnerNumbers.moneyOrZero(openNetByProduct.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)),
                        totals.buybackGrossEur(), totals.salePieces(), view.buyer(), loaded.confirmed(),
                        totals.buybackPieces(), openPiecesByProduct.values().stream().mapToInt(Integer::intValue).sum()));
                for (PartnerPortalViews.Entry entry : view.entries()) {
                    BigDecimal gross = entry.sale() == null ? PartnerNumbers.moneyOrZero(null) : entry.sale().grossEur();
                    BigDecimal saleNet = entry.sale() == null ? BigDecimal.ZERO : entry.sale().netEur();
                    BigDecimal buyback = entry.buyback() == null ? BigDecimal.ZERO : entry.buyback().costEur();
                    products.add(new PartnerPortalViews.AnalyticsProduct(report.id, entry.productId(), entry.name(),
                            entry.variant(), report.soldOn, report.channel, open,
                            entry.sale() == null ? 0 : entry.sale().quantity(),
                            entry.buyback() == null ? 0 : entry.buyback().quantity(),
                            gross, PartnerNumbers.money(saleNet.subtract(buyback)),
                            openSaleByProduct.getOrDefault(entry.productId(), 0),
                            PartnerNumbers.moneyOrZero(openNetByProduct.get(entry.productId())),
                            entry.sale() == null ? 0 : entry.sale().pieces(),
                            entry.buyback() == null ? 0 : entry.buyback().pieces(),
                            openPiecesByProduct.getOrDefault(entry.productId(), 0)));
                }
            }
        }
        return new PartnerPortalViews.Analytics(rows, products);
    }

    private Map<Long, String> customerNames(Collection<Long> ids) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            customers.findById(id).map(Customer::company).filter(Objects::nonNull).ifPresent(name -> result.put(id, name));
        }
        return result;
    }
}
