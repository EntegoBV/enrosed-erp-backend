package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/**
 * Product as it goes over the wire.
 *
 * Product dimensions, colour and the outer carton are three separate things
 * here - just like in the domain.
 */
public record ProductDto(
        Long id,
        String sku,
        String name,
        DimensionsDto dimensions,
        /** Gift box or display around the product; null or kind NONE when sold bare. */
        PackagingDto packaging,
        String colour,
        String variantSize,
        String colourHex,
        String description,
        Long categoryId,
        Long supplierId,
        Boolean active,
        Long familyId,
        String canonicalVariantKey,
        String canonicalBarcode,
        Integer variantPosition,
        Boolean inventoryKnown,
        String familyKey,
        String publicHandle,
        PublicationState websiteStatus,
        PublicationState orderAppStatus,
        String barcodeInner,
        String barcodeOuter,
        String hsCode,
        CartonDto carton,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,
        BigDecimal landedCostEur,
        String landedCostSource,
        BigDecimal markupPct,
        BigDecimal fixedSalesPriceEur,
        Integer stockQuantity,
        List<PhotoDto> photos,
        /** Name, description, colour and merchandising size per language. */
        List<TextDto> texts,
        /* derived, outbound only */
        List<String> publicationIssues,
        String describedAs,
        BigDecimal cartonCbm,
        BigDecimal pieceCbm,
        BigDecimal computedSalesPriceEur
) {

    /** Legacy wire names; displayed as B × D × H in this unchanged value order. */
    public record DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}

    public record PackagingDto(PackagingKind kind, DimensionsDto dimensions) {}

    /** Legacy wire names; displayed as B × D × H in this unchanged value order. */
    public record CartonDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                            Integer piecesPerCarton, BigDecimal weightKg) {}

    public record TextDto(
            Language language, String name, String description, String colour, String variantSize) {
        /** Backward-compatible request/source shape. */
        public TextDto(Language language, String name, String description, String colour) {
            this(language, name, description, colour, null);
        }
    }

    public enum PhotoOrigin { PRODUCT, FAMILY }

    public record PhotoDto(Long id, String originalFilename, String contentType, long sizeBytes,
                           Integer widthPx, Integer heightPx, int position,
                           String url, String downloadUrl,
                           Long familyPhotoId, PhotoOrigin origin, boolean readOnly) {}

    public static ProductDto from(Product product) {
        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions cartonSize = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();

        List<PhotoDto> photos = product.photos().stream()
                .map(photo -> new PhotoDto(photo.id(), photo.originalFilename(), photo.contentType(),
                        photo.sizeBytes(), photo.widthPx(), photo.heightPx(), photo.position(),
                        "/api/products/" + product.id() + "/photos/" + photo.id(),
                        "/api/products/" + product.id() + "/photos/" + photo.id() + "/download",
                        photo.familyPhotoId(),
                        photo.inherited() ? PhotoOrigin.FAMILY : PhotoOrigin.PRODUCT,
                        photo.inherited()))
                .toList();

        List<TextDto> texts = product.texts().stream()
                .map(text -> new TextDto(text.language(), text.name(), text.description(),
                        text.colour(), text.variantSize()))
                .toList();

        return new ProductDto(
                product.id(), product.sku(), product.name(),
                new DimensionsDto(size.lengthCm(), size.widthCm(), size.heightCm()),
                new PackagingDto(product.packaging().kind(), new DimensionsDto(
                        product.packaging().dimensions().lengthCm(),
                        product.packaging().dimensions().widthCm(),
                        product.packaging().dimensions().heightCm())),
                product.colour(), product.variantSize(), product.colourHex(), product.description(),
                product.categoryId(), product.supplierId(), product.active(),
                product.familyId(), product.canonicalVariantKey(), product.canonicalBarcode(),
                product.variantPosition(),
                product.inventoryKnown(),
                product.familyKey(), product.publicHandle(),
                product.publicationState(CatalogChannel.WEBSITE),
                product.publicationState(CatalogChannel.ORDER_APP),
                codes.inner(), codes.outer(), product.hsCode(),
                new CartonDto(cartonSize.lengthCm(), cartonSize.widthCm(), cartonSize.heightCm(),
                        carton.piecesPerCarton(), carton.weightKg()),
                product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                product.landedCostEur(), product.landedCostSource(),
                product.markupPct(), product.fixedSalesPriceEur(), product.stockQuantity(),
                photos, texts,
                product.publicationIssues(),
                product.describe(), carton.cbm(), carton.pieceCbm(), product.computedSalesPriceEur());
    }

    public Product toDomain(Long id) {
        DimensionsDto size = dimensions == null
                ? new DimensionsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : dimensions;
        CartonDto box = carton == null
                ? new CartonDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, BigDecimal.ZERO) : carton;

        DimensionsDto wrap = packaging == null || packaging.dimensions() == null
                ? new DimensionsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : packaging.dimensions();
        Packaging presentation = packaging == null || packaging.kind() == null
                ? Packaging.none()
                : new Packaging(packaging.kind(),
                        new Dimensions(wrap.lengthCm(), wrap.widthCm(), wrap.heightCm()));

        return new Product(
                id, sku, name,
                new Dimensions(size.lengthCm(), size.widthCm(), size.heightCm()),
                presentation,
                colour, variantSize, colourHex, description,
                categoryId, supplierId, active == null || active,
                familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition == null ? 0 : variantPosition,
                inventoryKnown == null || inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                new Barcodes(barcodeInner, barcodeOuter), hsCode,
                new Carton(new Dimensions(box.lengthCm(), box.widthCm(), box.heightCm()),
                        box.piecesPerCarton() == null ? 1 : box.piecesPerCarton(), box.weightKg()),
                exwPrice, exwCurrency == null ? Currency.USD : exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource,
                markupPct, fixedSalesPriceEur,
                stockQuantity == null ? 0 : stockQuantity,
                List.of(),
                texts == null ? List.of() : texts.stream()
                        .filter(text -> text != null && text.language() != null)
                        .map(text -> new ProductText(text.language(), text.name(),
                                text.description(), text.colour(), text.variantSize()))
                        .toList());
    }

    /** Preserves fields that older full-PUT clients could not send yet. */
    public Product toDomainForUpdate(Product current) {
        Product changes = toDomain(current.id());
        if (inventoryKnown == null) {
            changes = changes.withCanonicalIdentity(
                    changes.familyId(), changes.canonicalVariantKey(), changes.canonicalBarcode(),
                    changes.variantPosition(), current.inventoryKnown());
        }
        return changes;
    }
}
