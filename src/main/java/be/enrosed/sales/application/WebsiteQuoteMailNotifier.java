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
        facts.add(new TeamFact("Ordernummer", value(order.number())));

        List<TeamLine> lines = new ArrayList<>();
        int pieces = 0;
        for (SalesOrderLine line : order.lines()) {
            pieces += Math.max(0, line.quantity());
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
