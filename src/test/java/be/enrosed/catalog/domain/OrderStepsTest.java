package be.enrosed.catalog.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderStepsTest {

    private static final Carton TWELVE = new Carton(new Dimensions(new BigDecimal("40"), new BigDecimal("30"),
            new BigDecimal("20")), 12, new BigDecimal("5"));
    private static final Carton FORTY = new Carton(new Dimensions(new BigDecimal("60"), new BigDecimal("40"),
            new BigDecimal("40")), 40, new BigDecimal("9"));

    @Test
    void aCartonStepIsTheCartonContentAndNothingSetIsACarton() {
        assertEquals(12, OrderSteps.unitsPerStep(Packaging.none(), TWELVE));
        assertEquals(12, OrderSteps.unitsPerStep(Packaging.none().withOrderStep(OrderStep.CARTON), TWELVE));
        assertEquals(OrderStep.CARTON, Packaging.none().orderStep(), "null reads as the full carton");
        assertEquals(1, OrderSteps.unitsPerStep(Packaging.none(), null));
        assertEquals(1, OrderSteps.unitsPerStep(Packaging.none(),
                new Carton(Dimensions.empty(), 0, BigDecimal.ZERO)), "never a step below one");
        assertEquals(1, OrderSteps.unitsPerStep((Product) null));
    }

    @Test
    void aPackageStepIsADisplayOfPiecesAndOnePieceOtherwise() {
        Packaging bowls = display(8, SalesUnit.PIECE).withOrderStep(OrderStep.PACKAGE);
        assertEquals(8, OrderSteps.unitsPerStep(bowls, FORTY), "a display of 8 bowls sold per display of pieces");

        Packaging giftBox = new Packaging(PackagingKind.GIFT_BOX, Dimensions.empty(), null, null,
                SalesUnit.PIECE, "stolp", OrderStep.PACKAGE);
        assertEquals(1, OrderSteps.unitsPerStep(giftBox, TWELVE), "a gift box holds one piece");

        Packaging displayBasis = display(8, SalesUnit.DISPLAY).withOrderStep(OrderStep.PACKAGE);
        assertEquals(1, OrderSteps.unitsPerStep(displayBasis, FORTY), "a display basis already counts displays");

        assertEquals(1, OrderSteps.unitsPerStep(display(1, SalesUnit.PIECE).withOrderStep(OrderStep.PACKAGE),
                FORTY), "a display of one piece is one piece");
        assertEquals(1, OrderSteps.unitsPerStep(Packaging.none().withOrderStep(OrderStep.PACKAGE), FORTY));
    }

    @Test
    void aUnitStepIsOneWhateverTheCarton() {
        assertEquals(1, OrderSteps.unitsPerStep(Packaging.none().withOrderStep(OrderStep.UNIT), TWELVE));
        assertEquals(1, OrderSteps.unitsPerStep(display(8, SalesUnit.PIECE).withOrderStep(OrderStep.UNIT), FORTY));
    }

    @Test
    void roundingGoesUpToTheStepAndLeavesZeroAndNegativesAlone() {
        assertEquals(504, OrderSteps.roundUp(500, 6));
        assertEquals(12, OrderSteps.roundUp(3, 12));
        assertEquals(16, OrderSteps.roundUp(10, 8));
        assertEquals(24, OrderSteps.roundUp(24, 12));
        assertEquals(3, OrderSteps.roundUp(3, 1));
        assertEquals(0, OrderSteps.roundUp(0, 12), "zero stays zero");
        assertEquals(-4, OrderSteps.roundUp(-4, 12), "a negative is refused elsewhere, not rounded here");
        assertEquals(1_000_008L, OrderSteps.roundUp(1_000_001L, 8));
    }

    @Test
    void unknownCartonContentIsNeverAOnePieceCarton() {
        assertEquals(0, OrderSteps.knownPiecesPerCarton(null));
        assertEquals(0, OrderSteps.knownPiecesPerCarton(Carton.empty()),
                "the persistence default is no evidence of a one-piece box");
        assertEquals(1, OrderSteps.knownPiecesPerCarton(new Carton(Dimensions.empty(), 1, new BigDecimal("2"))),
                "a weighed carton of one is a real carton of one");
        assertEquals(12, OrderSteps.knownPiecesPerCarton(TWELVE));

        assertNull(OrderSteps.publicUnitsPerStep(Packaging.none(), Carton.empty()),
                "a carton step without content is not published");
        assertEquals(1, OrderSteps.publicUnitsPerStep(Packaging.none().withOrderStep(OrderStep.UNIT), Carton.empty()),
                "per stuk needs no carton content");
        assertEquals(12, OrderSteps.publicUnitsPerStep(Packaging.none(), TWELVE));
    }

    @Test
    void storedNamesReadAsTheStepAndAnythingElseAsTheCarton() {
        assertEquals(OrderStep.UNIT, OrderStep.parse(" unit "));
        assertNull(OrderStep.parse(null));
        assertNull(OrderStep.parse(""));
        assertNull(OrderStep.parse("BOX"));
        assertEquals(OrderStep.CARTON, OrderStep.effective("BOX"));
        assertEquals(OrderStep.PACKAGE, OrderStep.effective("PACKAGE"));
    }

    @Test
    void theStepTravelsWithTheUnitAndSurvivesNoPackaging() {
        Packaging unit = Packaging.none().withOrderStep(OrderStep.UNIT).withUnitKey("stolp");
        assertEquals(OrderStep.UNIT, unit.orderStep());
        assertEquals("stolp", unit.unitKey());
        assertEquals(OrderStep.UNIT, unit.withUnitKey("hart").orderStep());
        assertNull(Packaging.none().requestedOrderStep());
    }

    private static Packaging display(int pieces, SalesUnit basis) {
        return new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, pieces, basis, "bowl", null);
    }
}
