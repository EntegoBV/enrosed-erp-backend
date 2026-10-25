package be.enrosed.sourcing.domain;

import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.StockBatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * The batch cost of one costing line: the one rule that booking stock, the shipment refresh, "Kostprijzen toepassen"
 * and the batch backfill share.
 */
public final class BatchCosts {

    private static final int SCALE = 4;

    private BatchCosts() {}

    /**
     * The cost per selling unit as the product card takes it ({@code landedUnitEur}, 4 decimals), the same figure
     * without the Enrosed kost (the container quote's formula) and the parts per unit as "Kostprijzen toepassen"
     * stores them. No line or no positive landed unit cost: {@link BatchCost#NONE}.
     */
    public static BatchCost of(LandedCost.Line line, StockBatch.CostState state, Instant appliedAt, String appliedBy) {
        if (line == null || line.landedUnitEur() == null || line.landedUnitEur().signum() <= 0) return BatchCost.NONE;
        BigDecimal unit = line.landedUnitEur().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal excl = unit;
        if (line.quantity() > 0 && nz(line.extraRevenueEur()).signum() != 0) {
            excl = nz(line.totalEur()).subtract(nz(line.extraRevenueEur()))
                    .divide(BigDecimal.valueOf(line.quantity()), SCALE, RoundingMode.HALF_UP);
        }
        ProductCostBreakdown parts = ProductCostBreakdown.perUnit(line.quantity(), line.goodsEur(), line.originEur(),
                line.freightEur(), line.dutyEur(), line.destinationEur(), line.extraRevenueEur(), line.separateEur());
        return new BatchCost(state, unit, excl, parts, appliedAt, appliedBy);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
