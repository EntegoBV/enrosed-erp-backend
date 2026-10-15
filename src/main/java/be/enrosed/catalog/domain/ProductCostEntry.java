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
        String appliedBy,
        /** {@link ProductCostKind} name. */
        String kind,
        /** The basis the cost was applied on; null on rows written before the basis was kept. */
        ProductCostBasis basis,
        /** The extra unit cost on top of the factory price, in the EXW currency. */
        BigDecimal extraUnitCost,
        /** The parts of one unit of the cost; null when they were never kept. */
        ProductCostBreakdown breakdown,
        /** The container's name (Herkenbare naam, else its number); null without a container. */
        String containerName,
        /** True when the row names a container that is in the trash or gone. */
        boolean containerGone
) {
    /** Compatibility for callers written before the basis, the kind and the parts were kept. */
    public ProductCostEntry(Long id, Long productId, Instant appliedAt, BigDecimal landedUnitEur,
                            BigDecimal previousLandedUnitEur, String source, Long purchaseOrderId,
                            Integer quantity, BigDecimal exwPrice, String exwCurrency, String appliedBy) {
        this(id, productId, appliedAt, landedUnitEur, previousLandedUnitEur, source, purchaseOrderId, quantity,
                exwPrice, exwCurrency, appliedBy, ProductCostKind.legacy(appliedBy).name(), null, null, null,
                null, false);
    }

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

    /** The same entry with the name and state of its container as they are today. */
    public ProductCostEntry withContainer(String name, boolean gone) {
        return new ProductCostEntry(id, productId, appliedAt, landedUnitEur, previousLandedUnitEur, source,
                purchaseOrderId, quantity, exwPrice, exwCurrency, appliedBy, kind, basis, extraUnitCost, breakdown,
                name, gone);
    }
}
