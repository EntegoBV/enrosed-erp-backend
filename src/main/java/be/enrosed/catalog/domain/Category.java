package be.enrosed.catalog.domain;

/**
 * Product category from a fixed list - "Preserved", "Glas", "Acryl".
 * A free text field turned into a collection of typos too quickly.
 */
public record Category(
        Long id,
        String code,
        String name,
        String description,
        String eyebrow,
        int position,
        String mobileName,
        Long featuredProductId
) {
    /** Compatibility constructor for existing clients and seed data. */
    public Category(Long id, String code, String name, String description, int position) {
        this(id, code, name, description, null, position, null, null);
    }

    /** Compatibility constructor for clients from before category-owned eyebrow copy. */
    public Category(Long id, String code, String name, String description, int position,
                    String mobileName, Long featuredProductId) {
        this(id, code, name, description, null, position, mobileName, featuredProductId);
    }
}
