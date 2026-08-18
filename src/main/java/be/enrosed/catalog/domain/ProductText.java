package be.enrosed.catalog.domain;

import be.enrosed.shared.Language;

/**
 * A product's text in one language.
 *
 * Only name, description and colour live here. The rest of a product -
 * dimensions, barcodes, HS code, carton content - is universal: translating
 * that data gains nothing and doubles the chance of contradictions.
 *
 * An empty field means "not translated yet" and falls back to the product
 * itself. Deliberate: better the base name on a French quote than an empty
 * box.
 */
public record ProductText(
        Language language,
        String name,
        String description,
        String colour
) {

    /** Anything filled in? A row of only empty fields needs no saving. */
    public boolean isEmpty() {
        return blank(name) && blank(description) && blank(colour);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
