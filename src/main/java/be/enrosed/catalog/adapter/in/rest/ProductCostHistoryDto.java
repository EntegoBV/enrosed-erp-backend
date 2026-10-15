package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.ProductCostBasis;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.ProductCostEntry;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of a product's cost history. Everything after {@code appliedBy}
 * was added in the cost-history round (2026-10-16) and may be null on older
 * rows: the basis (salesUnit, piecesPerUnit, unitKey), the kind (APPLY,
 * REAPPLY, SEED, COPY), the extra unit cost in the EXW currency, the parts
 * of one unit in euro, and the container's name and whether it is gone.
 */
public record ProductCostHistoryDto(Long id, Instant appliedAt, BigDecimal landedUnitEur,
                                    BigDecimal previousLandedUnitEur, BigDecimal deltaEur, BigDecimal deltaPct,
                                    String source, Long purchaseOrderId, Integer quantity,
                                    BigDecimal exwPrice, String exwCurrency, String appliedBy,
                                    String kind, String salesUnit, Integer piecesPerUnit, String unitKey,
                                    BigDecimal extraUnitCost,
                                    BigDecimal goodsEur, BigDecimal originEur, BigDecimal freightEur,
                                    BigDecimal dutyEur, BigDecimal destinationEur, BigDecimal enrosedEur,
                                    BigDecimal separateEur,
                                    String containerName, boolean containerGone) {
    static ProductCostHistoryDto of(ProductCostEntry entry) {
        ProductCostBasis basis = entry.basis();
        ProductCostBreakdown parts = entry.breakdown();
        return new ProductCostHistoryDto(entry.id(), entry.appliedAt(), entry.landedUnitEur(),
                entry.previousLandedUnitEur(), entry.deltaEur(), entry.deltaPct(), entry.source(),
                entry.purchaseOrderId(), entry.quantity(), entry.exwPrice(), entry.exwCurrency(), entry.appliedBy(),
                entry.kind(),
                basis == null ? null : basis.salesUnit(),
                basis == null ? null : basis.piecesPerUnit(),
                basis == null ? null : basis.unitKey(),
                entry.extraUnitCost(),
                parts == null ? null : parts.goodsEur(),
                parts == null ? null : parts.originEur(),
                parts == null ? null : parts.freightEur(),
                parts == null ? null : parts.dutyEur(),
                parts == null ? null : parts.destinationEur(),
                parts == null ? null : parts.enrosedEur(),
                parts == null ? null : parts.separateEur(),
                entry.containerName(), entry.containerGone());
    }
}
