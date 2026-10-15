package be.enrosed.catalog.domain;

/**
 * How a row of the cost history came about. Stored as plain text, so a later
 * kind needs no migration.
 */
public enum ProductCostKind {
    /** A container's cost written onto the product. */
    APPLY,
    /** The same container applied again with another cost (a corrected rate, a late invoice). */
    REAPPLY,
    /** The cost the product already had when the history started. */
    SEED,
    /** The cost a copied product took over from the product it was copied from. */
    COPY;

    /** A row from before the kind was kept: the first-start seed wrote 'system', everything else was applied. */
    public static ProductCostKind legacy(String appliedBy) {
        return "system".equals(appliedBy) ? SEED : APPLY;
    }
}
