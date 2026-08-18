package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/**
 * Article in the catalogue.
 *
 * Mind the distinction between three things that easily blur together:
 *  - {@code dimensions} is the product itself (15 x 30 cm)
 *  - {@code colour} is the colour ("Rood", "Roze"), the first of what may
 *    later become a set of product options
 *  - {@code carton} is the outer box it ships in
 */
public record Product(
        Long id,
        String sku,
        String name,
        Dimensions dimensions,
        /**
         * Colour of the article.
         *
         * First of what may later become a set of product options (size,
         * finish). That is why the value stands apart instead of being baked
         * into the product name: when a second option arrives, existing data
         * does not have to be picked apart.
         */
        String colour,
        /**
         * Sales copy for the quote and the catalogue.
         *
         * Optional; without a description everything falls back to name and
         * dimensions.
         */
        String description,
        Long categoryId,
        Long supplierId,
        boolean active,

        Barcodes barcodes,
        String hsCode,

        Carton carton,

        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,

        BigDecimal landedCostEur,
        String landedCostSource,

        BigDecimal markupPct,
        BigDecimal fixedSalesPriceEur,

        /** Pieces in stock; grows when a purchase order is received. */
        int stockQuantity,

        List<Photo> photos,

        /**
         * Name, description and colour in other languages.
         *
         * The fields above remain the base: whatever is untranslated falls
         * back to them. That keeps a product usable while its translation is
         * still missing, instead of landing as an empty box on a quote.
         */
        List<ProductText> texts
) {

    public List<Photo> photos() {
        return photos == null ? List.of() : photos;
    }

    public List<ProductText> texts() {
        return texts == null ? List.of() : texts;
    }

    /** The text in this language, or null when there is none. */
    public ProductText textIn(Language language) {
        return texts().stream()
                .filter(text -> text.language() == language)
                .findFirst()
                .orElse(null);
    }

    /** Name in this language, falling back to the base name. */
    public String nameIn(Language language) {
        ProductText text = textIn(language);
        return text == null || isBlank(text.name()) ? name : text.name();
    }

    /**
     * Colour in the given language.
     *
     * A product-specific translation wins; otherwise standard colours
     * translate themselves through the shared dictionary, and anything
     * unknown stays as typed.
     */
    public String colourIn(Language language) {
        ProductText text = textIn(language);
        if (text != null && !isBlank(text.colour())) return text.colour();
        return be.enrosed.shared.ColourNames.translate(colour, language);
    }

    /** Description in this language, falling back to the base description. */
    public String descriptionIn(Language language) {
        ProductText text = textIn(language);
        return text == null || isBlank(text.description()) ? description : text.description();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Primary photo: the first in the series. */
    public Photo primaryPhoto() {
        return photos().isEmpty() ? null : photos().get(0);
    }

    /** Full description for a quote, in our own language. */
    public String describe() {
        return describeIn(Language.NL);
    }

    /**
     * Full description in the customer's language.
     *
     * The dimensions stay numeric; they are identical in every language and
     * do not belong in a translation file.
     */
    public String describeIn(Language language) {
        String naam = nameIn(language);
        StringBuilder text = new StringBuilder(naam == null ? "" : naam);
        String size = dimensions == null ? "" : dimensions.label();
        if (!size.isBlank()) text.append(" - ").append(size);
        String kleur = colourIn(language);
        if (kleur != null && !kleur.isBlank()) text.append(" - ").append(kleur);
        return text.toString();
    }

    /* Copy-methods: one aspect changes, everything else is carried over.
       Services used to re-list all twenty fields for every small change;
       one forgotten field in one of those lists silently wiped data. */

    public Product withSku(String newSku) {
        return new Product(id, newSku, name, dimensions, colour, description, categoryId,
                supplierId, active, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts);
    }

    /** Photos in gallery order; sorting lives here so no caller can forget it. */
    public Product withPhotos(List<Photo> newPhotos) {
        List<Photo> ordered = newPhotos == null ? List.of() : newPhotos.stream()
                .sorted(java.util.Comparator.comparingInt(Photo::position)).toList();
        return new Product(id, sku, name, dimensions, colour, description, categoryId,
                supplierId, active, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, ordered, texts);
    }

    public Product withStockQuantity(int newStock) {
        return new Product(id, sku, name, dimensions, colour, description, categoryId,
                supplierId, active, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                newStock, photos, texts);
    }

    public Product withLandedCost(BigDecimal newLandedCostEur, String source) {
        return new Product(id, sku, name, dimensions, colour, description, categoryId,
                supplierId, active, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, newLandedCostEur, source, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts);
    }

    public Product withTexts(List<ProductText> newTexts) {
        return new Product(id, sku, name, dimensions, colour, description, categoryId,
                supplierId, active, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, newTexts);
    }
}
