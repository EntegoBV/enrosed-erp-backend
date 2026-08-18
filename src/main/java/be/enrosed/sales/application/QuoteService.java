package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    public QuoteService(SalesRepositories.Orders orders, SalesRepositories.Revisions revisions,
                        SalesOrderService salesOrders, CustomerService customers,
                        QuoteDocumentRenderer renderer, QuoteMailer mailer,
                        be.enrosed.catalog.application.ProductService products,
                        SalesRepositories.Events events) {
        this.orders = orders;
        this.revisions = revisions;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.renderer = renderer;
        this.mailer = mailer;
        this.products = products;
        this.events = events;
    }

    /* ============================================================= sending */

    /**
     * Sends the quote to the customer: builds the PDF, adds a portal link
     * and mails the whole thing.
     */
    @Transactional
    public SalesOrder send(long orderId, String personalMessage) {
        SalesOrder order = salesOrders.get(orderId);

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
                        deliveryTermOf(line),
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
                        freightState == FreightState.AANGEVULD));

        record(order, QuoteEvent.Type.VERSTUURD, false, null,
                order.sentAt() == null ? "Offerte verstuurd" : "Offerte opnieuw verstuurd",
                "Naar " + customer.email());
        if (terms == DeliveryTermsState.AANGEVULD) {
            record(order, QuoteEvent.Type.LEVERTERMIJN_INGEVULD, false, null,
                    "Levertermijn ingevuld", null);
        }
        if (freightState == FreightState.AANGEVULD) {
            record(order, QuoteEvent.Type.VRACHT_INGEVULD, false, null, "Vracht ingevuld", null);
        }

        return orders.save(withStatus(order, QuoteStatus.VERZONDEN,
                token, Instant.now(), order.viewedAt(), order.viewCount(),
                null, null, order.customerMessage(), terms, freightState));
    }

    /** Rebuild the PDF, for instance to review or download it ourselves. */
    public QuoteDocumentRenderer.Document document(long orderId) {
        return document(orderId, null);
    }

    /**
     * The same PDF in a language of choice.
     *
     * Default is the customer's - that is what sending uses. A download may
     * deviate for a moment: sometimes you want an English copy to pass around
     * internally, without changing the customer's language for it.
     */
    public QuoteDocumentRenderer.Document document(long orderId, be.enrosed.shared.Language language) {
        SalesOrder order = salesOrders.get(orderId);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        String portalUrl = order.portalToken() == null ? null : portalUrl(order.portalToken());

        if (renderer instanceof be.enrosed.sales.adapter.out.document.PdfQuoteRenderer pdf) {
            return pdf.render(order, salesOrders.price(order), customer, portalUrl, language);
        }
        return renderer.render(order, salesOrders.price(order), customer, portalUrl);
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
        }

        return orders.save(withStatus(order, status, order.portalToken(),
                order.sentAt(), Instant.now(), order.viewCount() + 1,
                order.decidedAt(), order.signedByName(), order.customerMessage()));
    }

    public SalesOrder byToken(String token) {
        return orders.findByPortalToken(token)
                .orElseThrow(() -> new NotFoundException("Offertelink", token));
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
        SalesOrder accepted = orders.save(withStatus(order, QuoteStatus.GEACCEPTEERD,
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), signedByName.trim(), message));

        record(order, QuoteEvent.Type.GETEKEND, true, signedByName.trim(),
                "Offerte aanvaard en getekend", message);

        mailer.notifyInternal("Offerte " + order.number() + " geaccepteerd",
                signedByName + " heeft offerte " + order.number() + " getekend.");
        return accepted;
    }

    @Transactional
    public SalesOrder rejectByCustomer(String token, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        SalesOrder rejected = orders.save(withStatus(order, QuoteStatus.AFGEWEZEN,
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), null, message));

        record(order, QuoteEvent.Type.AFGEWEZEN, true, null, "Offerte afgewezen door de klant",
                message);

        mailer.notifyInternal("Offerte " + order.number() + " afgewezen",
                "Reden van de klant: " + (message == null ? "geen" : message));
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

        /* Round the customer's quantities up to a full carton. We do it on our
           own lines too; it belongs here just as much, because half a carton
           does not exist and otherwise "13 pieces" lands on the order and
           nothing downstream adds up - not the volume, not the pallets, not
           the freight. Server-side, because a customer can bypass the screen. */
        List<QuoteRevision.Line> rounded = roundToCartons(proposedLines);

        QuoteRevision revision = revisions.save(new QuoteRevision(
                null, order.id(), RevisionStatus.IN_AFWACHTING, Instant.now(),
                proposedBy, message, null, null, null,
                rounded));

        orders.save(withStatus(order, QuoteStatus.WIJZIGING_GEVRAAGD, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), null, null, message));

        record(order, QuoteEvent.Type.VOORSTEL, true, proposedBy,
                "Klant stelt een wijziging voor", describe(order, rounded, message));

        mailer.notifyInternal("Wijziging gevraagd op offerte " + order.number(),
                (proposedBy == null ? "De klant" : proposedBy)
                        + " stelt wijzigingen voor op offerte " + order.number()
                        + (message == null || message.isBlank() ? "" : ":\n\n" + message));

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

    /** The history of a quote, oldest first. */
    public List<QuoteEvent> history(long orderId) {
        return events.findByOrder(orderId);
    }

    /**
     * Rounds every proposed quantity up to a full carton.
     *
     * Zero stays zero: it means "drop this line" and is not a quantity.
     */
    private List<QuoteRevision.Line> roundToCartons(List<QuoteRevision.Line> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<QuoteRevision.Line> result = new ArrayList<>(lines.size());
        for (QuoteRevision.Line line : lines) {
            int quantity = line.quantity();
            if (quantity > 0 && line.productId() != null) {
                int per = products.get(line.productId()).carton().piecesPerCarton();
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
                order.orderDate(), LocalDate.now().plusDays(30), QuoteStatus.CONCEPT,
                order.incoterm(), order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(), updated,
                order.pallets()));
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
        return orders.save(withStatus(order, QuoteStatus.CONCEPT, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, null));
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
    private static String deliveryTermOf(PricedOrder.Line line) {
        if (line.inStock() && line.deliveryDate() != null) {
            return "leverbaar vanaf " + be.enrosed.shared.DocumentFormat.beDate(line.deliveryDate());
        }
        if (line.deliveryWeek() != null && !line.deliveryWeek().isBlank()) {
            return "levering in " + be.enrosed.shared.DocumentFormat.week(line.deliveryWeek());
        }
        return "levertermijn nog te bepalen";
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
                order.lines(), order.pallets());
    }

    private static QuoteRevision handled(QuoteRevision revision, RevisionStatus status,
                                         String handledBy, String responseMessage) {
        return new QuoteRevision(revision.id(), revision.salesOrderId(), status,
                revision.proposedAt(), revision.proposedBy(), revision.message(),
                Instant.now(), handledBy, responseMessage, revision.lines());
    }
}
