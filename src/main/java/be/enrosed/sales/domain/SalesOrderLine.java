package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Quote line.
 *
 * {@code unitPriceEur} and {@code manualDiscountPct} are manual overrides;
 * left empty, the pricing engine computes them itself.
 */
public record SalesOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal unitPriceEur,
        BigDecimal manualDiscountPct,

        /**
         * Hand-picked delivery week, e.g. "2026-W34". Optional: when empty,
         * the estimate from stock and transit time is used.
         */
        String deliveryWeek,

        /**
         * What one piece cost us when this line was written: the container's
         * landed cost for a quote made from it, otherwise the product's cost
         * of that day. Fixed, so an old quote's margin never drifts when a
         * later container lands cheaper or dearer.
         */
        BigDecimal unitCostEur,
        Boolean unavailable,
        Integer requestedQuantity,

        /**
         * The order step (sales units) this line was last rounded to, written by the server
         * when the document is saved; never taken from a client. A later change of the
         * product's step or carton content therefore never re-prices a sent or paid document.
         * Null on lines from before the step was remembered: they follow the product's step.
         */
        Integer unitsPerOrderStep
) {
    /** A line written before its order step was remembered; it follows the product's step. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur,
                          Boolean unavailable, Integer requestedQuantity) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur,
                unavailable, requestedQuantity, null);
    }
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, null, null);
    }
    /** A line written before the cost was remembered on it. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, null);
    }

    public boolean hasUnitCost() {
        return unitCostEur != null && unitCostEur.signum() > 0;
    }

    public SalesOrderLine withUnitCost(BigDecimal unitCost) {
        return new SalesOrderLine(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCost, unavailable, requestedQuantity, unitsPerOrderStep);
    }
    public boolean isUnavailable() { return Boolean.TRUE.equals(unavailable); }
    public SalesOrderLine withAvailability(boolean value, int activeQuantity, Integer remembered) {
        return new SalesOrderLine(id, productId, activeQuantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, value, remembered, unitsPerOrderStep);
    }
    /** The same line rounded to, and remembering, an order step; null forgets the step. */
    public SalesOrderLine withOrderStep(int roundedQuantity, Integer step) {
        return new SalesOrderLine(id, productId, roundedQuantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, unavailable, requestedQuantity, step);
    }
}
