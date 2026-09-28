package be.enrosed.catalog.domain;

import be.enrosed.shared.Language;

/**
 * A product's text in one language.
 *
 * Name, description and colour live here. The rest of a product - dimensions,
 * the Maat (variant size), barcodes, HS code, carton content - is universal:
 * translating that data gains nothing and doubles the chance of contradictions.
 * The Maat used to be translatable too; its per-language copies went stale when
 * the base changed (4.5*4.5cm on the website while the product said 4.8*4.8cm),
 * so since 2026-09-28 every document prints the one {@link Product#variantSize()}.
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
