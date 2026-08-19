package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deliberately small, public projection of the catalogue.
 *
 * Never reuse {@link ProductDto} here: it contains supplier, cost, margin,
 * customs and stock details that public consumers must never receive.
 */
public record PublicCatalogDto(
        CatalogChannel channel,
        Language language,
        List<PublicProductDto> products
) {

    public enum Availability {
        IN_STOCK,
        AVAILABLE_ON_ORDER
    }

    public record PublicProductDto(
            Long id,
            String sku,
            String familyKey,
            String publicHandle,
            String name,
            String description,
            String colour,
            CategoryDto category,
            DimensionsDto dimensions,
            CartonDto carton,
            BigDecimal salesPriceEur,
            Availability availability,
            List<PhotoDto> photos
    ) {}

    public record CategoryDto(Long id, String code, String name, String description) {}

    public record DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}

    public record CartonDto(
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            int piecesPerCarton
    ) {}

    public record PhotoDto(
            Long id,
            String contentType,
            Integer widthPx,
            Integer heightPx,
            int position,
            String url
    ) {}

    public static PublicProductDto product(
            Product product, Category category, Language language, String apiBaseUrl) {
        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions box = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        CategoryDto publicCategory = category == null ? null
                : new CategoryDto(category.id(), category.code(), category.name(), category.description());
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";

        List<PhotoDto> photos = product.photos().stream()
                .map(photo -> new PhotoDto(
                        photo.id(), photo.contentType(), photo.widthPx(), photo.heightPx(), photo.position(),
                        base + "api/v1/public/catalog/products/" + product.id()
                                + "/photos/" + photo.id()))
                .toList();

        return new PublicProductDto(
                product.id(), product.sku(), product.familyKey(), product.publicHandle(),
                product.nameIn(language), product.descriptionIn(language), product.colourIn(language),
                publicCategory,
                new DimensionsDto(size.lengthCm(), size.widthCm(), size.heightCm()),
                new CartonDto(box.lengthCm(), box.widthCm(), box.heightCm(), carton.piecesPerCarton()),
                product.computedSalesPriceEur(),
                product.stockQuantity() > 0 ? Availability.IN_STOCK : Availability.AVAILABLE_ON_ORDER,
                photos);
    }
}
