package be.enrosed.media;

/** Business records to which a reusable asset can be linked. */
public enum MediaTargetType {
    PRODUCT,
    PRODUCT_FAMILY,
    PURCHASE_ORDER,
    PLANNER_ITEM,
    /** A booked company cost: the supplier's invoice, the receipt, the contract. */
    COMPANY_COST
}
