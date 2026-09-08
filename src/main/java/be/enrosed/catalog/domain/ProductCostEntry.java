package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** One landed cost a product carried, from which container and since when. */
public record ProductCostEntry(
        Long id,
        Long productId,
        Instant appliedAt,
        BigDecimal landedUnitEur,
        BigDecimal previousLandedUnitEur,
        String source,
        Long purchaseOrderId,
        Integer quantity,
        BigDecimal exwPrice,
        String exwCurrency,
        String appliedBy
) {
    /** How much dearer (positive) or cheaper this container was than the cost before it. */
    public BigDecimal deltaEur() {
        if (previousLandedUnitEur == null || landedUnitEur == null) return null;
        return landedUnitEur.subtract(previousLandedUnitEur).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal deltaPct() {
        BigDecimal delta = deltaEur();
        if (delta == null || previousLandedUnitEur.signum() <= 0) return null;
        return delta.multiply(new BigDecimal("100")).divide(previousLandedUnitEur, 1, RoundingMode.HALF_UP);
    }
}
