package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertaalt tussen de JPA-entiteiten en de domeinrecords.
 *
 * Bewust met de hand: het domein mag niet vastzitten aan Hibernate, en zo
 * blijft zichtbaar welk databaseveld waar terechtkomt.
 */
final class CatalogMapper {

    private CatalogMapper() {}

    static Product toDomain(ProductEntity entity) {
        List<Photo> photos = new ArrayList<>();
        for (ProductPhotoEntity photo : entity.photos) {
            photos.add(new Photo(photo.id, photo.storageKey, photo.originalFilename,
                    photo.contentType, photo.sizeBytes, photo.widthPx, photo.heightPx, photo.position));
        }
        List<ProductText> texts = new ArrayList<>();
        for (ProductTextEntity text : entity.texts) {
            texts.add(new ProductText(text.language, text.name, text.description, text.colour));
        }
        return new Product(
                entity.id,
                entity.sku,
                entity.name,
                new Dimensions(entity.productLengthCm, entity.productWidthCm, entity.productHeightCm),
                entity.colour,
                entity.description,
                entity.categoryId,
                entity.supplierId,
                entity.active,
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
        entity.description = blankToNull(product.description());
        entity.categoryId = product.categoryId();
        entity.supplierId = product.supplierId();
        entity.active = product.active();

        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();
        entity.barcodeInner = blankToNull(codes.inner());
        entity.barcodeOuter = blankToNull(codes.outer());
        entity.hsCode = blankToNull(product.hsCode());

        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions cartonSize = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        entity.cartonLengthCm = cartonSize.lengthCm();
        entity.cartonWidthCm = cartonSize.widthCm();
        entity.cartonHeightCm = cartonSize.heightCm();
        entity.piecesPerCarton = Math.max(1, carton.piecesPerCarton());
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
     * Werkt de vertalingen bij: één rij per taal, lege rijen verdwijnen.
     *
     * Een taal waarvan alle velden leeggemaakt zijn wordt geschrapt in plaats
     * van als lege rij te blijven staan - anders groeit de tabel aan met rijen
     * die niets zeggen en telt de vertaalstatus verkeerd.
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
        }

        for (ProductText text : wanted.values()) {
            ProductTextEntity added = new ProductTextEntity();
            added.product = entity;
            added.language = text.language();
            added.name = blankToNull(text.name());
            added.description = blankToNull(text.description());
            added.colour = blankToNull(text.colour());
            entity.texts.add(added);
        }
    }

    /** Werkt de fotoreeks bij: nieuwe erbij, verdwenen eruit, volgorde gelijk. */
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
        }
        entity.photos.sort((a, b) -> Integer.compare(a.position, b.position));
    }

    static Category toDomain(CategoryEntity entity) {
        return new Category(entity.id, entity.code, entity.name, entity.description, entity.position);
    }

    static void apply(Category category, CategoryEntity entity) {
        entity.code = category.code();
        entity.name = category.name();
        entity.description = category.description();
        entity.position = category.position();
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
