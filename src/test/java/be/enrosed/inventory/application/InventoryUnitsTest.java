package be.enrosed.inventory.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryUnitsTest {
    @Test
    void oneStemOutOfEightSetsKeepsTheOtherNinetyFiveStemsAndUsesTheLedgerFactor() {
        StockMovement source = new StockMovement(1L, 2L, 3L, Instant.now(), -1, 7, StockMovement.Kind.SALE,
                "TICA", "systeem", null, 9L, 11L, null, 11, 11, 12, BigDecimal.ONE, "PIECE");
        StockMovement physical = InventoryUnits.movement(source, 24);
        assertEquals(-1, physical.delta());
        assertEquals(95, physical.quantityAfter());
        assertEquals(source.at(), physical.at());
        assertEquals(source.ticaRevisionId(), physical.ticaRevisionId());
        assertEquals(-1, source.delta());
        assertEquals(11, source.looseDelta());
    }

    @Test
    void legacyCountsUseTheirFrozenFactorAndNewCountsAreNeverMultipliedTwice() {
        StockCountLineEntity line = new StockCountLineEntity();
        line.salesUnit = "DISPLAY";
        line.piecesPerUnit = 12;
        assertEquals(96, InventoryUnits.quantity(8, InventoryUnits.countFactor(line)));
        line.quantityBasis = InventoryUnits.BASE_PIECE;
        line.salesUnit = "PIECE";
        assertEquals(95, InventoryUnits.quantity(95, InventoryUnits.countFactor(line)));
        assertEquals(1, InventoryUnits.factor("PIECE", 12));
    }

    @Test
    void aCarriedLayerUsesTwelveDecimalsWithoutMutatingTheFrozenOriginal() {
        StockClosingLayerEntity original = new StockClosingLayerEntity();
        original.quantity = 8;
        original.capacity = 8;
        original.unitValueEur = new BigDecimal("44.95");
        StockClosingLayerEntity converted = InventoryUnits.layer(original, 12);
        assertEquals(96, converted.quantity);
        assertEquals(new BigDecimal("355.85"), converted.unitValueEur.multiply(BigDecimal.valueOf(95))
                .setScale(2, RoundingMode.HALF_UP));
        assertEquals(8, original.quantity);
        assertEquals(new BigDecimal("44.95"), original.unitValueEur);
        assertThrows(ArithmeticException.class, () -> InventoryUnits.physical(Integer.MAX_VALUE, 0, 12));
    }

    @Test
    void correctingAnOldClosingDoesNotReportAnArtificialQuantityChangeFromTheNewBasis() {
        StockClosingEntity old = new StockClosingEntity();
        old.id = 1L; old.versionNo = 1;
        StockClosingArticleEntity oldArticle = new StockClosingArticleEntity();
        oldArticle.productId = 7L; oldArticle.salesUnit = "DISPLAY"; oldArticle.piecesPerUnit = 12;
        oldArticle.closingQuantity = 8; oldArticle.costValueEur = new BigDecimal("359.60");
        StockClosingEntity now = new StockClosingEntity();
        now.id = 2L; now.versionNo = 2; now.quantityBasis = InventoryUnits.BASE_PIECE;
        StockClosingArticleEntity nowArticle = InventoryUnits.copy(oldArticle);
        nowArticle.salesUnit = "PIECE"; nowArticle.closingQuantity = 96;
        ClosingVersionDiff.Changes diff = ClosingVersionDiff.between(
                new ClosingVersionDiff.Rows(now, List.of(nowArticle), List.of(), List.of(), List.of(), List.of()),
                new ClosingVersionDiff.Rows(old, List.of(oldArticle), List.of(), List.of(), List.of(), List.of()));
        assertTrue(diff.isEmpty());
        assertEquals(8, oldArticle.closingQuantity);
        assertNull(old.quantityBasis);
    }
}
