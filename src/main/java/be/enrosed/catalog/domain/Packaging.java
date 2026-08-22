package be.enrosed.catalog.domain;

/**
 * The gift box or display a product is sold in, with its own outer size.
 *
 * Three sizes now live side by side and must not blur: the product itself,
 * this presentation packaging around it, and the shipping carton around
 * several of those.
 */
public record Packaging(PackagingKind kind, Dimensions dimensions, String barcode) {

    public static Packaging none() {
        return new Packaging(PackagingKind.NONE, Dimensions.empty(), null);
    }

    /** Packaging without its own code; the gift box is not always scanned separately. */
    public Packaging(PackagingKind kind, Dimensions dimensions) {
        this(kind, dimensions, null);
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
