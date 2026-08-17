package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/**
 * Product zoals het over de lijn gaat.
 *
 * Afmeting van het product, colour (kleur) en de omdoos zijn hier drie
 * aparte dingen - net als in het domein.
 */
public record ProductDto(
        Long id,
        String sku,
        String name,
        DimensionsDto dimensions,
        String colour,
        String description,
        Long categoryId,
        Long supplierId,
        Boolean active,
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
        /** Naam, beschrijving en kleur per taal; de rest blijft universeel. */
        List<TextDto> texts,
        /* afgeleid, alleen uitgaand */
        String describedAs,
        BigDecimal cartonCbm,
        BigDecimal pieceCbm
) {

    public record DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}

    public record CartonDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                            Integer piecesPerCarton, BigDecimal weightKg) {}

    public record TextDto(Language language, String name, String description, String colour) {}

    public record PhotoDto(Long id, String originalFilename, String contentType, long sizeBytes,
                           Integer widthPx, Integer heightPx, int position, String url, String downloadUrl) {}

    public static ProductDto from(Product product) {
        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions cartonSize = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();

        List<PhotoDto> photos = product.photos().stream()
                .map(photo -> new PhotoDto(photo.id(), photo.originalFilename(), photo.contentType(),
                        photo.sizeBytes(), photo.widthPx(), photo.heightPx(), photo.position(),
                        "/api/products/" + product.id() + "/photos/" + photo.id(),
                        "/api/products/" + product.id() + "/photos/" + photo.id() + "/download"))
                .toList();

        List<TextDto> texts = product.texts().stream()
                .map(text -> new TextDto(text.language(), text.name(), text.description(), text.colour()))
                .toList();

        return new ProductDto(
                product.id(), product.sku(), product.name(),
                new DimensionsDto(size.lengthCm(), size.widthCm(), size.heightCm()),
                product.colour(), product.description(),
                product.categoryId(), product.supplierId(), product.active(),
                codes.inner(), codes.outer(), product.hsCode(),
                new CartonDto(cartonSize.lengthCm(), cartonSize.widthCm(), cartonSize.heightCm(),
                        carton.piecesPerCarton(), carton.weightKg()),
                product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                product.landedCostEur(), product.landedCostSource(),
                product.markupPct(), product.fixedSalesPriceEur(), product.stockQuantity(),
                photos, texts,
                product.describe(), carton.cbm(), carton.pieceCbm());
    }

    public Product toDomain(Long id) {
        DimensionsDto size = dimensions == null
                ? new DimensionsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : dimensions;
        CartonDto box = carton == null
                ? new CartonDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, BigDecimal.ZERO) : carton;

        return new Product(
                id, sku, name,
                new Dimensions(size.lengthCm(), size.widthCm(), size.heightCm()),
                colour, description, categoryId, supplierId, active == null || active,
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
                                text.description(), text.colour()))
                        .toList());
    }
}
