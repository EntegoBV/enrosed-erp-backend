package be.enrosed.sales.application;

import be.enrosed.shared.BusinessRuleException;

import java.util.List;

/**
 * Klantprijzen typed on a sales unit the product has since left: a screen that still showed it per
 * display of 6 while it is sold per piece now. Stored as they are, the amounts would mean six times
 * something else. Nothing is stored; the mapper answers 409 CUSTOMER_PRICE_BASIS_CHANGED with the
 * products concerned, so the screen can load them again and ask for the prices on their unit now.
 */
public class CustomerPriceBasisChangedException extends BusinessRuleException {

    public static final String CODE = "CUSTOMER_PRICE_BASIS_CHANGED";

    private final List<Long> productIds;

    public CustomerPriceBasisChangedException(String message, List<Long> productIds) {
        super(message);
        this.productIds = List.copyOf(productIds);
    }

    public List<Long> productIds() {
        return productIds;
    }
}
