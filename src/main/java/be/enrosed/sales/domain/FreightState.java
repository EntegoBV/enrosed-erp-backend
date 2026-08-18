package be.enrosed.sales.domain;

/**
 * Where a quote's freight cost stands in the back-and-forth with the
 * customer.
 *
 * The same road as the delivery term: sometimes at drafting time you do not
 * know yet what transport costs - a destination outside the usual rates, an
 * order just over one pallet, or a customer arranging their own pickup. Then
 * the quote leaves with the freight as an open item, comes back to us, we
 * fill in the amount and it goes to the customer again.
 *
 * The alternative - inventing an amount and correcting later - is worse:
 * the customer counts on the total that was shown.
 */
public enum FreightState {

    /** The calculated rate applies; nothing special to report. */
    BEREKEND,

    /** The quote left with the freight as an open item. */
    TE_BEPALEN,

    /** The freight has since been filled in and the quote left again. */
    AANGEVULD
}
