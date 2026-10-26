package be.enrosed.sourcing.domain;

import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.StockBatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BatchCostsTest {

    private static LandedCost.Line line(int quantity, String landedUnit, String total, String extraRevenue) {
        return new LandedCost.Line(94L, "Dome 15x30 blauw", quantity, 10, new BigDecimal("1.2"),
                new BigDecimal("600"), new BigDecimal("500"), new BigDecimal("30"), new BigDecimal("120"),
                new BigDecimal("650"), new BigDecimal("4"), "TARIC", new BigDecimal("40"),
                new BigDecimal("90"), extraRevenue == null ? null : new BigDecimal(extraRevenue),
                total == null ? null : new BigDecimal(total), landedUnit == null ? null : new BigDecimal(landedUnit),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("20"));
    }

    @Test
    void aLineWithoutAPositiveLandedUnitCostGivesNoCost() {
        assertSame(BatchCost.NONE, BatchCosts.of(null, StockBatch.CostState.CALCULATED, null, null));
        assertSame(BatchCost.NONE, BatchCosts.of(line(100, null, "800", null), StockBatch.CostState.APPLIED, null, null));
        assertSame(BatchCost.NONE, BatchCosts.of(line(100, "0", "800", null), StockBatch.CostState.APPLIED, null, null));
        assertSame(BatchCost.NONE, BatchCosts.of(line(100, "-1", "800", null), StockBatch.CostState.APPLIED, null, null));
        assertEquals(StockBatch.CostState.NONE, BatchCost.NONE.state());
    }

    @Test
    void withoutExtraRevenueTheFigureWithoutEnrosedKostIsTheLandedUnitCost() {
        BatchCost cost = BatchCosts.of(line(100, "8.318", "831.80", null), StockBatch.CostState.CALCULATED, null, null);
        assertEquals(StockBatch.CostState.CALCULATED, cost.state());
        assertEquals(new BigDecimal("8.3180"), cost.unitCostEur(), "4 decimals, as the card");
        assertEquals(new BigDecimal("8.3180"), cost.unitCostExclEnrosedEur());
        assertNull(cost.appliedAt());
        assertNull(cost.appliedBy());

        BatchCost zero = BatchCosts.of(line(100, "8.318", "831.80", "0"), StockBatch.CostState.CALCULATED, null, null);
        assertEquals(new BigDecimal("8.3180"), zero.unitCostExclEnrosedEur(), "a zero Enrosed kost changes nothing");
    }

    @Test
    void withExtraRevenueTheFigureWithoutEnrosedKostIsTheContainerQuoteFormula() {
        Instant now = Instant.parse("2026-10-25T10:00:00Z");
        BatchCost cost = BatchCosts.of(line(300, "8.3180", "2495.40", "125.04"), StockBatch.CostState.APPLIED,
                now, "emre");
        assertEquals(new BigDecimal("8.3180"), cost.unitCostEur());
        /* (2495.40 - 125.04) / 300 = 7.90120 */
        assertEquals(new BigDecimal("7.9012"), cost.unitCostExclEnrosedEur());
        assertEquals(StockBatch.CostState.APPLIED, cost.state());
        assertEquals(now, cost.appliedAt());
        assertEquals("emre", cost.appliedBy());
    }

    @Test
    void thePartsAreTheLineDividedByItsQuantityAsApplyingStoresThem() {
        BatchCost cost = BatchCosts.of(line(300, "8.3180", "2495.40", "125.04"), StockBatch.CostState.APPLIED,
                null, null);
        ProductCostBreakdown parts = cost.parts();
        assertEquals(ProductCostBreakdown.perUnit(300, new BigDecimal("500"), new BigDecimal("30"),
                new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("90"), new BigDecimal("125.04"),
                new BigDecimal("20")), parts);
        assertEquals(new BigDecimal("1.6667"), parts.goodsEur());
        assertEquals(new BigDecimal("0.1000"), parts.originEur());
        assertEquals(new BigDecimal("0.4000"), parts.freightEur());
        assertEquals(new BigDecimal("0.1333"), parts.dutyEur());
        assertEquals(new BigDecimal("0.3000"), parts.destinationEur());
        assertEquals(new BigDecimal("0.4168"), parts.enrosedEur(), "the Enrosed kost is the line's extra revenue");
        assertEquals(new BigDecimal("0.0667"), parts.separateEur());

        BatchCost nothingCarried = BatchCosts.of(line(0, "8.3180", "0", "125.04"), StockBatch.CostState.APPLIED,
                null, null);
        assertNull(nothingCarried.parts(), "no quantity, no parts");
        assertEquals(new BigDecimal("8.3180"), nothingCarried.unitCostExclEnrosedEur());
    }
}
