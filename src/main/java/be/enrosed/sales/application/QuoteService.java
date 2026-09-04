package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.BusinessDays;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The quote workflow: sending, being viewed, and receiving change requests
 * from the customer.
 *
 * The most important rule lives in {@link #proposeRevision}: the customer
 * never edits the quote directly. They put down a proposal and we approve,
 * adjust or reject it. A sent document must not change under our hands -
 * otherwise nobody knows which version was signed.
 *
 * The customer enters with a portal token instead of an account. Whoever has
 * the link may view the quote and respond to it; the link only exists in the
 * mail to that address.
 */
@ApplicationScoped
public class QuoteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SalesRepositories.Orders orders;
    private final SalesRepositories.Revisions revisions;
    private final SalesOrderService salesOrders;
    private final CustomerService customers;
    private final QuoteDocumentRenderer renderer;
    private final QuoteMailer mailer;
    private final be.enrosed.catalog.application.ProductService products;
    private final SalesRepositories.Events events;

    @ConfigProperty(name = "enrosed.portal.base-url")
    String portalBaseUrl;

    private final be.enrosed.shared.company.CompanyProfileService company;
    private final be.enrosed.push.WebPushNotifier phones;

    /* Field injection deliberately keeps the existing constructor available to small pure unit
       tests. Runtime requests always receive these CDI-owned, server-authoritative collaborators. */
    @Inject
    CurrentActor currentActor;

    @Inject
    ActivityLogService activityLog;

    @Inject
    Event<SalesActivityPushNotifier.Ready> salesPushReady;

    public QuoteService(SalesRepositories.Orders orders, SalesRepositories.Revisions revisions,
                        SalesOrderService salesOrders, CustomerService customers,
                        QuoteDocumentRenderer renderer, QuoteMailer mailer,
                        be.enrosed.catalog.application.ProductService products,
                        SalesRepositories.Events events,
                        be.enrosed.shared.company.CompanyProfileService company,
                        be.enrosed.push.WebPushNotifier phones) {
        this.company = company;
        this.phones = phones;
        this.orders = orders;
        this.revisions = revisions;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.renderer = renderer;
        this.mailer = mailer;
        this.products = products;
        this.events = events;
    }

    private SalesOrder sendInvoiceByMail(SalesOrder order, String personalMessage) {
        if (order.status() != QuoteStatus.CONCEPT) {
            throw new BusinessRuleException("Alleen een conceptfactuur kan verstuurd worden");
        }
        salesOrders.validateInvoiceForSend(order);
        Customer customer = customers.get(order.customerId());
        if (customer.email() == null || customer.email().isBlank()) {
            throw new BusinessRuleException(
                    "Klant " + customer.company() + " heeft geen e-mailadres");
        }

        PricedOrder priced = salesOrders.price(order);
        QuoteDocumentRenderer.Document document = renderer.render(order, priced, customer, null);

        var text = be.enrosed.shared.DocumentText.of(customer.language());
        String iban = company.get().iban() == null || company.get().iban().isBlank()
                ? "-" : company.get().iban();
        java.math.BigDecimal claim = priced.totals().vatTreatment().isExempt()
                ? priced.totals().total() : priced.totals().totalInclVat();
        String paymentSentence = text.get("paymentInstruction").formatted(
                be.enrosed.shared.DocumentFormat.eur(claim),
                be.enrosed.shared.DocumentText.date(order.invoiceDueDate(), customer.language()),
                iban, order.number());

        mailer.sendInvoice(order, customer, document, personalMessage, paymentSentence);
        /* SalesOrderService owns the single SENT audit + after-commit push for both
           this mail flow and the dashboard's manual 'mark sent' action. */
        return salesOrders.markInvoiceSent(order.id());
    }

    /**
     * The packing slip: pallets as laid out by hand, or simply the lines
     * when no pallets exist - pallets are never a requirement, the paper
     * adapts to how far the planning got.
     */
    public QuoteDocumentRenderer.Document packingSlip(long orderId) {
        return packingSlip(orderId, SalesPdfOptions.forPackingSlip(false, false));
    }

    /** Warehouse export with opt-in, price-free product master data. */
    public QuoteDocumentRenderer.Document packingSlip(long orderId,
                                                       SalesPdfOptions requestedOptions) {
        SalesPdfOptions options = requestedOptions == null
                ? SalesPdfOptions.forPackingSlip(false, false) : requestedOptions;
        SalesOrder order = salesOrders.get(orderId);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());

        java.util.Map<Long, Integer> assigned = new java.util.HashMap<>();
        java.util.List<QuoteDocumentRenderer.PackingPallet> pallets = new java.util.ArrayList<>();
        int visiblePallets = order.loadMode() == LoadMode.PALLETS ? order.pallets().size() : 0;
        for (int i = 0; i < visiblePallets; i++) {
            OrderPallet pallet = order.pallets().get(i);
            java.util.List<QuoteDocumentRenderer.PackingItem> items = new java.util.ArrayList<>();
            for (OrderPallet.Item item : pallet.items()) {
                var product = products.get(item.productId());
                int per = product.carton() == null ? 1
                        : Math.max(1, product.carton().piecesPerCarton());
                items.add(packingItem(product, item.cartons(), item.cartons() * per, options));
                assigned.merge(item.productId(), item.cartons(), Integer::sum);
            }
            String label = pallet.label() == null || pallet.label().isBlank()
                    ? "Pallet " + (i + 1) : pallet.label();
            pallets.add(new QuoteDocumentRenderer.PackingPallet(
                    label, pallet.type(), pallet.heightCm(), items));
        }

        /* Lines not (fully) on a pallet: the loose rest. Without any manual
           pallets this simply lists the whole order. */
        java.util.List<QuoteDocumentRenderer.PackingItem> loose = new java.util.ArrayList<>();
        int totalCartons = 0;
        int totalPieces = 0;
        for (var line : order.lines()) {
            var product = products.get(line.productId());
            int per = product.carton() == null ? 1
                    : Math.max(1, product.carton().piecesPerCarton());
            int cartons = (line.quantity() + per - 1) / per;
            totalCartons += cartons;
            /* Sales ships full outer cartons; the footer must match the row
               (13 requested at 12/doos is 24 picked pieces, not 13). */
            totalPieces += cartons * per;
            int left = cartons - assigned.getOrDefault(line.productId(), 0);
            if (left > 0) {
                loose.add(packingItem(product, left, left * per, options));
            }
        }

        return renderer.packingSlip(new QuoteDocumentRenderer.PackingSlip(
                order, customer, pallets, loose, totalCartons, totalPieces,
                order.loadMode() == LoadMode.LOOSE_CARTONS), options);
    }

    private static QuoteDocumentRenderer.PackingItem packingItem(
            Product product, int cartons, int pieces, SalesPdfOptions options) {
        boolean includeCarton = options.showOuterCarton() && product.carton() != null;
        String productBarcode = options.showBarcode()
                ? firstNonBlank(product.canonicalBarcode(),
                        firstNonBlank(product.packaging().barcode(),
                                product.barcodes() == null ? null : product.barcodes().inner()))
                : null;
        String outerBarcode = includeCarton && options.showBarcode()
                && product.barcodes() != null ? product.barcodes().outer() : null;
        return new QuoteDocumentRenderer.PackingItem(
                product.describe(), cartons, pieces,
                includeCarton ? printableDimensions(product.carton().dimensions()) : null,
                includeCarton ? Math.max(1, product.carton().piecesPerCarton()) : null,
                productBarcode, outerBarcode,
                includeCarton ? be.enrosed.shared.DocumentFormat.cbm(product.carton().cbm()) : null,
                includeCarton ? be.enrosed.shared.DocumentFormat.kg(product.carton().weightKg()) : null);
    }

    private static String printableDimensions(Dimensions dimensions) {
        if (dimensions == null || dimensions.isBlank()) return null;
        String label = dimensions.label();
        int separator = label.indexOf(':');
        return separator < 0 ? label : label.substring(separator + 1).strip();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.strip();
        return second == null || second.isBlank() ? null : second.strip();
    }

    /* ============================================================= sending */

    /**
     * Sends the quote to the customer: builds the PDF, adds a portal link
     * and mails the whole thing.
     */
    @Transactional
    public SalesOrder send(long orderId, String personalMessage) {
        SalesOrder order = salesOrders.get(orderId);
        /* Same door, different letter: an invoice mails the PDF with its
           payment line and skips the portal entirely. */
        if (order.isInvoice()) {
            return sendInvoiceByMail(order, personalMessage);
        }
        SalesLifecycle.requireSendable(order);
        salesOrders.validateForSend(order);

        if (order.lines().isEmpty()) {
            throw new BusinessRuleException("Een offerte zonder regels kan niet verstuurd worden");
        }
        if (order.customerId() == null) {
            throw new BusinessRuleException("Koppel eerst een klant aan de offerte");
        }
        Customer customer = customers.get(order.customerId());
        if (customer.email() == null || customer.email().isBlank()) {
            throw new BusinessRuleException(
                    "Klant " + customer.company() + " heeft geen e-mailadres");
        }

        PricedOrder priced = salesOrders.price(order);
        if (!priced.validation().meetsMinimum()) {
            throw new BusinessRuleException(
                    "De offerte haalt de minimum orderwaarde niet - er ontbreekt nog "
                            + priced.validation().shortfall() + " EUR");
        }

        /* The token survives a second sending: the customer already has the link. */
        String token = order.portalToken() == null ? newToken() : order.portalToken();
        String portalUrl = portalUrl(token);

        QuoteDocumentRenderer.Document document = renderer.render(order, priced, customer, portalUrl);

        /* The delivery terms travel along in the mail. When every line has a
           date or a week, that is the answer a customer who asked for a change
           has been waiting for - they should not have to discover it in the
           PDF. */
        List<QuoteMailer.DeliveryLine> deliveryLines = priced.lines().stream()
                .map(line -> new QuoteMailer.DeliveryLine(
                        line.description(),
                        deliveryTermOf(line, customer.language()),
                        line.inStock() || (line.deliveryWeek() != null && !line.deliveryWeek().isBlank())))
                .toList();

        /* Track where the delivery terms stand. When a quote leaves with a
           line without a term, the customer knows we still owe them a date.
           When a later sending does have it, that is the news of that mail. */
        boolean allKnown = deliveryLines.stream().allMatch(QuoteMailer.DeliveryLine::known);
        DeliveryTermsState terms = !allKnown
                ? DeliveryTermsState.TE_BEPALEN
                : order.deliveryTerms() == DeliveryTermsState.TE_BEPALEN
                        ? DeliveryTermsState.AANGEVULD
                        : order.deliveryTerms();

        /* Same road for the freight: when it leaves as an open item, the
           customer knows the amount is still to follow. */
        FreightState freightState = order.freight() == FreightState.TE_BEPALEN
                ? FreightState.TE_BEPALEN
                : order.freight() == FreightState.AANGEVULD
                        ? FreightState.AANGEVULD
                        : FreightState.BEREKEND;

        mailer.sendQuote(order, customer, portalUrl, document, personalMessage, deliveryLines,
                new QuoteMailer.Notice(
                        terms == DeliveryTermsState.AANGEVULD,
                        freightState == FreightState.TE_BEPALEN,
                        freightState == FreightState.AANGEVULD),
                new QuoteMailer.Summary(
                        priced.totals().pieces(), priced.lines().size(),
                        priced.totals().goodsTotal(), priced.totals().shippingTotal(),
                        priced.totals().total(),
                        priced.lines().stream().map(line -> new QuoteMailer.SummaryLine(
                                line.description(), line.quantity(), line.net())).toList()));

        ActorRef actor = staffActor();
        record(order, QuoteEvent.Type.VERSTUURD, false, actor.displayName(),
                order.sentAt() == null ? "Offerte verstuurd" : "Offerte opnieuw verstuurd",
                "Naar " + customer.email());
        if (terms == DeliveryTermsState.AANGEVULD) {
            record(order, QuoteEvent.Type.LEVERTERMIJN_INGEVULD, false, null,
                    "Levertermijn ingevuld", null);
        }
        if (freightState == FreightState.AANGEVULD) {
            record(order, QuoteEvent.Type.VRACHT_INGEVULD, false, null, "Vracht ingevuld", null);
        }

        SalesOrder sent = orders.save(withStatus(order, QuoteStatus.VERZONDEN,
                token, Instant.now(), order.viewedAt(), order.viewCount(),
                null, null, order.customerMessage(), terms, freightState));
        recordActivity("SENT", order,
                order.sentAt() == null ? "Offerte verstuurd" : "Offerte opnieuw verstuurd");
        notifyAfterCommit(SalesActivityPushNotifier.Ready.staffQuoteSent(
                order.id(), order.number(), actor));
        return sent;
    }

    /** Rebuild the PDF, for instance to review or download it ourselves. */
    public QuoteDocumentRenderer.Document document(long orderId) {
        return document(orderId, null, SalesPdfOptions.defaults());
    }

    /**
     * The same PDF in a language of choice.
     *
     * Default is the customer's - that is what sending uses. A download may
     * deviate for a moment: sometimes you want an English copy to pass around
     * internally, without changing the customer's language for it.
     */
    public QuoteDocumentRenderer.Document document(long orderId, be.enrosed.shared.Language language) {
        return document(orderId, language, SalesPdfOptions.defaults());
    }

    /** Staff download with presentation choices; customer mail and portal use defaults. */
    public QuoteDocumentRenderer.Document document(long orderId,
                                                    be.enrosed.shared.Language language,
                                                    SalesPdfOptions options) {
        SalesOrder order = salesOrders.get(orderId);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        String portalUrl = activePortalUrl(order).orElse(null);
        return renderer.render(order, salesOrders.price(order), customer, portalUrl, language,
                options == null ? SalesPdfOptions.defaults() : options);
    }

    /* ======================================================= customer side */

    /**
     * Fetches the quote through the portal link and records that the customer
     * looked at it.
     *
     * The counter increases on every opening, even when the quote was already
     * viewed: a customer coming back three times tells you something, and you
     * want to see that before you call. The status only jumps from sent to
     * viewed the first time.
     */
    @Transactional
    public SalesOrder openByToken(String token) {
        SalesOrder order = byToken(token);
        if (order.status().isFinal()) return order;

        QuoteStatus status = order.status() == QuoteStatus.VERZONDEN
                ? QuoteStatus.BEKEKEN
                : order.status();

        /* Only the first opening enters the history. Recording every one turns
           the trail into a log where the real news drowns; the count is already
           on the order. */
        if (order.status() == QuoteStatus.VERZONDEN) {
            record(order, QuoteEvent.Type.BEKEKEN, true, null, "Klant heeft de offerte geopend", null);
            notifyAfterCommit(SalesActivityPushNotifier.Ready.customerOpened(
                    order.id(), order.number()));
        }

        return orders.save(withStatus(order, status, order.portalToken(),
                order.sentAt(), Instant.now(), order.viewCount() + 1,
                order.decidedAt(), order.signedByName(), order.customerMessage()));
    }

    public SalesOrder byToken(String token) {
        SalesOrder order = orders.findByPortalToken(token)
                .orElseThrow(() -> new NotFoundException("Offertelink", token));
        SalesLifecycle.requirePortalVisible(order);
        return order;
    }

    /**
     * Full, server-configured customer URL when a real sent portal is live.
     *
     * A reopened quote deliberately keeps its historical token, but that token
     * must not become copyable while the aggregate contains unsent edits. The
     * sent timestamp also prevents malformed or old draft data with a stray
     * token from being advertised as a customer link.
     */
    public Optional<String> activePortalUrl(SalesOrder order) {
        if (order == null
                || order.portalToken() == null || order.portalToken().isBlank()
                || order.sentAt() == null
                || !SalesLifecycle.portalVisible(order)) {
            return Optional.empty();
        }
        return Optional.of(portalUrl(order.portalToken()));
    }

    /**
     * The customer signs for approval. The typed name is the signature;
     * together with the timestamp it is the proof of acceptance.
     */
    @Transactional
    public SalesOrder acceptByCustomer(String token, String signedByName, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        if (signedByName == null || signedByName.isBlank()) {
            throw new BusinessRuleException("Vul je naam in om te tekenen");
        }
        if (signedByName.trim().length() > 255) {
            throw new BusinessRuleException("De naam bij de handtekening is te lang");
        }
        requireMessageLength(message);
        SalesOrder accepted = orders.save(withStatus(order, QuoteStatus.GEACCEPTEERD,
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), signedByName.trim(), message));

        record(order, QuoteEvent.Type.GETEKEND, true, signedByName.trim(),
                "Offerte aanvaard en getekend", message);
        recordActivity("CUSTOMER_ACCEPTED", order, "Klant aanvaardde de offerte");

        mailer.notifyInternal("Offerte " + order.number() + " geaccepteerd",
                signedByName + " heeft offerte " + order.number() + " getekend.");
        notifyAfterCommit(SalesActivityPushNotifier.Ready.customerAccepted(
                order.id(), order.number()));
        return accepted;
    }

    @Transactional
    public SalesOrder rejectByCustomer(String token, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        requireMessageLength(message);
        SalesOrder rejected = orders.save(withStatus(order, QuoteStatus.AFGEWEZEN,
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), null, message));

        record(order, QuoteEvent.Type.AFGEWEZEN, true, null, "Offerte afgewezen door de klant",
                message);
        recordActivity("CUSTOMER_REJECTED", order, "Klant wees de offerte af");

        mailer.notifyInternal("Offerte " + order.number() + " afgewezen",
                "Reden van de klant: " + (message == null ? "geen" : message));
        notifyAfterCommit(SalesActivityPushNotifier.Ready.customerRejected(
                order.id(), order.number()));
        return rejected;
    }

    /**
     * The customer proposes changes.
     *
     * This does not touch the quote. It puts down a proposal that comes to us
     * for review; the order itself stays exactly as it was sent.
     */
    @Transactional
    public QuoteRevision proposeRevision(String token, List<QuoteRevision.Line> proposedLines,
                                         String proposedBy, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        if ((proposedLines == null || proposedLines.isEmpty()) && (message == null || message.isBlank())) {
            throw new BusinessRuleException("Geef aan wat er moet wijzigen");
        }
        if (revisions.findByOrder(order.id()).stream()
                .anyMatch(revision -> revision.status() == RevisionStatus.IN_AFWACHTING)) {
            throw new BusinessRuleException(
                    "Er staat al een wijzigingsvoorstel open. Trek dat eerst in of wacht op verwerking.");
        }
        if (message != null && message.length() > 4000) {
            throw new BusinessRuleException("Het bericht bij het voorstel is te lang (maximaal 4000 tekens)");
        }
        if (proposedBy != null && proposedBy.trim().length() > 255) {
            throw new BusinessRuleException("De naam bij het voorstel is te lang");
        }

        /* Round the customer's quantities up to a full carton. We do it on our
           own lines too; it belongs here just as much, because half a carton
           does not exist and otherwise "13 pieces" lands on the order and
           nothing downstream adds up - not the volume, not the pallets, not
           the freight. Server-side, because a customer can bypass the screen. */
        List<QuoteRevision.Line> rounded = roundToCartons(order, proposedLines);

        QuoteRevision revision = revisions.save(new QuoteRevision(
                null, order.id(), RevisionStatus.IN_AFWACHTING, Instant.now(),
                proposedBy, message, null, null, null,
                rounded));

        orders.save(withStatus(order, QuoteStatus.WIJZIGING_GEVRAAGD, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), null, null, message));

        record(order, QuoteEvent.Type.VOORSTEL, true, proposedBy,
                "Klant stelt een wijziging voor", describe(order, rounded, message));
        recordActivity("CUSTOMER_CHANGE_REQUESTED", order,
                "Klant vroeg een wijziging aan");

        mailer.notifyInternal("Wijziging gevraagd op offerte " + order.number(),
                (proposedBy == null ? "De klant" : proposedBy)
                        + " stelt wijzigingen voor op offerte " + order.number()
                        + (message == null || message.isBlank() ? "" : ":\n\n" + message));
        notifyAfterCommit(SalesActivityPushNotifier.Ready.customerChangeRequested(
                order.id(), order.number()));

        return revision;
    }

    /**
     * What exactly the customer proposes, in plain words.
     *
     * The quantities also live in the revision itself, but that disappears
     * from view once handled. In the history you want to read, half a year
     * later, what was asked without opening a second table.
     */
    private String describe(SalesOrder order, List<QuoteRevision.Line> lines, String message) {
        StringBuilder text = new StringBuilder();
        for (QuoteRevision.Line line : lines) {
            int before = order.lines().stream()
                    .filter(existing -> existing.productId().equals(line.productId()))
                    .mapToInt(SalesOrderLine::quantity)
                    .findFirst()
                    .orElse(0);
            if (before == line.quantity()) continue;

            if (!text.isEmpty()) text.append('\n');
            String name;
            try {
                name = products.get(line.productId()).describe();
            } catch (RuntimeException e) {
                name = "Product " + line.productId();
            }
            text.append(name).append(": ").append(before).append(" naar ").append(line.quantity());
        }
        if (message != null && !message.isBlank()) {
            if (!text.isEmpty()) text.append('\n');
            text.append('"').append(message.trim()).append('"');
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** Records one step in the history of a quote. */
    private void record(SalesOrder order, QuoteEvent.Type type, boolean byCustomer,
                        String actor, String summary, String detail) {
        events.add(new QuoteEvent(null, order.id(), type, Instant.now(),
                actor, byCustomer, summary, detail));
    }

    private ActorRef staffActor() {
        return currentActor == null ? ActorRef.SYSTEM : currentActor.current();
    }

    private void recordActivity(String action, SalesOrder order, String summary) {
        if (activityLog == null) return; // Direct constructor-only unit tests have no CDI fields.
        activityLog.record(action, "SALES_ORDER", String.valueOf(order.id()),
                order.number(), summary);
    }

    private void notifyAfterCommit(SalesActivityPushNotifier.Ready ready) {
        if (salesPushReady == null) return;
        salesPushReady.fire(ready);
    }

    /** The history of a quote, oldest first. */
    public List<QuoteEvent> history(long orderId) {
        return events.findByOrder(orderId);
    }

    /**
     * Rounds every proposed quantity up to a full carton.
     *
     * Zero stays zero: it means "drop this line" and is not a quantity.
     */
    private List<QuoteRevision.Line> roundToCartons(SalesOrder order,
                                                    List<QuoteRevision.Line> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<QuoteRevision.Line> result = new ArrayList<>(lines.size());
        Set<Long> seen = new HashSet<>();
        for (QuoteRevision.Line line : lines) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke voorgestelde regel moet bij een product horen");
            }
            if (!seen.add(line.productId())) {
                throw new BusinessRuleException(
                        "Product " + line.productId() + " staat dubbel in het voorstel");
            }
            int quantity = line.quantity();
            if (quantity < 0) {
                throw new BusinessRuleException("Een voorgesteld aantal kan niet negatief zijn");
            }
            if (quantity > 0) {
                be.enrosed.catalog.domain.Product product;
                try {
                    product = products.get(line.productId());
                } catch (NotFoundException exception) {
                    throw new BusinessRuleException(
                            "Product " + line.productId() + " bestaat niet meer");
                }
                boolean alreadyQuoted = order.lines().stream()
                        .anyMatch(existing -> existing.productId().equals(line.productId()));
                if (!alreadyQuoted && !product.active()) {
                    throw new BusinessRuleException(
                            "Product " + product.describe() + " is niet beschikbaar om toe te voegen");
                }
                int per = product.carton() == null ? 1 : product.carton().piecesPerCarton();
                if (per > 1) {
                    quantity = (int) Math.ceil((double) quantity / per) * per;
                }
            }
            result.add(new QuoteRevision.Line(line.id(), line.productId(), quantity, line.note()));
        }
        return result;
    }

    /**
     * The customer withdraws their proposal.
     *
     * The proposal is not deleted but marked withdrawn: that it existed for a
     * while belongs to the story of this quote. The quote returns to viewed,
     * because it is back in the customer's court.
     */
    @Transactional
    public SalesOrder withdrawRevision(String token) {
        SalesOrder order = byToken(token);

        QuoteRevision pending = revisions.findByOrder(order.id()).stream()
                .filter(revision -> revision.status() == RevisionStatus.IN_AFWACHTING)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Er staat geen voorstel open"));

        revisions.save(new QuoteRevision(pending.id(), pending.salesOrderId(),
                RevisionStatus.INGETROKKEN, pending.proposedAt(), pending.proposedBy(),
                pending.message(), Instant.now(), pending.proposedBy(), null, pending.lines()));

        record(order, QuoteEvent.Type.VOORSTEL_INGETROKKEN, true, pending.proposedBy(),
                "Klant trekt zijn voorstel in", null);

        return orders.save(withStatus(order, QuoteStatus.BEKEKEN, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, order.customerMessage()));
    }

    /* ============================================================ our side */

    public List<QuoteRevision> pendingRevisions() {
        return revisions.findPending();
    }

    public List<QuoteRevision> revisionsFor(long orderId) {
        return revisions.findByOrder(orderId);
    }

    /**
     * An adopted proposal puts the quote back on concept - but the customer
     * is still waiting for the new version. This stays true until the quote
     * actually goes out again, so the screen can keep saying so.
     */
    public boolean awaitsResend(SalesOrder order) {
        if (order.status() != QuoteStatus.CONCEPT || order.id() == null) return false;
        return revisions.findByOrder(order.id()).stream()
                .anyMatch(revision -> adoptedAfterLastSend(order, revision));
    }

    /** The list-screen variant: one query for all orders at once. */
    public Set<Long> awaitsResendIds(List<SalesOrder> orders) {
        Map<Long, SalesOrder> concepts = orders.stream()
                .filter(order -> order.status() == QuoteStatus.CONCEPT && order.id() != null)
                .collect(java.util.stream.Collectors.toMap(SalesOrder::id, order -> order));
        if (concepts.isEmpty()) return Set.of();
        return revisions.findApproved().stream()
                .filter(revision -> {
                    SalesOrder order = concepts.get(revision.salesOrderId());
                    return order != null && adoptedAfterLastSend(order, revision);
                })
                .map(QuoteRevision::salesOrderId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean adoptedAfterLastSend(SalesOrder order, QuoteRevision revision) {
        return revision.status() == RevisionStatus.GOEDGEKEURD && revision.handledAt() != null
                && (order.sentAt() == null || revision.handledAt().isAfter(order.sentAt()));
    }

    /**
     * We adopt the proposal.
     *
     * The customer's quantities go onto the order; lines the customer zeroes
     * disappear. The quote returns to concept so we can still steer it -
     * prices, discount - before it goes out the door again.
     */
    @Transactional
    public SalesOrder approveRevision(long revisionId, String handledBy, String responseMessage) {
        QuoteRevision revision = revision(revisionId);
        requirePending(revision);
        SalesOrder order = salesOrders.get(revision.salesOrderId());

        List<SalesOrderLine> updated = new ArrayList<>();
        for (SalesOrderLine line : order.lines()) {
            QuoteRevision.Line proposal = revision.lines().stream()
                    .filter(candidate -> candidate.productId().equals(line.productId()))
                    .findFirst()
                    .orElse(null);

            if (proposal == null) {
                updated.add(line);
            } else if (proposal.quantity() > 0) {
                updated.add(new SalesOrderLine(line.id(), line.productId(), proposal.quantity(),
                        line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()));
            }
            /* quantity 0 means: drop this line */
        }

        /* Products not yet on the quote that the customer wants added. */
        for (QuoteRevision.Line proposal : revision.lines()) {
            boolean known = order.lines().stream()
                    .anyMatch(line -> line.productId().equals(proposal.productId()));
            if (!known && proposal.quantity() > 0) {
                updated.add(new SalesOrderLine(null, proposal.productId(), proposal.quantity(),
                        null, null, null));
            }
        }

        revisions.save(handled(revision, RevisionStatus.GOEDGEKEURD, handledBy, responseMessage));
        record(order, QuoteEvent.Type.VOORSTEL_OVERGENOMEN, false, handledBy,
                "Voorstel van de klant overgenomen", responseMessage);

        return orders.save(new SalesOrder(
                order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), BusinessDays.add(LocalDate.now(), 30), QuoteStatus.CONCEPT,
                order.incoterm(), order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(),
                order.freightCarrierId(), order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(),
                updated, order.pallets()));
    }

    /**
     * Puts a rejected or expired quote back on concept.
     *
     * A "no" from the customer is rarely the end: usually it was too expensive
     * or the delivery date did not suit. Then you want to adjust that same
     * quote and send it again, with the same number and the same history,
     * instead of building a new one.
     *
     * The portal link stays so the customer does not receive it twice. The
     * decision and the signature are wiped: they belonged to the previous
     * round and must not linger as if it were still signed.
     */
    @Transactional
    public SalesOrder reopen(long orderId) {
        SalesOrder order = salesOrders.get(orderId);

        if (!order.status().canReopen()) {
            throw new BusinessRuleException(order.status() == QuoteStatus.GEACCEPTEERD
                    ? "Offerte " + order.number() + " is aanvaard en getekend. Maak een nieuwe"
                            + " offerte in plaats van deze open te breken."
                    : "Offerte " + order.number() + " staat op "
                            + order.status().name().toLowerCase() + " en hoeft niet heropend.");
        }

        record(order, QuoteEvent.Type.HEROPEND, false, null,
                "Offerte heropend om bij te sturen", null);

        /* A fresh validity date: the old one is usually the very reason the
           quote expired, and a quote leaving today with last month's date
           reads as sloppiness. */
        SalesOrder reopened = withStatus(order, QuoteStatus.CONCEPT, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, null);
        return orders.save(withValidity(reopened, BusinessDays.add(LocalDate.now(), 30)));
    }

    /**
     * Withdraws a quote that is still open. The customer's link then shows
     * it as cancelled, and when asked we tell them by mail with that link;
     * a mail that cannot leave keeps the quote open, so nothing looks
     * cancelled that the customer never heard of.
     */
    @Transactional
    public SalesOrder cancel(long orderId, String reason, boolean notifyCustomer) {
        SalesOrder order = salesOrders.get(orderId);
        if (order.isInvoice()) {
            throw new BusinessRuleException("Een factuur annuleer je niet; maak een creditnota.");
        }
        QuoteStatus status = order.status();
        if (status != QuoteStatus.CONCEPT && !status.isOpenForCustomer()) {
            throw new BusinessRuleException("Offerte " + order.number() + " staat op "
                    + status.name().toLowerCase() + " en kan niet meer geannuleerd worden.");
        }
        String message = reason == null || reason.isBlank() ? null : reason.strip();
        requireMessageLength(message);

        /* A request that never went out can still be withdrawn with a word
           to the customer: they typed it themselves on the website. The mail
           carries a link, so the token is made now when it does not exist. */
        String token = order.portalToken();
        String toldCustomer = null;
        if (notifyCustomer && order.customerId() != null) {
            Customer customer = customers.get(order.customerId());
            if (customer.email() != null && !customer.email().isBlank()) {
                if (token == null || token.isBlank()) token = newToken();
                mailer.sendCancellation(order, customer, portalUrl(token), message);
                toldCustomer = customer.email();
            }
        }

        ActorRef actor = staffActor();
        record(order, QuoteEvent.Type.GEANNULEERD, false, actor.displayName(),
                toldCustomer == null ? "Offerte geannuleerd" : "Offerte geannuleerd, klant verwittigd op " + toldCustomer,
                message);
        SalesOrder cancelled = orders.save(withStatus(order, QuoteStatus.GEANNULEERD, token,
                order.sentAt(), order.viewedAt(), order.viewCount(), Instant.now(), null,
                order.customerMessage()));
        recordActivity("CANCELLED", order, "Offerte geannuleerd");
        return cancelled;
    }

    /** What we told the customer when cancelling, for the portal page. */
    public Optional<String> cancellationMessage(SalesOrder order) {
        if (order.status() != QuoteStatus.GEANNULEERD || order.id() == null) return Optional.empty();
        return history(order.id()).stream()
                .filter(event -> event.type() == QuoteEvent.Type.GEANNULEERD)
                .reduce((first, second) -> second)
                .map(QuoteEvent::detail)
                .filter(detail -> detail != null && !detail.isBlank());
    }

    /** We do not adopt the proposal; the quote stays as it was. */
    @Transactional
    public SalesOrder rejectRevision(long revisionId, String handledBy, String responseMessage) {
        QuoteRevision revision = revision(revisionId);
        requirePending(revision);
        SalesOrder order = salesOrders.get(revision.salesOrderId());

        revisions.save(handled(revision, RevisionStatus.AFGEWEZEN, handledBy, responseMessage));
        record(order, QuoteEvent.Type.VOORSTEL_AFGEWEZEN, false, handledBy,
                "Voorstel van de klant niet overgenomen", responseMessage);

        return orders.save(withStatus(order, QuoteStatus.VERZONDEN, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), null, null,
                order.customerMessage()));
    }

    /* ============================================================ helpers */

    /** Delivery term as readable text, in Belgian date notation. */
    /* The mail travels in the customer's language, so these fragments come
       from the same bundle as the portal - hardcoded Dutch here leaked
       "leverbaar vanaf" into translated mails. */
    private static String deliveryTermOf(PricedOrder.Line line,
                                         be.enrosed.shared.Language language) {
        var text = be.enrosed.shared.DocumentText.of(language);
        if (line.inStock() && line.deliveryDate() != null) {
            return text.get("portalDeliverableFrom") + " " + be.enrosed.shared.DocumentText
                    .date(java.time.LocalDate.parse(line.deliveryDate()), language);
        }
        if (line.deliveryWeek() != null && !line.deliveryWeek().isBlank()) {
            return be.enrosed.shared.DocumentText.week(line.deliveryWeek(), language);
        }
        return text.get("portalTermToBeDetermined");
    }

    private QuoteRevision revision(long id) {
        return revisions.findById(id).orElseThrow(() -> new NotFoundException("Wijzigingsvoorstel", id));
    }

    private void requirePending(QuoteRevision revision) {
        if (revision.status() != RevisionStatus.IN_AFWACHTING) {
            throw new BusinessRuleException("Dit voorstel is al behandeld");
        }
    }

    private void requireOpen(SalesOrder order) {
        if (!order.status().isOpenForCustomer()) {
            throw new BusinessRuleException(
                    "Deze offerte staat niet open voor een reactie (status "
                            + order.status().name().toLowerCase() + ")");
        }
        if (order.validUntil() != null && order.validUntil().isBefore(LocalDate.now())) {
            throw new BusinessRuleException("Deze offerte is vervallen op " + order.validUntil());
        }
    }

    private String portalUrl(String token) {
        return portalBaseUrl.replaceAll("/+$", "") + "/offerte/" + token;
    }

    /** 32 random bytes: too long to guess, short enough for a link. */
    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SalesOrder withStatus(SalesOrder order, QuoteStatus status, String token,
                                         Instant sentAt, Instant viewedAt, int viewCount,
                                         Instant decidedAt, String signedBy, String customerMessage) {
        return withStatus(order, status, token, sentAt, viewedAt, viewCount, decidedAt, signedBy,
                customerMessage, order.deliveryTerms(), order.freight());
    }

    private static SalesOrder withStatus(SalesOrder order, QuoteStatus status, String token,
                                         Instant sentAt, Instant viewedAt, int viewCount,
                                         Instant decidedAt, String signedBy, String customerMessage,
                                         DeliveryTermsState deliveryTerms, FreightState freight) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), status, order.incoterm(),
                order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(),
                token, sentAt, viewedAt, viewCount, decidedAt, signedBy, customerMessage,
                order.internalNotes(), deliveryTerms, freight, order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(),
                order.freightCarrierId(), order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(),
                order.lines(), order.pallets());
    }

    private static QuoteRevision handled(QuoteRevision revision, RevisionStatus status,
                                         String handledBy, String responseMessage) {
        return new QuoteRevision(revision.id(), revision.salesOrderId(), status,
                revision.proposedAt(), revision.proposedBy(), revision.message(),
                Instant.now(), handledBy, responseMessage, revision.lines());
    }

    private static void requireMessageLength(String message) {
        if (message != null && message.length() > 4000) {
            throw new BusinessRuleException("Het bericht is te lang (maximaal 4000 tekens)");
        }
    }

    private static SalesOrder withValidity(SalesOrder order, LocalDate validUntil) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), validUntil, order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(),
                order.freightCarrierId(), order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(),
                order.lines(), order.pallets());
    }
}
