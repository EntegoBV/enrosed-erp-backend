package be.enrosed.catalog.domain;

/**
 * Product category from a fixed list - "Preserved", "Glas", "Acryl".
 * A free text field turned into a collection of typos too quickly.
 */
public record Category(Long id, String code, String name, String description, int position) {
}
