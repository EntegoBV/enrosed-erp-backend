package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Public family catalogue projection. It intentionally contains no suppliers,
 * costs, provenance, source identifiers, conflicts, or internal observations.
 */
public record PublicFamilyCatalogDto(
        CatalogChannel channel,
        Language language,
        List<Language> fallbackChain,
        long siteCopyRevision,
        String catalogRevision,
        Map<String, LocalizedValueDto> siteCopy,
        List<CategoryDto> categories,
        List<FamilyDto> families
) {
    public record FamilyDto(
            Long id,
            String familyKey,
            String publicHandle,
            String name,
            String summary,
            String description,
            String format,
            List<String> highlights,
            CategoryDto category,
            int productPosition,
            Long cardFeaturedProductId,
            List<String> tags,
            String status,
            SeoDto seo,
            DimensionsDto dimensions,
            List<PackageDto> packages,
            List<ImageDto> images,
            List<VariantDto> variants,
            Map<String, Language> textSources
    ) {}

    public record CategoryDto(
            String key,
            String name,
            int position,
            String eyebrow,
            String description,
            String mobileName,
            String navigationName,
            String footerName,
            Long featuredProductId,
            Map<String, Language> textSources,
            /** The category's own photo, public: the picture a collection opens with; null when none. */
            String photoUrl,
            Integer photoWidth,
            Integer photoHeight
    ) {}

    public record SeoDto(String title, String description) {}

    /** Legacy wire names; length=B, width=D, height=H. */
    public record DimensionsDto(
            BigDecimal length,
            BigDecimal width,
            BigDecimal height,
            String unit,
            String raw
    ) {}

    public record PackageDto(
            String packageType,
            int position,
            DimensionsDto dimensions,
            Integer piecesPerPackage,
            BigDecimal weight,
            String weightUnit,
            String variantExternalId
    ) {}

    public record ImageDto(
            Long id,
            String smallUrl,
            String largeUrl,
            Integer smallWidth,
            Integer smallHeight,
            Integer largeWidth,
            Integer largeHeight,
            String alt,
            int position,
            Long variantProductId,
            String variantExternalId,
            String variantColor,
            Map<String, Language> textSources
    ) {}

    public record VariantDto(
            Long id,
            String sku,
            String barcode,
            String color,
            String size,
            String colorHex,
            String name,
            int position,
            Object availability,
            Long primaryImageId,
            PublicPriceDto publicPrice,
            Map<String, Language> textSources
    ) {}

    public record PublicPriceDto(
            BigDecimal amount,
            String currency,
            BigDecimal compareAtAmount
    ) {}
}
