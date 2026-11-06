package be.enrosed.catalog.domain;

/**
 * What a typed price is per: the product's sales unit and, for a display sold per display, the
 * pieces in it. A price typed per display of 12 means something else once the product is sold per
 * piece or per display of 10, and nothing converts stored amounts (docs/catalog/sales-units.md), so
 * an agreed or one-off price keeps the basis it was typed on and applies only while that still holds.
 * The unit's name (stuk, bowl) is not part of it: a bowl priced per piece stays priced per piece.
 */
public record PriceBasis(SalesUnit salesUnit, Integer piecesPerUnit) {

    public PriceBasis {
        /* Only a display's contents change what its price means; a piece price ignores them. */
        piecesPerUnit = salesUnit == SalesUnit.DISPLAY && piecesPerUnit != null && piecesPerUnit > 0
                ? piecesPerUnit : null;
    }

    /** The basis a product's prices are on now. */
    public static PriceBasis of(Packaging packaging) {
        Packaging current = packaging == null ? Packaging.none() : packaging;
        return new PriceBasis(current.salesUnit(), current.piecesPerUnit());
    }

    /** Whether a price typed on {@code unit} with {@code pieces} per display still means the same. */
    public boolean matches(SalesUnit unit, Integer pieces) {
        return unit != null && equals(new PriceBasis(unit, pieces));
    }

    /** "stuk", "display van 12" or "display": what the price is per, in the ERP's words. */
    public String dutchLabel() {
        if (salesUnit != SalesUnit.DISPLAY) return "stuk";
        return piecesPerUnit == null ? "display" : "display van " + piecesPerUnit;
    }
}
