package be.enrosed.catalog.domain;

import be.enrosed.shared.UnitNames;

/**
 * The gift box or display a product is sold in, with its own outer size.
 *
 * Three sizes now live side by side and must not blur: the product itself,
 * this presentation packaging around it, and the shipping carton around
 * several of those.
 */
public record Packaging(PackagingKind kind, Dimensions dimensions, String barcode,
                        /** Pieces a display holds; null or 1 for a gift box around one piece. */
                        Integer piecesPerUnit,
                        /** Basis of stored sales quantities and prices; does not change their values. */
                        SalesUnit salesUnit,
                        /**
                         * What one piece is called on customer documents ({@link UnitNames} key).
                         * Independent of the kind: a bowl stays a bowl without a display too.
                         */
                        String unitKey) {

    /** Compatibility for callers written before a piece could be called something else than "stuk". */
    public Packaging(PackagingKind kind, Dimensions dimensions, String barcode, Integer piecesPerUnit,
                     SalesUnit salesUnit) {
        this(kind, dimensions, barcode, piecesPerUnit, salesUnit, null);
    }

    public Packaging(PackagingKind kind, Dimensions dimensions, String barcode, Integer piecesPerUnit) {
        this(kind, dimensions, barcode, piecesPerUnit, SalesUnit.PIECE);
    }

    public SalesUnit salesUnit() {
        return kind() == PackagingKind.DISPLAY && salesUnit == SalesUnit.DISPLAY
                ? SalesUnit.DISPLAY : SalesUnit.PIECE;
    }

    public boolean soldAsDisplay() {
        return salesUnit() == SalesUnit.DISPLAY;
    }

    /** Legacy frozen document JSON may predate the explicit commercial-unit field. */
    public boolean hasExplicitSalesUnit() {
        return salesUnit != null;
    }

    /** Always a known unit; missing or unknown (older rows, frozen JSON) reads as "stuk". */
    public String unitKey() {
        return UnitNames.normalize(unitKey);
    }

    /** The key as it was given, trimmed; null when blank. Only validation needs the raw value. */
    public String requestedUnitKey() {
        return unitKey == null || unitKey.isBlank() ? null : unitKey.strip();
    }

    /** The same packaging with another name for one piece. */
    public Packaging withUnitKey(String unit) {
        return new Packaging(kind, dimensions, barcode, piecesPerUnit, salesUnit, unit);
    }

    public static Packaging none() {
        return new Packaging(PackagingKind.NONE, Dimensions.empty(), null, null);
    }

    /** Packaging without its own code; the gift box is not always scanned separately. */
    public Packaging(PackagingKind kind, Dimensions dimensions) {
        this(kind, dimensions, null, null);
    }

    /** Compatibility for callers written before displays counted their pieces. */
    public Packaging(PackagingKind kind, Dimensions dimensions, String barcode) {
        this(kind, dimensions, barcode, null);
    }

    /** How many pieces one unit of this packaging holds; 1 unless a display says more. */
    public int unitPieces() {
        return piecesPerUnit == null || piecesPerUnit < 1 || !isPresent() ? 1 : piecesPerUnit;
    }

    /** Trimmed, null when blank; only meaningful while packaging is present. */
    public String barcode() {
        if (barcode == null || !isPresent()) return null;
        String value = barcode.trim();
        return value.isEmpty() ? null : value;
    }

    public PackagingKind kind() {
        return kind == null ? PackagingKind.NONE : kind;
    }

    public Dimensions dimensions() {
        return dimensions == null ? Dimensions.empty() : dimensions;
    }

    public boolean isPresent() {
        return kind() != PackagingKind.NONE;
    }

    /** "Geschenkverpakking B × D × H: 20 × 12 × 30 cm", or empty without packaging. */
    public String label() {
        if (!isPresent()) return "";
        String size = dimensions().label();
        return kind().dutchLabel() + (size.isEmpty() ? "" : " " + size);
    }
}
