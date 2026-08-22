package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between the JPA entities and the domain records.
 *
 * Deliberately by hand: the domain must not be welded to Hibernate, and
 * this keeps visible which database field ends up where.
 */
final class CatalogMapper {

    private CatalogMapper() {}

    static Product toDomain(ProductEntity entity) {
        List<Photo> photos = new ArrayList<>();
        for (ProductPhotoEntity photo : entity.photos) {
            photos.add(new Photo(photo.id, photo.storageKey, photo.originalFilename,
                    photo.contentType, photo.sizeBytes, photo.widthPx, photo.heightPx,
                    photo.position, photo.familyPhotoId));
        }
        List<ProductText> texts = new ArrayList<>();
        for (ProductTextEntity text : entity.texts) {
            texts.add(new ProductText(text.language, text.name, text.description,
                    text.colour, text.variantSize));
        }
        return new Product(
                entity.id,
                entity.sku,
                entity.name,
                new Dimensions(entity.productLengthCm, entity.productWidthCm, entity.productHeightCm),
                entity.colour,
                entity.variantSize,
                entity.colourHex,
                entity.description,
                entity.categoryId,
                entity.supplierId,
                entity.active,
                entity.familyId,
                entity.canonicalVariantKey,
                entity.canonicalBarcode,
                entity.variantPosition,
                entity.inventoryKnown,
                entity.familyKey,
                entity.publicHandle,
                entity.websiteStatus == null ? PublicationState.DRAFT : entity.websiteStatus,
                entity.orderAppStatus == null ? PublicationState.DRAFT : entity.orderAppStatus,
                new Barcodes(entity.barcodeInner, entity.barcodeOuter),
                entity.hsCode,
                new Carton(new Dimensions(entity.cartonLengthCm, entity.cartonWidthCm, entity.cartonHeightCm),
                        entity.piecesPerCarton, entity.cartonWeightKg),
                entity.exwPrice,
                entity.exwCurrency,
                entity.extraUnitCost,
                entity.landedCostEur,
                entity.landedCostSource,
                entity.markupPct,
                entity.fixedSalesPriceEur,
                entity.stockQuantity,
                photos,
                texts);
    }

    static void apply(Product product, ProductEntity entity) {
        entity.sku = product.sku();
        entity.name = product.name();

        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        entity.productLengthCm = size.lengthCm();
        entity.productWidthCm = size.widthCm();
        entity.productHeightCm = size.heightCm();

        entity.colour = product.colour();
        entity.variantSize = blankToNull(product.variantSize());
        /* A standard colour name carries its own swatch; only an explicit
           sample overrides it. Applied here so every write path - editor,
           copy, CSV import - agrees. */
        String swatch = be.enrosed.shared.ColourSwatches.orDefault(
                blankToNull(product.colourHex()), product.colour());
        entity.colourHex = swatch == null ? null : swatch.toUpperCase();
        entity.description = blankToNull(product.description());
        entity.categoryId = product.categoryId();
        entity.supplierId = product.supplierId();
        entity.active = product.active();
        entity.familyId = product.familyId();
        entity.canonicalVariantKey = blankToNull(product.canonicalVariantKey());
        entity.canonicalBarcode = blankToNull(product.canonicalBarcode());
        entity.variantPosition = product.variantPosition();
        entity.inventoryKnown = product.inventoryKnown();
        entity.familyKey = blankToNull(product.familyKey());
        entity.publicHandle = blankToNull(product.publicHandle());
        entity.websiteStatus = product.publicationState(CatalogChannel.WEBSITE);
        entity.orderAppStatus = product.publicationState(CatalogChannel.ORDER_APP);

        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();
        entity.barcodeInner = blankToNull(codes.inner());
        entity.barcodeOuter = blankToNull(codes.outer());
        entity.hsCode = blankToNull(product.hsCode());

        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions cartonSize = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        entity.cartonLengthCm = cartonSize.lengthCm();
        entity.cartonWidthCm = cartonSize.widthCm();
        entity.cartonHeightCm = cartonSize.heightCm();
        entity.piecesPerCarton = carton.piecesPerCarton();
        entity.cartonWeightKg = carton.weightKg();

        entity.exwPrice = product.exwPrice();
        entity.exwCurrency = product.exwCurrency();
        entity.extraUnitCost = product.extraUnitCost();
        entity.landedCostEur = product.landedCostEur();
        entity.landedCostSource = product.landedCostSource();
        entity.markupPct = product.markupPct();
        entity.fixedSalesPriceEur = product.fixedSalesPriceEur();
        entity.stockQuantity = product.stockQuantity();
    }

    /**
     * Updates the translations: one row per language, empty rows disappear.
     *
     * A language whose fields were all cleared is dropped rather than left
     * as an empty row - otherwise the table grows rows that say nothing and
     * the translation status counts wrong.
     */
    static void applyTexts(Product product, ProductEntity entity) {
        Map<Language, ProductText> wanted = new LinkedHashMap<>();
        for (ProductText text : product.texts()) {
            if (text != null && text.language() != null && !text.isEmpty()) {
                wanted.put(text.language(), text);
            }
        }

        entity.texts.removeIf(existing -> !wanted.containsKey(existing.language));

        for (ProductTextEntity existing : entity.texts) {
            ProductText text = wanted.remove(existing.language);
            existing.name = blankToNull(text.name());
            existing.description = blankToNull(text.description());
            existing.colour = blankToNull(text.colour());
            existing.variantSize = blankToNull(text.variantSize());
        }

        for (ProductText text : wanted.values()) {
            ProductTextEntity added = new ProductTextEntity();
            added.product = entity;
            added.language = text.language();
            added.name = blankToNull(text.name());
            added.description = blankToNull(text.description());
            added.colour = blankToNull(text.colour());
            added.variantSize = blankToNull(text.variantSize());
            entity.texts.add(added);
        }
    }

    /** Updates the photo series: new ones in, vanished ones out, order kept. */
    static void applyPhotos(Product product, ProductEntity entity) {
        List<Photo> wanted = product.photos();

        entity.photos.removeIf(existing -> wanted.stream()
                .noneMatch(photo -> photo.id() != null && photo.id().equals(existing.id)));

        for (Photo photo : wanted) {
            ProductPhotoEntity target = entity.photos.stream()
                    .filter(existing -> photo.id() != null && photo.id().equals(existing.id))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                target = new ProductPhotoEntity();
                target.product = entity;
                entity.photos.add(target);
            }
            target.storageKey = photo.storageKey();
            target.originalFilename = photo.originalFilename();
            target.contentType = photo.contentType();
            target.sizeBytes = photo.sizeBytes();
            target.widthPx = photo.widthPx();
            target.heightPx = photo.heightPx();
            target.position = photo.position();
            target.familyPhotoId = photo.familyPhotoId();
        }
        entity.photos.sort((a, b) -> Integer.compare(a.position, b.position));
    }

    static Category toDomain(CategoryEntity entity) {
        List<CategoryText> texts = entity.texts.stream()
                .map(text -> new CategoryText(text.language, text.name, text.description,
                        text.eyebrow, text.mobileName, text.navigationName, text.footerName))
                .toList();
        return new Category(entity.id, entity.code, entity.name, entity.description,
                entity.eyebrow, entity.position, entity.mobileName, entity.navigationName,
                entity.footerName, entity.featuredProductId, texts, entity.revision);
    }

    static void apply(Category category, CategoryEntity entity) {
        entity.code = category.code();
        entity.name = category.name();
        entity.description = category.description();
        entity.eyebrow = blankToNull(category.eyebrow());
        entity.position = category.position();
        entity.mobileName = blankToNull(category.mobileName());
        entity.navigationName = blankToNull(category.navigationName());
        entity.footerName = blankToNull(category.footerName());
        entity.featuredProductId = category.featuredProductId();
        applyCategoryTexts(category, entity);
    }

    private static void applyCategoryTexts(Category category, CategoryEntity entity) {
        Map<Language, CategoryText> wanted = new LinkedHashMap<>();
        for (CategoryText text : category.texts()) {
            if (text != null && text.language() != null && !text.isEmpty()) {
                if (wanted.put(text.language(), text) != null) {
                    throw new be.enrosed.shared.BusinessRuleException(
                            "Elke categorietaal mag exact één keer voorkomen");
                }
            }
        }
        entity.texts.removeIf(existing -> !wanted.containsKey(existing.language));
        for (CategoryTextEntity existing : entity.texts) {
            CategoryText text = wanted.remove(existing.language);
            existing.name = blankToNull(text.name());
            existing.description = blankToNull(text.description());
            existing.eyebrow = blankToNull(text.eyebrow());
            existing.mobileName = blankToNull(text.mobileName());
            existing.navigationName = blankToNull(text.navigationName());
            existing.footerName = blankToNull(text.footerName());
        }
        for (CategoryText text : wanted.values()) {
            CategoryTextEntity added = new CategoryTextEntity();
            added.category = entity;
            added.language = text.language();
            added.name = blankToNull(text.name());
            added.description = blankToNull(text.description());
            added.eyebrow = blankToNull(text.eyebrow());
            added.mobileName = blankToNull(text.mobileName());
            added.navigationName = blankToNull(text.navigationName());
            added.footerName = blankToNull(text.footerName());
            entity.texts.add(added);
        }
    }

    static HsCode toDomain(HsCodeEntity entity) {
        return new HsCode(entity.id, entity.code, entity.description, entity.dutyRatePct);
    }

    static void apply(HsCode hsCode, HsCodeEntity entity) {
        entity.code = hsCode.code();
        entity.description = hsCode.description();
        entity.dutyRatePct = hsCode.dutyRatePct();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
