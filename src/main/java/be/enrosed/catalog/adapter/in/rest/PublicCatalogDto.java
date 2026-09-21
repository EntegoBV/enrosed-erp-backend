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
        OUT_OF_STOCK,
        AVAILABLE_ON_ORDER,
        UNKNOWN
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

    public record CategoryDto(
            Long id, String code, String name, String description,
            String mobileName, Long featuredProductId) {}

    /** Legacy wire names; lengthCm=B, widthCm=D, heightCm=H. */
    public record DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}

    /** Legacy wire names; lengthCm=B, widthCm=D, heightCm=H. */
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
        return product(product, category, language, apiBaseUrl, product.nameIn(language));
    }

    /** Additive overload used by the public adapter once website names diverge from documents. */
    public static PublicProductDto product(
            Product product, Category category, Language language, String apiBaseUrl,
            String publicName) {
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        // Direct callers have no family publication context. Never expose inherited images.
        List<PhotoDto> photos = product.photosFor(be.enrosed.catalog.domain.PhotoRole.WEBSITE).stream()
                .filter(photo -> !photo.inherited())
                .map(photo -> new PhotoDto(
                        photo.id(), photo.contentType(), photo.widthPx(), photo.heightPx(), photo.position(),
                        base + "api/v1/public/catalog/products/" + product.id()
                                + "/photos/" + photo.id()))
                .toList();
        return product(product, category, language, publicName, photos);
    }

    /** The adapter supplies the same authorized ERP image projection as the family catalogue. */
    public static PublicProductDto product(
            Product product, Category category, Language language, String publicName,
            List<PhotoDto> photos) {
        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions box = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        CategoryDto publicCategory = category == null ? null
                : new CategoryDto(category.id(), category.code(), category.name(),
                        category.description(), category.mobileName(), category.featuredProductId());
        BigDecimal salesPrice = product.computedSalesPriceEur();

        return new PublicProductDto(
                product.id(), product.sku(), product.familyKey(), product.publicHandle(),
                publicName, product.descriptionIn(language), product.colourIn(language),
                publicCategory,
                new DimensionsDto(size.lengthCm(), size.widthCm(), size.heightCm()),
                new CartonDto(box.lengthCm(), box.widthCm(), box.heightCm(), carton.piecesPerCarton()),
                salesPrice != null && salesPrice.signum() > 0 ? salesPrice : null,
                !product.inventoryKnown() ? Availability.UNKNOWN
                        : product.stockQuantity() > 0
                            ? Availability.IN_STOCK : Availability.OUT_OF_STOCK,
                photos);
    }
}
