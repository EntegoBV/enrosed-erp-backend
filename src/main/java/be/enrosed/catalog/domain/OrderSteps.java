package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * The one rule for the multiple a sales quantity rounds up to, in sales units.
 * The ERP and the website mirror it; every server path that rounds a sale
 * (pricing, saving, the portal, the website, delivery splits) asks here.
 *
 * <ul>
 *   <li>CARTON: the pieces (or displays) of one outer carton, at least 1;</li>
 *   <li>PACKAGE: the pieces of one display when the product is a display sold per piece
 *       with at least two pieces in it; otherwise 1 (a gift box holds one piece, and a
 *       display basis already counts displays);</li>
 *   <li>UNIT: 1.</li>
 * </ul>
 *
 * Shipping is not decided here: a part carton still ships as one full carton
 * ({@link Carton#cartonsFor(int)}).
 */
public final class OrderSteps {

    private OrderSteps() {}

    public static int unitsPerStep(Packaging packaging, Carton carton) {
        OrderStep step = packaging == null ? OrderStep.CARTON : packaging.orderStep();
        return switch (step) {
            case CARTON -> carton == null ? 1 : Math.max(1, carton.piecesPerCarton());
            case PACKAGE -> packaging.kind() == PackagingKind.DISPLAY
                    && packaging.salesUnit() == SalesUnit.PIECE
                    && packaging.piecesPerUnit() != null && packaging.piecesPerUnit() >= 2
                    ? packaging.piecesPerUnit() : 1;
            case UNIT -> 1;
        };
    }

    public static int unitsPerStep(Product product) {
        return product == null ? 1 : unitsPerStep(product.packaging(), product.carton());
    }

    /** Zero stays zero and a negative stays what it is (refused elsewhere); anything else rounds up. */
    public static int roundUp(int quantity, int unitsPerStep) {
        long rounded = roundUp((long) quantity, unitsPerStep);
        return rounded > Integer.MAX_VALUE ? quantity : (int) rounded;
    }

    public static long roundUp(long quantity, int unitsPerStep) {
        if (quantity <= 0 || unitsPerStep <= 1) return quantity;
        return (quantity + unitsPerStep - 1) / unitsPerStep * unitsPerStep;
    }

    /**
     * The carton content as evidence says it, 0 when unknown. Old product rows defaulted to
     * {@link Carton#empty()}: one piece with no measurements, weight or capacity. That is a
     * persistence default, not evidence that the box really holds one piece, so that exact
     * shape reads as unknown and nothing turns four requested boxes into four pieces.
     */
    public static int knownPiecesPerCarton(Carton carton) {
        if (carton == null || carton.piecesPerCarton() <= 0) return 0;
        if (carton.piecesPerCarton() == 1 && hasNoCartonEvidence(carton)) return 0;
        return carton.piecesPerCarton();
    }

    /**
     * The step as public channels publish it: null for a carton step whose content is
     * unknown, never a guessed one-piece carton.
     */
    public static Integer publicUnitsPerStep(Packaging packaging, Carton carton) {
        OrderStep step = packaging == null ? OrderStep.CARTON : packaging.orderStep();
        if (step == OrderStep.CARTON && knownPiecesPerCarton(carton) <= 0) return null;
        return unitsPerStep(packaging, carton);
    }

    private static boolean hasNoCartonEvidence(Carton carton) {
        Dimensions dimensions = carton.dimensions();
        boolean noDimensions = dimensions == null
                || nonPositive(dimensions.lengthCm())
                && nonPositive(dimensions.widthCm())
                && nonPositive(dimensions.heightCm());
        return noDimensions
                && nonPositive(carton.weightKg())
                && (carton.piecesPerHc() == null || carton.piecesPerHc() <= 0);
    }

    private static boolean nonPositive(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }
}
