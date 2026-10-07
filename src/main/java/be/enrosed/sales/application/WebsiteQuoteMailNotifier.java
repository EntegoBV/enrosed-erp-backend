package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.mail.InternalMessageSender;
import be.enrosed.shared.mail.InternalMessageSender.TeamFact;
import be.enrosed.shared.mail.InternalMessageSender.TeamLine;
import be.enrosed.shared.mail.InternalMessageSender.TeamNotice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Mails the team every website quotation request once it is safely in the
 * ERP: who asked, from where, what and how much, and one click to the
 * order. The push notification stays terse and PII-free; this is the
 * readable version for the mailbox. A mail problem never touches the
 * saved request.
 */
@ApplicationScoped
public class WebsiteQuoteMailNotifier {

    private static final Logger LOG = Logger.getLogger(WebsiteQuoteMailNotifier.class);

    private final SalesOrderService salesOrders;
    private final CustomerService customers;
    private final ProductService products;
    private final InternalMessageSender messages;
    private final String portalBaseUrl;
    /** Website orders of logged-in customers: who ordered through which login, and where it is delivered. */
    @Inject Instance<WebOrders> webOrders;
    @Inject Instance<WebOrderDeliveries> deliveries;

    public WebsiteQuoteMailNotifier(SalesOrderService salesOrders, CustomerService customers,
                                    ProductService products, InternalMessageSender messages,
                                    @ConfigProperty(name = "enrosed.portal.base-url",
                                            defaultValue = "http://localhost:4321") String portalBaseUrl) {
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.products = products;
        this.messages = messages;
        this.portalBaseUrl = portalBaseUrl;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebsiteQuotePushNotifier.Ready ready) {
        try {
            SalesOrder order = salesOrders.get(ready.orderId());
            Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
            messages.sendTeamNotice(notice(order, customer));
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Teammail voor websiteaanvraag %s kon niet vertrekken", ready.reference());
        }
    }

    /** A logged-in customer placed an order; the team reads what, for where, and that it can still change. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderPlaced(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Placed placed) {
        sendOrderNotice(placed.orderId(), placed.number(), "Nieuwe websitebestelling " + placed.number(),
                "Website · nieuwe bestelling",
                "Een ingelogde klant plaatste een bestelling via de website. De klant kan ze nog wijzigen of annuleren tot je ze in verwerking neemt.",
                true);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Changed changed) {
        sendOrderNotice(changed.orderId(), changed.number(),
                "Websitebestelling " + changed.number() + " gewijzigd door de klant",
                "Website · bestelling gewijzigd",
                "De klant wijzigde de bestelling op de website (versie " + changed.revision() + "): "
                        + value(changed.summary()) + ". Open de laatste versie voor je ze in verwerking neemt.",
                true);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Cancelled cancelled) {
        sendOrderNotice(cancelled.orderId(), cancelled.number(),
                "Websitebestelling " + cancelled.number() + " geannuleerd door de klant",
                "Website · bestelling geannuleerd",
                "De klant annuleerde deze bestelling op de website voor ze in verwerking was genomen. Er hoeft niets meer te gebeuren.",
                false);
    }

    private void sendOrderNotice(long orderId, String number, String subject, String kicker, String intro, boolean withLines) {
        try {
            SalesOrder order = salesOrders.get(orderId);
            Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
            messages.sendTeamNotice(orderNotice(order, customer, subject, kicker, intro, withLines));
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Teammail voor websitebestelling %s kon niet vertrekken", number);
        }
    }

    /**
     * The team mail about a website order: the facts of a request, with the
     * delivery and contact the customer typed for THIS order and the login
     * that ordered. A cancelled order lists its facts only.
     */
    TeamNotice orderNotice(SalesOrder order, Customer customer, String subject, String kicker, String intro, boolean withLines) {
        var delivery = deliveries == null || !deliveries.isResolvable() ? null : deliveries.get().forDocument(order).orElse(null);
        var row = webOrders == null || !webOrders.isResolvable() || order.id() == null ? null
                : webOrders.get().find(order.id()).orElse(null);
        List<TeamFact> facts = new ArrayList<>();
        if (customer != null) {
            facts.add(new TeamFact("Bedrijf", value(customer.company())));
            facts.add(new TeamFact("Contact", value(customer.contact())));
            facts.add(new TeamFact("E-mail", value(customer.email())));
            facts.add(new TeamFact("Telefoon", value(customer.phone())));
            facts.add(new TeamFact("BTW-nummer", value(customer.vatNumber())));
            facts.add(new TeamFact("Land", value(customer.countryCode())));
            facts.add(new TeamFact("Taal", customer.language().code().toUpperCase()));
        }
        if (delivery != null) {
            boolean pickup = WebOrderDeliveries.PICKUP.equals(delivery.fulfillment());
            String place = pickup ? value(delivery.pickupLabel())
                    : join(delivery.address(), join(delivery.postalCode(), delivery.city(), " "), ", ");
            facts.add(new TeamFact("Levering", (pickup ? "Afhaling (EXW)" : "Levering (DAP)") + (place.isBlank() ? "" : " · " + place)));
            String contact = join(delivery.contactName(), delivery.contactPhone(), " · ");
            if (!contact.isBlank()) facts.add(new TeamFact("Contact voor deze bestelling", contact));
        }
        if (row != null) facts.add(new TeamFact("Besteld via klantlogin", value(row.accountEmail())));
        facts.add(new TeamFact("Ordernummer", value(order.number())));

        List<TeamLine> lines = withLines ? productLines(order) : List.of();
        String note = withLines ? order.notes() : null;
        String companyLabel = customer == null || customer.company() == null || customer.company().isBlank()
                ? value(order.number()) : customer.company();
        String erpUrl = portalBaseUrl.replaceAll("/+$", "") + "/sales/" + order.id();
        String mailto = customer == null || customer.email() == null || customer.email().isBlank() ? null
                : "mailto:" + customer.email();
        StringBuilder text = new StringBuilder(subject + "\n" + intro + "\n");
        for (TeamFact fact : facts) text.append(fact.label()).append(": ").append(fact.value()).append('\n');
        for (TeamLine line : lines) text.append("- ").append(line.description()).append(" × ").append(line.quantity()).append('\n');
        if (note != null && !note.isBlank()) text.append("\nOpmerking:\n").append(note).append('\n');
        text.append('\n').append(erpUrl);

        return new TeamNotice(subject + " · " + companyLabel, kicker, companyLabel, intro,
                List.copyOf(facts), List.copyOf(lines),
                "Opmerking van de klant", note,
                "Open in het ERP", erpUrl,
                mailto == null ? null : "Mail de klant", mailto,
                text.toString());
    }

    TeamNotice notice(SalesOrder order, Customer customer) {
        List<TeamFact> facts = new ArrayList<>();
        if (customer != null) {
            facts.add(new TeamFact("Bedrijf", value(customer.company())));
            facts.add(new TeamFact("Contact", value(customer.contact())));
            facts.add(new TeamFact("E-mail", value(customer.email())));
            facts.add(new TeamFact("Telefoon", value(customer.phone())));
            facts.add(new TeamFact("BTW-nummer", value(customer.vatNumber())));
            facts.add(new TeamFact("Land", value(customer.countryCode())));
            facts.add(new TeamFact("Taal", customer.language().code().toUpperCase()));
            String destination = join(customer.address(), join(customer.postalCode(), customer.city(), " "), ", ");
            facts.add(new TeamFact("Levering", "EXW".equalsIgnoreCase(order.incoterm())
                    ? "Afhaling (EXW)" : "Levering (DAP)" + (destination.isBlank() ? "" : " · " + destination)));
        }
        /* Only a line that STARTS with the marker counts: our own note line, never text a customer typed. */
        if (order.internalNotes() != null && order.internalNotes().lines()
                .anyMatch(line -> line.startsWith(WebsiteQuoteLoginRequested.NOTE_MARKER))) {
            facts.add(new TeamFact("Login gevraagd", "Ja · goedkeuren bij Login-aanvragen"));
        }
        facts.add(new TeamFact("Ordernummer", value(order.number())));

        List<TeamLine> lines = productLines(order);
        int pieces = order.lines().stream().mapToInt(line -> Math.max(0, line.quantity())).sum();

        String companyLabel = customer == null || customer.company() == null || customer.company().isBlank()
                ? value(order.number()) : customer.company();
        String erpUrl = portalBaseUrl.replaceAll("/+$", "") + "/sales/" + order.id();
        String mailto = customer == null || customer.email() == null || customer.email().isBlank() ? null
                : "mailto:" + customer.email();
        StringBuilder text = new StringBuilder("Nieuwe websiteaanvraag " + order.number() + "\n");
        for (TeamFact fact : facts) text.append(fact.label()).append(": ").append(fact.value()).append('\n');
        for (TeamLine line : lines) text.append("- ").append(line.description()).append(" × ").append(line.quantity()).append('\n');
        if (order.notes() != null && !order.notes().isBlank()) text.append("\nOpmerking:\n").append(order.notes()).append('\n');
        text.append('\n').append(erpUrl);

        return new TeamNotice(
                "Nieuwe websiteaanvraag " + order.number() + " · " + companyLabel,
                "Website · nieuwe offerteaanvraag",
                companyLabel,
                "Klaar voor beoordeling in Verkoop · " + DocumentFormat.amount(BigDecimal.valueOf(pieces))
                        + " stuks in " + lines.size() + (lines.size() == 1 ? " regel" : " regels")
                        + ". Prijzen en logistiek zijn nog door ons te bevestigen.",
                List.copyOf(facts), List.copyOf(lines),
                "Opmerking van de klant", order.notes(),
                "Open in het ERP", erpUrl,
                mailto == null ? null : "Mail de klant", mailto,
                text.toString());
    }

    /** One line per product: name and colour, the pieces, and the cartons where the carton content is known. */
    private List<TeamLine> productLines(SalesOrder order) {
        List<TeamLine> lines = new ArrayList<>();
        for (SalesOrderLine line : order.lines()) {
            String description = "Product " + line.productId();
            String note = null;
            try {
                Product product = line.productId() == null ? null : products.get(line.productId());
                if (product != null) {
                    description = product.name() + (product.colour() == null || product.colour().isBlank()
                            ? "" : " - " + product.colour());
                    note = product.sku();
                    if (product.carton() != null && product.carton().piecesPerCarton() > 0) {
                        int cartons = product.carton().cartonsFor(line.quantity());
                        note = (note == null ? "" : note + " · ") + cartons + (cartons == 1 ? " doos" : " dozen")
                                + " van " + product.carton().piecesPerCarton();
                    }
                }
            } catch (RuntimeException ignored) {
                /* A product that vanished in the meantime still gets its line; the ERP shows the rest. */
            }
            lines.add(new TeamLine(description, DocumentFormat.amount(BigDecimal.valueOf(line.quantity())) + " st", note));
        }
        return lines;
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    private static String join(String left, String right, String separator) {
        boolean hasLeft = left != null && !left.isBlank();
        boolean hasRight = right != null && !right.isBlank();
        if (hasLeft && hasRight) return left.strip() + separator + right.strip();
        return hasLeft ? left.strip() : hasRight ? right.strip() : "";
    }
}
