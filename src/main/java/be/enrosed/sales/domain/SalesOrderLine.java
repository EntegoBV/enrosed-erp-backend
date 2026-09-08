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
        BigDecimal unitCostEur
) {
    /** A line written before the cost was remembered on it. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, null);
    }

    public boolean hasUnitCost() {
        return unitCostEur != null && unitCostEur.signum() > 0;
    }

    public SalesOrderLine withUnitCost(BigDecimal unitCost) {
        return new SalesOrderLine(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCost);
    }
}
