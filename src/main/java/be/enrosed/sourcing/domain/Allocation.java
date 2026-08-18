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
    PIECES
}
