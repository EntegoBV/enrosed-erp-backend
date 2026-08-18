package be.enrosed.sales.domain;

public enum TierScope {
    /** Discount per product line, on that product's piece count. */
    LINE,
    /** Extra discount on the order total, on the total piece count. */
    ORDER
}
