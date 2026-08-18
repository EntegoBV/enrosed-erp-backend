package be.enrosed.sales.domain;

public enum RevisionStatus {
    /** With us for review. */
    IN_AFWACHTING,
    /** Adopted onto the order. */
    GOEDGEKEURD,
    /** Niet overgenomen. */
    AFGEWEZEN,
    /**
     * Withdrawn by the customer before we got to it.
     *
     * Deliberately not deleted: that a proposal lay there for a while
     * belongs to the quote's story, even after it was taken away again.
     */
    INGETROKKEN
}
