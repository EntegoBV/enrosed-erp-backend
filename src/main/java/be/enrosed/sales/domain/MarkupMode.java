package be.enrosed.sales.domain;

/** Where the markup on the cost price comes from. */
public enum MarkupMode {
    /** Each product uses its own markup from the catalogue. */
    PRODUCT,
    /** One percentage over the whole order. */
    ORDER
}
