package be.enrosed.sales.application;

/**
 * Lets another feature remove what it keeps for a customer, inside the transaction that
 * deletes the customer and after the business checks have passed.
 */
public interface CustomerDeletionListener {
    void beforeDelete(long customerId);
}
