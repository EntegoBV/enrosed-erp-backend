package be.enrosed.catalog.domain;

/**
 * The basis a landed cost was applied on: per display of n pieces or per
 * piece, and what one piece is called.
 *
 * Kept with every row of the cost history, because a product's basis can
 * change later without its cost being converted: a cost applied per stuk
 * must keep reading per stuk after the product went over to per display.
 * The values are the effective ones, as {@link Packaging#salesUnit()} reads
 * them, so a stray DISPLAY on a gift box never shows as "per display".
 */
public record ProductCostBasis(
        /** {@link SalesUnit} name: DISPLAY only for a display sold per display. */
        String salesUnit,
        /** Pieces in one display; null unless the packaging is a display of more than one piece. */
        Integer piecesPerUnit,
        /** What one piece is called ({@code UnitNames} key), never blank. */
        String unitKey
) {
    public static ProductCostBasis of(Packaging packaging) {
        Packaging read = packaging == null ? Packaging.none() : packaging;
        Integer pieces = read.kind() == PackagingKind.DISPLAY
                && read.piecesPerUnit() != null && read.piecesPerUnit() > 1 ? read.piecesPerUnit() : null;
        return new ProductCostBasis(read.salesUnit().name(), pieces, read.unitKey());
    }
}
