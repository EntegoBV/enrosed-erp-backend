package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;

/**
 * Line on a purchase order. Price fields may be empty; the product's own
 * price then applies.
 */
public record PurchaseOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,
        /**
         * The quantity at the moment the order was placed with the supplier.
         *
         * Snapshotted when the status leaves concept. Containers regularly
         * arrive with less than was ordered; from then on the line can say
         * "ordered 96, received 90" instead of silently forgetting what was
         * agreed. Null for lines added after ordering.
         */
        Integer orderedQuantity,
        /** What the agreed price covers; null means EXW, as every line was before. */
        PriceBasis priceBasis,
        /** Pieces that arrived broken; they are in {@code quantity} but never in stock. */
        Integer damagedQuantity
) {
    /** Compatibility for callers written before DDP prices existed. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost, orderedQuantity, null, null);
    }

    /** Compatibility for callers written before damage was counted. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity,
                             PriceBasis priceBasis) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost, orderedQuantity, priceBasis, null);
    }

    public int damaged() {
        return damagedQuantity == null || damagedQuantity < 0 ? 0 : damagedQuantity;
    }

    /** What goes on the shelf: arrived minus broken. */
    public int usable() {
        return Math.max(0, quantity - damaged());
    }

    public PriceBasis priceBasis() {
        return priceBasis == null ? PriceBasis.EXW : priceBasis;
    }

    public boolean deliveredDutyPaid() {
        return priceBasis() == PriceBasis.DDP;
    }
}
