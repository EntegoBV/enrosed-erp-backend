package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.ProductCostEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductCostHistoryDto(Long id, Instant appliedAt, BigDecimal landedUnitEur,
                                    BigDecimal previousLandedUnitEur, BigDecimal deltaEur, BigDecimal deltaPct,
                                    String source, Long purchaseOrderId, Integer quantity,
                                    BigDecimal exwPrice, String exwCurrency, String appliedBy) {
    static ProductCostHistoryDto of(ProductCostEntry entry) {
        return new ProductCostHistoryDto(entry.id(), entry.appliedAt(), entry.landedUnitEur(),
                entry.previousLandedUnitEur(), entry.deltaEur(), entry.deltaPct(), entry.source(),
                entry.purchaseOrderId(), entry.quantity(), entry.exwPrice(), entry.exwCurrency(), entry.appliedBy());
    }
}
