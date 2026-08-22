package be.enrosed.catalog.domain;

/**
 * The gift box or display a product is sold in, with its own outer size.
 *
 * Three sizes now live side by side and must not blur: the product itself,
 * this presentation packaging around it, and the shipping carton around
 * several of those.
 */
public record Packaging(PackagingKind kind, Dimensions dimensions) {

    public static Packaging none() {
        return new Packaging(PackagingKind.NONE, Dimensions.empty());
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
