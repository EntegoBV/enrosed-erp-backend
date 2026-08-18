package be.enrosed.sales.domain;

/**
 * Where a quote's delivery terms stand in the back-and-forth with the
 * customer.
 *
 * An article without stock leaves with "delivery term to be determined".
 * The quote then has to come back to us: we fill in the delivery week and
 * send it again. On that second sending the customer must see right away
 * that this is what changed - hence remembering it instead of deriving it
 * from the lines afterwards.
 */
public enum DeliveryTermsState {

    /** Everything could be promised right away; nothing special to report. */
    VOLLEDIG,

    /** A quote left with at least one line without a term. */
    TE_BEPALEN,

    /** Those terms have since been filled in and the quote left again. */
    AANGEVULD
}
