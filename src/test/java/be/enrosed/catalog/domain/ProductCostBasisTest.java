package be.enrosed.catalog.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCostBasisTest {

    @Test
    void theBasisIsTheEffectiveOneTheSalesSideReads() {
        assertEquals(new ProductCostBasis("DISPLAY", 12, "diamant"), ProductCostBasis.of(
                new Packaging(PackagingKind.DISPLAY, null, null, 12, SalesUnit.DISPLAY, " Diamant ", null)));
        assertEquals(new ProductCostBasis("PIECE", 12, "stuk"), ProductCostBasis.of(
                new Packaging(PackagingKind.DISPLAY, null, null, 12, SalesUnit.PIECE)),
                "a display sold per piece still says how many it holds");
        assertEquals(new ProductCostBasis("PIECE", null, "stuk"), ProductCostBasis.of(
                new Packaging(PackagingKind.GIFT_BOX, null, null, 6, SalesUnit.DISPLAY)),
                "a stray DISPLAY on a gift box is no display");
        assertEquals(new ProductCostBasis("DISPLAY", null, "roos"), ProductCostBasis.of(
                new Packaging(PackagingKind.DISPLAY, null, null, 1, SalesUnit.DISPLAY, "roos", null)),
                "a display of one piece has no count to show");
        assertEquals(new ProductCostBasis("PIECE", null, "stuk"), ProductCostBasis.of(null));
        assertEquals(new ProductCostBasis("PIECE", null, "stuk"), ProductCostBasis.of(
                new Packaging(PackagingKind.NONE, null, null, null, null, "onbekend", null)),
                "an unknown unit reads as stuk");
    }

    @Test
    void thePartsOfOneUnitAreTheLineDividedByItsQuantity() {
        ProductCostBreakdown parts = ProductCostBreakdown.perUnit(120, new BigDecimal("155.52"),
                new BigDecimal("0.00"), new BigDecimal("37.04"), null, new BigDecimal("12.10"),
                new BigDecimal("120.00"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("1.2960"), parts.goodsEur());
        assertEquals(new BigDecimal("0.0000"), parts.originEur());
        assertEquals(new BigDecimal("0.3087"), parts.freightEur(), "37.04 / 120 = 0.30866…, half up");
        assertNull(parts.dutyEur(), "a part the line does not know stays unknown");
        assertEquals(new BigDecimal("0.1008"), parts.destinationEur());
        assertEquals(new BigDecimal("1.0000"), parts.enrosedEur());
        assertEquals(new BigDecimal("0.0000"), parts.separateEur());
        assertFalse(parts.isEmpty());

        assertNull(ProductCostBreakdown.perUnit(0, BigDecimal.TEN, null, null, null, null, null, null),
                "a line that received nothing has no unit");
        assertTrue(new ProductCostBreakdown(null, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void aRowFromBeforeTheKindReadsAsSeedOrApply() {
        assertEquals(ProductCostKind.SEED, ProductCostKind.legacy("system"));
        assertEquals(ProductCostKind.APPLY, ProductCostKind.legacy("systeem"), "a real system apply");
        assertEquals(ProductCostKind.APPLY, ProductCostKind.legacy("emre"));
        assertEquals(ProductCostKind.APPLY, ProductCostKind.legacy(null));
    }
}
