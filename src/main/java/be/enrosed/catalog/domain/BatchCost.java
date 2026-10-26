package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** The cost a batch gets, per selling unit; any part may be null. */
public record BatchCost(StockBatch.CostState state, BigDecimal unitCostEur, BigDecimal unitCostExclEnrosedEur,
        ProductCostBreakdown parts, Instant appliedAt, String appliedBy) {

    public static final BatchCost NONE = new BatchCost(StockBatch.CostState.NONE, null, null, null, null, null);
}
