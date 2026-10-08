package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

/**
 * "Leveradres overnemen": staff fill the empty address fields of a customer
 * record from the delivery address of one of that customer's documents.
 *
 * The document is locked the way a customer-session writer locks it, never
 * through the staff gate: taking an address over is no work on the order, so
 * a website order the customer may still change stays theirs, is not taken
 * into processing and sends no mail. What staff confirmed travels in the
 * call instead of a revision; a delivery that changed since is refused.
 */
@ApplicationScoped
public class CustomerAddressTakeover {
    static final String NOT_POSSIBLE = "Het leveradres kan niet overgenomen worden. Vul het adres in bij de klant.";
    static final String CHANGED = "Het leveradres is intussen gewijzigd. Controleer het adres opnieuw.";

    @Inject SalesOrderService salesOrders;
    @Inject CustomerService customers;
    @Inject WebOrderDeliveries deliveries;
    @Inject WebOrders webOrders;
    @Inject EntityManager entities;

    /** The notice of one document, read live; null when its customer can be invoiced. */
    public CustomerInvoiceData.Notice notice(SalesOrder order) {
        if (!CustomerInvoiceData.appliesTo(order) || cancelledByCustomer(order)) return null;
        return CustomerInvoiceData.notice(customers.get(order.customerId()), order,
                deliveries.forDocument(order).orElse(null));
    }

    /**
     * Fills the empty address fields and answers the document. A record that
     * is complete by now - a second click, or the same action on another
     * document of the customer - is left alone and is no error.
     */
    @Transactional
    public SalesOrder take(long orderId, String expectedAddress, String expectedPostalCode, String expectedCity) {
        salesOrders.lockDocumentForCustomer(orderId);
        SalesOrder order = salesOrders.get(orderId);
        if (order.customerId() == null) throw new BusinessRuleException("Koppel eerst een klant aan het document");
        /* Document first, customer second: nothing else locks a customer, so two takeovers queue here. */
        SalesEntities.CustomerEntity row = entities.find(SalesEntities.CustomerEntity.class, order.customerId(),
                LockModeType.PESSIMISTIC_WRITE);
        if (row != null) entities.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        Customer customer = customers.get(order.customerId());
        if (CustomerInvoiceData.missing(customer).isEmpty()) return order;
        if (!CustomerInvoiceData.appliesTo(order) || cancelledByCustomer(order)) throw new BusinessRuleException(NOT_POSSIBLE);
        CustomerInvoiceData.Offer offer = CustomerInvoiceData.offer(customer, order,
                deliveries.forDocument(order).orElse(null));
        CustomerInvoiceData.Takeover takeover = offer.takeover();
        if (takeover == null) throw new BusinessRuleException(NOT_POSSIBLE);
        if (!CustomerInvoiceData.sameText(expectedAddress, takeover.address())
                || !CustomerInvoiceData.sameText(expectedPostalCode, takeover.postalCode())
                || !CustomerInvoiceData.sameText(expectedCity, takeover.city()))
            throw new BusinessRuleException(CHANGED);
        customers.fillMissingAddress(customer.id(), takeover.address(), takeover.postalCode(), takeover.city(),
                "Adres overgenomen van het leveradres van " + order.number());
        return order;
    }

    /** A website order its customer cancelled leads to no invoice. */
    private boolean cancelledByCustomer(SalesOrder order) {
        return order.id() != null && webOrders.find(order.id()).map(WebOrders.Row::customerCancelledAt).orElse(null) != null;
    }
}
