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
        String deliveryWeek
) {}
