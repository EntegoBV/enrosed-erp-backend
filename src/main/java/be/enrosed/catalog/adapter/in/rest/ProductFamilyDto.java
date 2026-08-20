package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Full administrator projection. Public consumers use PublicFamilyCatalogDto instead. */
public record ProductFamilyDto(
        Long id,
        String familyKey,
        String publicHandle,
        Long categoryId,
        String categoryKey,
        String categoryName,
        int categoryPosition,
        String collectionKey,
        List<CollectionDto> collections,
        int productPosition,
        List<String> tags,
        PublicationState websiteStatus,
        PublicationState orderAppStatus,
        PublicationState catalogueStatus,
        boolean active,
        String name,
        String summary,
        String description,
        String format,
        List<String> highlights,
        String seoTitle,
        String seoDescription,
        DimensionsDto dimensions,
        List<TextDto> texts,
        List<PackageDto> packages,
        List<ImageDto> images,
        List<ExternalIdentifierDto> externalIdentifiers,
        List<PriceObservationDto> priceObservations,
        List<ProvenanceDto> provenance,
        List<ConflictDto> conflicts,
        List<String> publicationIssues,
        long variantCount
) {
    public record DimensionsDto(BigDecimal length, BigDecimal width, BigDecimal height,
                                String unit, String raw) {}
    public record CollectionDto(Long id, String key, String name, String eyebrow,
                                String description, int position, boolean primary) {}
    public record TextDto(Language language, String name, String summary, String description,
                          String format, List<String> highlights,
                          String seoTitle, String seoDescription) {}
    public record PackageDto(Long id, String sourceKey, String packageType, int position,
                             BigDecimal length, BigDecimal width, BigDecimal height,
                             String dimensionUnit, Integer piecesPerPackage,
                             BigDecimal weight, String weightUnit, String raw,
                             String variantExternalId, Long productId,
                             Boolean axisMeaningConfirmed, String sourceType,
                             String sourceLocation, Boolean operational, String confidence) {}
    public record AltTextDto(Language language, String alt) {}
    public record ImageDto(Long id, String sourceKey, String sourceAssetId, String sourceUrl,
                           String originalFilename, Integer originalWidthPx, Integer originalHeightPx,
                           String smallUrl, String largeUrl,
                           String smallSha256, Integer smallWidthPx, Integer smallHeightPx,
                           String largeSha256, Integer largeWidthPx, Integer largeHeightPx,
                           int position, String variantExternalId, String variantColor,
                           String altTextSource, List<AltTextDto> altTexts) {}
    public record ExternalIdentifierDto(String source, String identifierType, String value) {}
    public record PriceObservationDto(
            Long id, String ownerType, String ownerKey, Long productId,
            String context, BigDecimal amount, String currency, String taxContext,
            String incoterm, String market, String sourceType, String sourceLocation,
            String rawValue, boolean publicPrice, String publicRole) {}
    public record ProvenanceDto(String fieldName, String source, String sourceRecordKey,
                                String rawValue, String confidence, String status) {}
    public record ConflictDto(String fieldName, String reason, String confidence, String status) {}

    public static ProductFamilyDto from(
            ProductFamilyEntity family,
            List<ProductExternalIdentifierEntity> identifiers,
            List<ProductPriceObservationEntity> prices,
            List<ProductProvenanceEntity> provenance,
            List<CatalogImportConflictEntity> conflicts,
            long variantCount,
            ObjectMapper json) {
        List<ImageDto> images = family.photos.stream().map(photo -> new ImageDto(
                photo.id, photo.sourceKey, photo.sourceAssetId, photo.sourceUrl,
                photo.originalFilename, photo.originalWidthPx, photo.originalHeightPx,
                "/api/product-families/" + family.id + "/images/" + photo.id + "/small",
                "/api/product-families/" + family.id + "/images/" + photo.id + "/large",
                photo.smallSha256, photo.smallWidthPx, photo.smallHeightPx,
                photo.largeSha256, photo.largeWidthPx, photo.largeHeightPx,
                photo.position, photo.variantExternalId, photo.variantColor,
                photo.altTextSource,
                read(json, photo.altTextsJson, new TypeReference<List<AltTextDto>>() {}))).toList();
        List<TextDto> texts = family.texts.stream().map(text -> new TextDto(
                text.language, text.name, text.summary, text.description, text.format,
                readStrings(json, text.highlightsJson), text.seoTitle, text.seoDescription)).toList();
        List<PackageDto> packages = family.packages.stream().map(item -> new PackageDto(
                item.id, item.sourceKey, item.packageType, item.position,
                item.lengthValue, item.widthValue, item.heightValue, item.dimensionUnit,
                item.piecesPerPackage, item.weightValue, item.weightUnit, item.rawValue,
                item.variantExternalId, item.productId, item.axisMeaningConfirmed,
                item.sourceType, item.sourceLocation, item.operational, item.confidence)).toList();
        List<CollectionDto> collections = family.collections.stream().map(item -> new CollectionDto(
                item.collection.id, item.collection.collectionKey, item.collection.name,
                item.collection.eyebrow, item.collection.description, item.position,
                item.primaryCollection)).toList();

        List<String> issues = publicationIssues(family, variantCount);
        return new ProductFamilyDto(
                family.id, family.familyKey, family.publicHandle, family.categoryId,
                family.categoryKey, family.categoryName, family.categoryPosition,
                family.collectionKey, collections, family.productPosition,
                readStrings(json, family.tagsJson), state(family.websiteStatus),
                state(family.orderAppStatus), state(family.catalogueStatus), family.active,
                family.name, family.summary, family.description, family.format,
                readStrings(json, family.highlightsJson), family.seoTitle, family.seoDescription,
                new DimensionsDto(family.dimensionLength, family.dimensionWidth,
                        family.dimensionHeight, family.dimensionUnit, family.dimensionRaw),
                texts, packages, images,
                identifiers.stream().map(item -> new ExternalIdentifierDto(
                        item.source, item.identifierType, item.externalValue)).toList(),
                prices.stream().sorted(Comparator
                                .comparing((ProductPriceObservationEntity item) -> item.ownerType,
                                        Comparator.nullsFirst(String::compareTo))
                                .thenComparing(item -> item.ownerKey,
                                        Comparator.nullsFirst(String::compareTo))
                                .thenComparing(item -> item.context,
                                        Comparator.nullsFirst(String::compareTo))
                                .thenComparing(item -> item.id,
                                        Comparator.nullsFirst(Long::compareTo)))
                        .map(item -> new PriceObservationDto(
                                item.id, item.ownerType, item.ownerKey, item.productId,
                                item.context, item.amount, item.currency, item.taxTreatment,
                                item.incoterm, item.market, item.source, item.sourceLocation,
                                item.rawValue, item.publicPrice, item.publicRole))
                        .toList(),
                provenance.stream().map(item -> new ProvenanceDto(
                        item.fieldName, item.source, item.sourceRecordKey, item.rawValue,
                        item.confidence, item.status)).toList(),
                conflicts.stream().map(item -> new ConflictDto(
                        item.fieldName, item.reason, item.confidence, item.status)).toList(),
                issues, variantCount);
    }

    static List<String> publicationIssues(ProductFamilyEntity family, long variantCount) {
        List<String> issues = new ArrayList<>();
        if (!family.active) issues.add("Productfamilie is niet actief");
        if (blank(family.familyKey)) issues.add("Familiecode ontbreekt");
        if (blank(family.publicHandle)) issues.add("Publieke handle ontbreekt");
        if (!hasText(family, family.name, item -> item.name)) issues.add("Naam ontbreekt");
        if (!hasText(family, family.summary, item -> item.summary)) issues.add("Samenvatting ontbreekt");
        if (!hasText(family, family.description, item -> item.description)) {
            issues.add("Beschrijving ontbreekt");
        }
        if (blank(family.categoryKey)) issues.add("Categorie ontbreekt");
        if (!hasText(family, family.seoTitle, item -> item.seoTitle)) issues.add("SEO-titel ontbreekt");
        if (!hasText(family, family.seoDescription, item -> item.seoDescription)) {
            issues.add("SEO-beschrijving ontbreekt");
        }
        ProductFamilyCollectionEntity primary = family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst().orElse(null);
        if (primary == null) issues.add("Primaire collectie ontbreekt");
        else {
            if (blank(primary.collection.eyebrow)) issues.add("Collectie-eyebrow ontbreekt");
            if (blank(primary.collection.description)) issues.add("Collectiebeschrijving ontbreekt");
        }
        if (family.photos.isEmpty()) issues.add("Minstens één foto is verplicht");
        if (family.photos.stream().anyMatch(photo -> photo.smallWidthPx == null
                || photo.smallWidthPx <= 0 || photo.smallHeightPx == null || photo.smallHeightPx <= 0
                || photo.largeWidthPx == null || photo.largeWidthPx <= 0
                || photo.largeHeightPx == null || photo.largeHeightPx <= 0)) {
            issues.add("Elke foto moet geldige kleine en grote afmetingen hebben");
        }
        if (family.photos.stream().anyMatch(photo -> blank(photo.altTextsJson)
                || "[]".equals(photo.altTextsJson.strip()))) {
            issues.add("Elke foto moet een alt-tekst hebben");
        }
        if (variantCount == 0) issues.add("Minstens één variant is verplicht");
        return List.copyOf(issues);
    }

    private static PublicationState state(PublicationState value) {
        return value == null ? PublicationState.DRAFT : value;
    }

    private static boolean hasText(
            ProductFamilyEntity family, String base, Function<ProductFamilyTextEntity, String> field) {
        return !blank(base) || family.texts.stream().map(field).anyMatch(value -> !blank(value));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static List<String> readStrings(ObjectMapper json, String value) {
        return read(json, value, new TypeReference<List<String>>() {});
    }

    static <T> T read(ObjectMapper json, String value, TypeReference<T> type) {
        if (value == null || value.isBlank()) {
            @SuppressWarnings("unchecked") T empty = (T) List.of();
            return empty;
        }
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Ongeldige gecanonicaliseerde JSON in de database", exception);
        }
    }
}
