package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Article in the catalogue.
 *
 * Mind the distinction between three things that easily blur together:
 *  - {@code dimensions} is the product itself (shown as B × D × H)
 *  - {@code packaging} is the gift box or display it is sold in, if any
 *  - {@code colour} and {@code variantSize} are explicit variant options
 *  - {@code carton} is the outer box it ships in
 */
public record Product(
        Long id,
        String sku,
        String name,
        Dimensions dimensions,
        /** Gift box or display around the product; {@link Packaging#none()} when sold bare. */
        Packaging packaging,
        /**
         * Colour of the article.
         *
         * First of what may later become a set of product options (size,
         * finish). That is why the value stands apart instead of being baked
         * into the product name: when a second option arrives, existing data
         * does not have to be picked apart.
        */
        String colour,
        /** Optional merchandising size option; distinct from physical dimensions. */
        String variantSize,
        /** Optional editable swatch colour; exactly #RRGGBB when present. */
        String colourHex,
        /**
         * Sales copy for the quote and the catalogue.
         *
         * Optional; without a description everything falls back to name and
         * dimensions.
         */
        String description,
        Long categoryId,
        Long supplierId,
        /** Internal free-text purchasing note supplied by or about the supplier. */
        String supplierNote,
        boolean active,

        Long familyId,
        String canonicalVariantKey,
        /** Source-accurate product EAN/barcode; unlike inner/outer carton codes its level is not guessed. */
        String canonicalBarcode,
        int variantPosition,
        boolean inventoryKnown,

        /** Stable key that groups stock-bearing variants into one merchandising family. */
        String familyKey,
        /** Stable, unique URL identity shared with public catalogue consumers. */
        String publicHandle,
        PublicationState websiteStatus,
        PublicationState orderAppStatus,

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
        List<ProductText> texts,

        /**
         * A demo piece: an idea shown at fairs or kept aside, never the
         * website's to sell. Sorted to the bottom of the catalogue and
         * excluded from every public channel.
         */
        boolean demo
) {

    /** Compatibility constructor for callers written before supplier notes existed. */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, Packaging packaging,
            String colour, String variantSize, String colourHex, String description,
            Long categoryId, Long supplierId, boolean active,
            Long familyId, String canonicalVariantKey, String canonicalBarcode,
            int variantPosition, boolean inventoryKnown,
            String familyKey, String publicHandle,
            PublicationState websiteStatus, PublicationState orderAppStatus,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts, boolean demo) {
        this(id, sku, name, dimensions, packaging, colour, variantSize, colourHex, description,
                categoryId, supplierId, null, active, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey, publicHandle,
                websiteStatus, orderAppStatus, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, demo);
    }

    /** Compatibility constructor for callers written before demo pieces existed. */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, Packaging packaging,
            String colour, String variantSize, String colourHex, String description,
            Long categoryId, Long supplierId, boolean active,
            Long familyId, String canonicalVariantKey, String canonicalBarcode,
            int variantPosition, boolean inventoryKnown,
            String familyKey, String publicHandle,
            PublicationState websiteStatus, PublicationState orderAppStatus,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts) {
        this(id, sku, name, dimensions, packaging, colour, variantSize, colourHex, description,
                categoryId, supplierId, null, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown, familyKey, publicHandle, websiteStatus,
                orderAppStatus, barcodes, hsCode, carton, exwPrice, exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur, stockQuantity,
                photos, texts, false);
    }

    /**
     * Compatibility constructor for the existing internal model and seed data.
     * Existing products deliberately start as drafts on every public channel.
     */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, String colour,
            String description, Long categoryId, Long supplierId, boolean active,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts) {
        this(id, sku, name, dimensions, Packaging.none(), colour, null, null, description,
                categoryId, supplierId, null, active,
                null, null, null, 0, true,
                null, null, PublicationState.DRAFT, PublicationState.DRAFT,
                barcodes, hsCode, carton, exwPrice, exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, false);
    }

    /** Compatibility constructor for callers written before presentation packaging existed. */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, String colour,
            String variantSize, String colourHex, String description,
            Long categoryId, Long supplierId, boolean active,
            Long familyId, String canonicalVariantKey, String canonicalBarcode,
            int variantPosition, boolean inventoryKnown,
            String familyKey, String publicHandle,
            PublicationState websiteStatus, PublicationState orderAppStatus,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts) {
        this(id, sku, name, dimensions, Packaging.none(), colour, variantSize, colourHex,
                description, categoryId, supplierId, null, active, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey,
                publicHandle, websiteStatus, orderAppStatus, barcodes, hsCode,
                carton, exwPrice, exwCurrency, extraUnitCost, landedCostEur,
                landedCostSource, markupPct, fixedSalesPriceEur, stockQuantity,
                photos, texts, false);
    }

    /** Compatibility constructor for callers written before size and colour swatches existed. */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, String colour,
            String description, Long categoryId, Long supplierId, boolean active,
            Long familyId, String canonicalVariantKey, String canonicalBarcode,
            int variantPosition, boolean inventoryKnown,
            String familyKey, String publicHandle,
            PublicationState websiteStatus, PublicationState orderAppStatus,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts) {
        this(id, sku, name, dimensions, Packaging.none(), colour, null, null, description,
                categoryId, supplierId, null, active, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey,
                publicHandle, websiteStatus, orderAppStatus, barcodes, hsCode,
                carton, exwPrice, exwCurrency, extraUnitCost, landedCostEur,
                landedCostSource, markupPct, fixedSalesPriceEur, stockQuantity,
                photos, texts, false);
    }

    /** Compatibility constructor for callers written before canonical family entities existed. */
    public Product(
            Long id, String sku, String name, Dimensions dimensions, String colour,
            String description, Long categoryId, Long supplierId, boolean active,
            String familyKey, String publicHandle,
            PublicationState websiteStatus, PublicationState orderAppStatus,
            Barcodes barcodes, String hsCode, Carton carton,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
            BigDecimal landedCostEur, String landedCostSource,
            BigDecimal markupPct, BigDecimal fixedSalesPriceEur, int stockQuantity,
            List<Photo> photos, List<ProductText> texts) {
        this(id, sku, name, dimensions, Packaging.none(), colour, null, null, description,
                categoryId, supplierId, null, active,
                null, null, null, 0, true, familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, false);
    }

    public Packaging packaging() {
        return packaging == null ? Packaging.none() : packaging;
    }

    public List<Photo> photos() {
        return photos == null ? List.of() : photos;
    }

    public List<ProductText> texts() {
        return texts == null ? List.of() : texts;
    }

    /** Null is treated as DRAFT so rows created before publication existed remain private. */
    public PublicationState publicationState(CatalogChannel channel) {
        PublicationState state = switch (channel) {
            case WEBSITE -> websiteStatus;
            case ORDER_APP -> orderAppStatus;
            /* Catalogue publication belongs to the canonical family, not legacy flat SKUs. */
            case CATALOGUE -> PublicationState.DRAFT;
        };
        return state == null ? PublicationState.DRAFT : state;
    }

    public boolean isPublishedTo(CatalogChannel channel) {
        return active && publicationState(channel) == PublicationState.PUBLISHED;
    }

    public boolean isPublishedToAnyPublicChannel() {
        return active && (publicationState(CatalogChannel.WEBSITE) == PublicationState.PUBLISHED
                || publicationState(CatalogChannel.ORDER_APP) == PublicationState.PUBLISHED);
    }

    /** The same price rule used by sales: a positive fixed price wins over cost + markup. */
    public BigDecimal computedSalesPriceEur() {
        BigDecimal price = fixedSalesPriceEur != null && fixedSalesPriceEur.signum() > 0
                ? fixedSalesPriceEur
                : Money.addPercent(landedCostEur, markupPct);
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Everything an administrator must finish before a SKU may be public.
     * These messages are part of the Dutch admin contract.
     */
    public List<String> publicationIssues() {
        List<String> issues = new ArrayList<>();
        if (!active) issues.add("Product is niet actief");
        if (isBlank(sku)) issues.add("SKU ontbreekt");
        if (isBlank(name)) issues.add("Naam ontbreekt");
        if (categoryId == null) issues.add("Categorie ontbreekt");
        if (isBlank(description)) issues.add("Beschrijving ontbreekt");
        if (photos().isEmpty()) issues.add("Minstens één foto is verplicht");
        if (computedSalesPriceEur().signum() <= 0) {
            issues.add("Verkoopprijs ontbreekt of is niet positief");
        }
        if (!validCarton()) {
            issues.add("Omdoos is ongeldig: vul positieve afmetingen en minstens 1 stuk per doos in");
        }
        if (isBlank(publicHandle)) issues.add("Publieke handle ontbreekt");
        return List.copyOf(issues);
    }

    private boolean validCarton() {
        if (carton == null || carton.piecesPerCarton() < 1 || carton.dimensions() == null) return false;
        Dimensions size = carton.dimensions();
        return positive(size.lengthCm()) && positive(size.widthCm()) && positive(size.heightCm());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
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
        return nameResolved(language).value();
    }

    public LanguageFallback.Resolved<String> nameResolved(Language language) {
        return LanguageFallback.text(texts(), language, ProductText::language,
                ProductText::name, name);
    }

    /**
     * Colour in the given language.
     *
     * A product-specific translation wins; otherwise standard colours
     * translate themselves through the shared dictionary, and anything
     * unknown stays as typed.
     */
    public String colourIn(Language language) {
        return colourResolved(language).value();
    }

    public LanguageFallback.Resolved<String> colourResolved(Language language) {
        LanguageFallback.Resolved<String> translated = LanguageFallback.text(
                texts(), language, ProductText::language, ProductText::colour, null);
        if (translated.value() != null) return translated;
        return new LanguageFallback.Resolved<>(
                be.enrosed.shared.ColourNames.translate(colour, language), null);
    }

    /** Merchandising size in this language (for example Small / Petit / Klein). */
    public String variantSizeIn(Language language) {
        return variantSizeResolved(language).value();
    }

    public LanguageFallback.Resolved<String> variantSizeResolved(Language language) {
        return LanguageFallback.text(texts(), language, ProductText::language,
                ProductText::variantSize, variantSize);
    }

    /** Description in this language, falling back to the base description. */
    public String descriptionIn(Language language) {
        return descriptionResolved(language).value();
    }

    public LanguageFallback.Resolved<String> descriptionResolved(Language language) {
        return LanguageFallback.text(texts(), language, ProductText::language,
                ProductText::description, description);
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
     * The short label a buyer reads on an order line: the name and, when
     * set, the colour. Dimensions and merchandising size stay off - they
     * clutter a line whose measurements have their own columns.
     */
    public String nameWithColour() {
        return colour == null || colour.isBlank() ? name : name + " - " + colour;
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
        String physicalSize = dimensions == null ? "" : dimensions.label();
        if (!physicalSize.isBlank()) text.append(" - ").append(physicalSize);
        String kleur = colourIn(language);
        if (kleur != null && !kleur.isBlank()) text.append(" - ").append(kleur);
        String localizedSize = variantSizeIn(language);
        if (localizedSize != null && !localizedSize.isBlank()) {
            text.append(" - ").append(localizedSize);
        }
        return text.toString();
    }

    /* Copy-methods: one aspect changes, everything else is carried over.
       Services used to re-list all twenty fields for every small change;
       one forgotten field in one of those lists silently wiped data. */

    public Product withSku(String newSku) {
        return new Product(id, newSku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, demo);
    }

    public Product withVariantAttributes(
            String newColour, String newVariantSize, String newColourHex) {
        return new Product(id, sku, name, dimensions, packaging, newColour, newVariantSize, newColourHex,
                description, categoryId, supplierId, supplierNote, active, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey, publicHandle,
                websiteStatus, orderAppStatus, barcodes, hsCode, carton, exwPrice,
                exwCurrency, extraUnitCost, landedCostEur, landedCostSource, markupPct,
                fixedSalesPriceEur, stockQuantity, photos, texts, demo);
    }

    /** Photos in gallery order; sorting lives here so no caller can forget it. */
    public Product withPhotos(List<Photo> newPhotos) {
        List<Photo> ordered = newPhotos == null ? List.of() : newPhotos.stream()
                .sorted(java.util.Comparator.comparingInt(Photo::position)).toList();
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, ordered, texts, demo);
    }

    public Product withStockQuantity(int newStock) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, true,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                newStock, photos, texts, demo);
    }

    public Product withDemo(boolean newDemo) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId, supplierId, supplierNote, active, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey, publicHandle,
                websiteStatus, orderAppStatus, barcodes, hsCode, carton, exwPrice,
                exwCurrency, extraUnitCost, landedCostEur, landedCostSource, markupPct,
                fixedSalesPriceEur, stockQuantity, photos, texts, newDemo);
    }

    public Product withActive(boolean newActive) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId, supplierId, supplierNote, newActive, familyId, canonicalVariantKey,
                canonicalBarcode, variantPosition, inventoryKnown, familyKey, publicHandle,
                websiteStatus, orderAppStatus, barcodes, hsCode, carton, exwPrice,
                exwCurrency, extraUnitCost, landedCostEur, landedCostSource, markupPct,
                fixedSalesPriceEur, stockQuantity, photos, texts, demo);
    }

    public Product withSupplierNote(String newSupplierNote) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId, supplierId, newSupplierNote, active, familyId,
                canonicalVariantKey, canonicalBarcode, variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus, barcodes, hsCode,
                carton, exwPrice, exwCurrency, extraUnitCost, landedCostEur, landedCostSource,
                markupPct, fixedSalesPriceEur, stockQuantity, photos, texts, demo);
    }

    /** Synchronizes the family-owned operational category cache without touching supplier data. */
    public Product withCategoryId(Long newCategoryId) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, newCategoryId, supplierId, supplierNote, active, familyId,
                canonicalVariantKey, canonicalBarcode, variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus, barcodes, hsCode,
                carton, exwPrice, exwCurrency, extraUnitCost, landedCostEur,
                landedCostSource, markupPct, fixedSalesPriceEur, stockQuantity, photos, texts, demo);
    }

    public Product withLandedCost(BigDecimal newLandedCostEur, String source) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, newLandedCostEur, source, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, demo);
    }

    public Product withTexts(List<ProductText> newTexts) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, newTexts, demo);
    }

    public Product withPublicationMetadata(String newFamilyKey, String newPublicHandle,
                                           PublicationState newWebsiteStatus,
                                           PublicationState newOrderAppStatus) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition, inventoryKnown,
                newFamilyKey, newPublicHandle, newWebsiteStatus,
                newOrderAppStatus, barcodes, hsCode, carton, exwPrice, exwCurrency,
                extraUnitCost, landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, demo);
    }

    public Product withCanonicalIdentity(Long newFamilyId, String newCanonicalVariantKey,
                                         String newCanonicalBarcode, int newVariantPosition,
                                         boolean newInventoryKnown) {
        return new Product(id, sku, name, dimensions, packaging, colour, variantSize, colourHex,
                description, categoryId,
                supplierId, supplierNote, active, newFamilyId, newCanonicalVariantKey, newCanonicalBarcode,
                newVariantPosition, newInventoryKnown, familyKey, publicHandle,
                websiteStatus, orderAppStatus,
                barcodes, hsCode, carton, exwPrice, exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource, markupPct, fixedSalesPriceEur,
                stockQuantity, photos, texts, demo);
    }
}
