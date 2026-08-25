package be.enrosed.sales.domain;

/**
 * Life cycle of a quote.
 *
 * CONCEPT -> VERZONDEN -> BEKEKEN -> GEACCEPTEERD
 *                              \-> WIJZIGING_GEVRAAGD -> (we adjust) -> VERZONDEN
 *                              \-> AFGEWEZEN
 *
 * The customer can make nothing final except accepting or rejecting; a
 * change is always a proposal we still have to approve.
 */
public enum QuoteStatus {
    CONCEPT,
    VERZONDEN,
    BEKEKEN,
    WIJZIGING_GEVRAAGD,
    GEACCEPTEERD,
    AFGEWEZEN,
    VERLOPEN,
    /** Invoices only: the money arrived. */
    BETAALD;

    public boolean isOpenForCustomer() {
        return this == VERZONDEN || this == BEKEKEN || this == WIJZIGING_GEVRAAGD;
    }

    public boolean isFinal() {
        return this == GEACCEPTEERD || this == AFGEWEZEN || this == VERLOPEN || this == BETAALD;
    }

    /**
     * Can this quote go back to concept?
     *
     * A rejected or expired quote is often no endpoint but a negotiation:
     * the customer found it too expensive, we adjust the price and send
     * again. An accepted one is different - it was signed, and breaking it
     * open afterwards blurs what the signature belongs to. For that you
     * make a new quote.
     */
    public boolean canReopen() {
        return this == AFGEWEZEN || this == VERLOPEN;
    }
}
