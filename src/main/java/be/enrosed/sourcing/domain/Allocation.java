package be.enrosed.sourcing.domain;

/**
 * Allocation key for costs only known at container level.
 * For a single-product container the choice makes no difference.
 */
public enum Allocation {
    /** By volume - volume is what fills the container. */
    CBM,
    /** Naar goederenwaarde. */
    VALUE,
    /** By piece count. */
    PIECES,
    /** By hand, per product line: only the Enrosed kost takes this key; the other costs fall back to pieces. */
    MANUAL,
    /** Not in any piece price: booked apart, under the landed total. Only the inspection and other named costs take this key. */
    SEPARATE
}
