package be.enrosed.sales.application;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The price tables carry no foreign keys: a deleted customer takes its klantprijzen along, and
 * the history keeps a CUSTOMER_DELETED row for each.
 */
@ApplicationScoped
public class CustomerPriceDeletionListener implements CustomerDeletionListener {

    private final CustomerPriceService prices;

    public CustomerPriceDeletionListener(CustomerPriceService prices) {
        this.prices = prices;
    }

    @Override
    public void beforeDelete(long customerId) {
        prices.removeForDeletedCustomer(customerId);
    }
}
