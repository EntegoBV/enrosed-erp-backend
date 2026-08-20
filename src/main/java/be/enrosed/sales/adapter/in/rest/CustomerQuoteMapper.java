package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.RevisionStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Maps internal orders onto the explicit, customer-safe REST contract. */
@ApplicationScoped
public class CustomerQuoteMapper {

    private final QuoteService quotes;
    private final SalesOrderService salesOrders;
    private final CustomerService customers;
    private final ProductService products;

    public CustomerQuoteMapper(QuoteService quotes, SalesOrderService salesOrders,
                               CustomerService customers, ProductService products) {
        this.quotes = quotes;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.products = products;
    }

    /** Public portal view. The caller has already passed token lifecycle checks. */
    public CustomerQuoteView portal(SalesOrder order, String preferredLanguage) {
        return view(order, preferredLanguage, false);
    }

    /**
     * Read-only staff preview. It never creates or resolves a portal token and
     * cannot record a customer view or expose response actions.
     */
    public CustomerQuoteView preview(SalesOrder order, String preferredLanguage) {
        return view(order, preferredLanguage, true);
    }

    private CustomerQuoteView view(SalesOrder order, String preferred, boolean preview) {
        PricedOrder priced = salesOrders.price(order);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        Language language = preferred != null && !preferred.isBlank()
                ? Language.of(preferred)
                : customer == null ? Language.NL : customer.language();

        List<CustomerQuoteView.CustomerLine> lines = priced.lines().stream()
                .map(line -> new CustomerQuoteView.CustomerLine(
                        line.productId(), line.sku(), line.customerDescription(), line.photoUrl(),
                        line.quantity(), line.cartons(),
                        order.palletPositionsForProduct(line.productId(), line.pallets()), line.cbm(),
                        piecesPerCarton(line.productId()),
                        line.unitPrice(), line.discountPct(), line.net(),
                        line.inventoryKnown(), line.inStock(),
                        line.deliveryDate(), line.deliveryWeek()))
                .toList();

        PricedOrder.Totals totals = priced.totals();
        int effectivePallets = totals.palletsManual() > 0
                ? totals.palletsManual() : totals.palletsStrict();
        CustomerQuoteView.CustomerTotals customerTotals = new CustomerQuoteView.CustomerTotals(
                totals.pieces(), totals.cartons(), effectivePallets, totals.cbm(),
                totals.subtotal(), totals.orderDiscountPercent(), totals.orderDiscountAmount(),
                totals.extraDiscountPercent(), totals.extraDiscountLabel(), totals.extraDiscountAmount(),
                totals.goodsTotal(), totals.freight(), totals.handling(),
                totals.total(), totals.vatRatePct(), totals.vatAmount(), totals.totalInclVat(),
                totals.vatTreatment().labelIn(language),
                totals.vatTreatment().legalMentionIn(language));

        List<CustomerQuoteView.PendingProposal> proposals = quotes.revisionsFor(order.id()).stream()
                .map(revision -> new CustomerQuoteView.PendingProposal(
                        customerFacingStatus(order, revision),
                        revision.proposedAt() == null ? null : revision.proposedAt().toString(),
                        revision.message(), revision.responseMessage()))
                .toList();

        return new CustomerQuoteView(
                preview,
                order.number(), preview ? order.status().name() : customerFacingOrderStatus(order),
                order.orderDate() == null ? null : order.orderDate().toString(),
                order.validUntil() == null ? null : order.validUntil().toString(),
                order.incoterm(), order.notes(),
                customer == null ? null : customer.company(),
                customer == null ? null : customer.contact(),
                order.countryCode(),
                lines, customerTotals,
                !preview && order.status().isOpenForCustomer(),
                order.signedByName(), proposals,
                order.deliveryTerms().name(),
                order.freight().name(),
                order.loadMode().name(),
                order.freightPricingStrategy().name(),
                language.name(),
                DocumentText.of(language));
    }

    private int piecesPerCarton(Long productId) {
        if (productId == null) return 1;
        try {
            int per = products.get(productId).carton().piecesPerCarton();
            return Math.max(1, per);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static String customerFacingStatus(SalesOrder order, QuoteRevision revision) {
        boolean takenOverButNotResent = revision.status() == RevisionStatus.GOEDGEKEURD
                && (order.sentAt() == null
                    || (revision.handledAt() != null && order.sentAt().isBefore(revision.handledAt())));
        return takenOverButNotResent
                ? RevisionStatus.IN_AFWACHTING.name()
                : revision.status().name();
    }

    private static String customerFacingOrderStatus(SalesOrder order) {
        return order.status() == QuoteStatus.CONCEPT
                ? QuoteStatus.WIJZIGING_GEVRAAGD.name()
                : order.status().name();
    }
}
