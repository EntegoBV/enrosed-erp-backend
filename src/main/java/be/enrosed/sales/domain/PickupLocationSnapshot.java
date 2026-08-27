package be.enrosed.sales.domain;

/**
 * Immutable customer-visible collection facts captured when a public request
 * is submitted. The stock location may later be renamed, moved or disabled;
 * the ERP request must keep what the buyer actually selected at that time.
 */
public record PickupLocationSnapshot(
        Long locationId,
        String label,
        String address,
        String instructions
) {}
