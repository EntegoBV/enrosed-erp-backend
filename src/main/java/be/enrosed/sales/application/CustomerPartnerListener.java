package be.enrosed.sales.application;

/**
 * Lets another feature react, inside the saving transaction, when a customer stops being a
 * partner (the Partnercontainers tick goes off): the partner portal signs that login out.
 */
public interface CustomerPartnerListener {
    void partnerEnded(long customerId);
}
