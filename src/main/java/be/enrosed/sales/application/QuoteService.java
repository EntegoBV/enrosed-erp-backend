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
 * De offerteworkflow: versturen, laten bekijken, en wijzigingen van de klant
 * terugkrijgen.
 *
 * De belangrijkste regel zit in {@link #proposeRevision}: de klant wijzigt de
 * offerte niet zelf. Hij legt een voorstel neer en wij keuren het goed, passen
 * het aan of wijzen het af. Een verzonden document mag niet onder onze handen
 * veranderen - anders weet niemand meer welke versie er getekend is.
 *
 * De klant komt binnen met een portaltoken in plaats van een account. Wie de
 * link heeft mag de offerte zien en erop reageren; de link staat alleen in de
 * mail aan dat adres.
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

    /* ============================================================ versturen */

    /**
     * Verstuurt de offerte naar de klant: maakt de PDF, zet er een portallink
     * bij en mailt het geheel.
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

        /* Token blijft staan bij een tweede verzending: de klant heeft de link al. */
        String token = order.portalToken() == null ? newToken() : order.portalToken();
        String portalUrl = portalUrl(token);

        QuoteDocumentRenderer.Document document = renderer.render(order, priced, customer, portalUrl);

        /* De levertermijnen gaan mee in de mail. Staat er voor elke regel een
           datum of een week, dan is dat het antwoord waar een klant die eerder
           een wijziging vroeg op wachtte - dat hoort hij niet pas in de PDF te
           ontdekken. */
        List<QuoteMailer.DeliveryLine> deliveryLines = priced.lines().stream()
                .map(line -> new QuoteMailer.DeliveryLine(
                        line.description(),
                        deliveryTermOf(line),
                        line.inStock() || (line.deliveryWeek() != null && !line.deliveryWeek().isBlank())))
                .toList();

        /* Stand van zaken rond de levertermijnen bijhouden. Vertrekt er een
           offerte met een regel zonder termijn, dan weet de klant dat wij nog
           moeten laten weten wanneer we leveren. Is die termijn er bij een
           volgende zending wel, dan is dát het nieuws van deze mail. */
        boolean allKnown = deliveryLines.stream().allMatch(QuoteMailer.DeliveryLine::known);
        DeliveryTermsState terms = !allKnown
                ? DeliveryTermsState.TE_BEPALEN
                : order.deliveryTerms() == DeliveryTermsState.TE_BEPALEN
                        ? DeliveryTermsState.AANGEVULD
                        : order.deliveryTerms();

        /* Dezelfde weg voor de vracht: vertrekt ze als open post, dan weet de
           klant dat wij het bedrag nog laten weten. */
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

    /** De PDF opnieuw opbouwen, bijvoorbeeld om zelf na te kijken of te downloaden. */
    public QuoteDocumentRenderer.Document document(long orderId) {
        return document(orderId, null);
    }

    /**
     * Dezelfde PDF in een zelfgekozen taal.
     *
     * Standaard die van de klant - dat is wat er bij het versturen gebeurt. Bij
     * het downloaden mag je er even van afwijken: soms wil je een Engelse versie
     * om intern door te geven, zonder daarvoor de taal van de klant te wijzigen.
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

    /* ============================================================ klantkant */

    /**
     * Haalt de offerte op via de portallink en houdt bij dat de klant gekeken heeft.
     *
     * De teller loopt op bij elke opening, ook als de offerte al bekeken was: dat
     * een klant er drie keer op terugkomt zegt iets, en dat wil je zien voor je
     * belt. De status springt alleen de eerste keer van verzonden naar bekeken.
     */
    @Transactional
    public SalesOrder openByToken(String token) {
        SalesOrder order = byToken(token);
        if (order.status().isFinal()) return order;

        QuoteStatus status = order.status() == QuoteStatus.VERZONDEN
                ? QuoteStatus.BEKEKEN
                : order.status();

        /* Alleen de eerste opening komt in de geschiedenis. Elke keer vastleggen
           maakt van het spoor een logboek waarin het echte nieuws verdwijnt; het
           aantal keer staat al op de order. */
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
     * De klant tekent voor akkoord. De ingetikte naam is de handtekening;
     * samen met het tijdstip is dat het bewijs van aanvaarding.
     */
    @Transactional
    public SalesOrder acceptByCustomer(String token, String signedByName, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        if (signedByName == null || signedByName.isBlank()) {
            throw new BusinessRuleException("Vul je naam in om te tekenen");
        }
        SalesOrder accepted = orders.save(new SalesOrder(
                order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), QuoteStatus.GEACCEPTEERD,
                order.incoterm(), order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), signedByName.trim(), message, order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(), order.lines()));

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
        SalesOrder rejected = orders.save(new SalesOrder(
                order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), QuoteStatus.AFGEWEZEN,
                order.incoterm(), order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(),
                Instant.now(), null, message, order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(), order.lines()));

        record(order, QuoteEvent.Type.AFGEWEZEN, true, null, "Offerte afgewezen door de klant",
                message);

        mailer.notifyInternal("Offerte " + order.number() + " afgewezen",
                "Reden van de klant: " + (message == null ? "geen" : message));
        return rejected;
    }

    /**
     * De klant stelt wijzigingen voor.
     *
     * Dit past de offerte niet aan. Het legt een voorstel neer dat bij ons ter
     * beoordeling komt; de order zelf blijft staan zoals hij verstuurd is.
     */
    @Transactional
    public QuoteRevision proposeRevision(String token, List<QuoteRevision.Line> proposedLines,
                                         String proposedBy, String message) {
        SalesOrder order = byToken(token);
        requireOpen(order);
        if ((proposedLines == null || proposedLines.isEmpty()) && (message == null || message.isBlank())) {
            throw new BusinessRuleException("Geef aan wat er moet wijzigen");
        }

        /* Aantallen van de klant naar boven op een volle doos. Wij doen dat op
           onze eigen regels ook; het hoort hier net zo goed te gebeuren, want
           een halve doos bestaat niet en anders belandt "13 stuks" op de order
           en klopt verderop niets meer - niet het volume, niet de pallets, niet
           de vracht. Server-side, want een klant kan het scherm omzeilen. */
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
     * Wat de klant precies voorstelt, in gewone taal.
     *
     * De aantallen staan ook in de revisie zelf, maar die verdwijnt uit beeld
     * zodra ze afgehandeld is. In de geschiedenis wil je een half jaar later nog
     * kunnen lezen wat er gevraagd werd zonder een tweede tabel te moeten
     * openslaan.
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

    /** Legt een stap vast in de geschiedenis van een offerte. */
    private void record(SalesOrder order, QuoteEvent.Type type, boolean byCustomer,
                        String actor, String summary, String detail) {
        events.add(new QuoteEvent(null, order.id(), type, Instant.now(),
                actor, byCustomer, summary, detail));
    }

    /** De geschiedenis van een offerte, oudste eerst. */
    public List<QuoteEvent> history(long orderId) {
        return events.findByOrder(orderId);
    }

    /**
     * Rondt elk voorgesteld aantal naar boven af op een volle doos.
     *
     * Nul blijft nul: dat betekent "deze regel mag eruit" en is geen aantal.
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
     * De klant trekt zijn voorstel weer in.
     *
     * Het voorstel wordt niet gewist maar op ingetrokken gezet: dat het er even
     * gestaan heeft hoort bij het verhaal van deze offerte. De offerte gaat
     * terug naar bekeken, want ze ligt weer bij de klant.
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

    /* ========================================================= onze kant */

    public List<QuoteRevision> pendingRevisions() {
        return revisions.findPending();
    }

    public List<QuoteRevision> revisionsFor(long orderId) {
        return revisions.findByOrder(orderId);
    }

    /**
     * Wij nemen het voorstel over.
     *
     * De aantallen van de klant gaan naar de order; regels die de klant op nul
     * zet verdwijnen. De offerte gaat terug naar concept zodat we hem nog
     * kunnen bijsturen - prijzen, korting - voor hij opnieuw de deur uit gaat.
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
            /* aantal 0 betekent: deze regel mag eruit */
        }

        /* Producten die er nog niet op stonden maar die de klant erbij wil. */
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
                order.deliveryTerms(), order.freight(), order.manualFreightEur(), updated));
    }

    /**
     * Zet een afgewezen of verlopen offerte terug op concept.
     *
     * Een "nee" van de klant is zelden het einde: meestal was het te duur of
     * kwam de levertermijn niet uit. Dan wil je dezelfde offerte bijsturen en
     * opnieuw sturen, met dezelfde nummering en dezelfde geschiedenis, in
     * plaats van een nieuwe te moeten opbouwen.
     *
     * De portallink blijft staan zodat de klant hem niet twee keer krijgt. De
     * beslissing en de handtekening worden gewist: die hoorden bij de vorige
     * ronde en mogen niet blijven staan alsof er nog voor getekend is.
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

        /* Een nieuwe geldigheidsdatum: de oude is meestal net de reden dat ze
           verlopen is, en een offerte die vandaag vertrekt met een datum van
           vorige maand leest als slordigheid. */
        return orders.save(withStatus(order, QuoteStatus.CONCEPT, order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(),
                null, null, null));
    }

    /** Wij nemen het voorstel niet over; de offerte blijft zoals hij was. */
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

    /** Levertermijn als leesbare tekst, in Belgische datumnotatie. */
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

    /** 32 willekeurige bytes: te lang om te raden, kort genoeg voor een link. */
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
                order.lines());
    }

    private static QuoteRevision handled(QuoteRevision revision, RevisionStatus status,
                                         String handledBy, String responseMessage) {
        return new QuoteRevision(revision.id(), revision.salesOrderId(), status,
                revision.proposedAt(), revision.proposedBy(), revision.message(),
                Instant.now(), handledBy, responseMessage, revision.lines());
    }
}
