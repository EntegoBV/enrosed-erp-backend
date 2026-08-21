package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.application.FamilyPhotoVariantResolver;
import be.enrosed.catalog.application.FamilyVariantRules;
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
        Long cardFeaturedProductId,
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
        List<MemberDto> members,
        List<String> publicationIssues,
        long variantCount
) {
    /** Legacy wire names; length=B, width=D, height=H. */
    public record DimensionsDto(BigDecimal length, BigDecimal width, BigDecimal height,
                                String unit, String raw) {}
    public record CollectionDto(Long id, String key, String name, String eyebrow,
                                String description, int position, boolean primary,
                                String mobileName, Long featuredProductId) {}
    public record TextDto(Language language, String name, String summary, String description,
                          String format, List<String> highlights,
                          String seoTitle, String seoDescription) {}
    /** Package length/width/height keep their wire names and display as B × D × H. */
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
                           int position, Long variantProductId,
                           String variantExternalId, String variantColor,
                           String altTextSource, List<AltTextDto> altTexts) {}
    public record MemberDto(Long productId, String canonicalVariantKey, String sku,
                            String name, String colour, String size, String colourHex,
                            int position, boolean active) {}
    public record ExternalIdentifierDto(String source, String identifierType, String value) {}
    public record PriceObservationDto(
            Long id, String ownerType, String ownerKey, Long productId,
            String context, BigDecimal amount, String currency, String taxContext,
            String incoterm, String market, String sourceType, String sourceLocation,
            String rawValue, boolean publicPrice, String publicRole) {}
    public record ProvenanceDto(String fieldName, String source, String sourceRecordKey,
                                String rawValue, String confidence, String status) {}
    public record ConflictDto(String fieldName, String reason, String confidence, String status) {}

    public ProductFamilyDto withAdditionalPublicationIssues(List<String> additional) {
        if (additional == null || additional.isEmpty()) return this;
        List<String> combined = new ArrayList<>(publicationIssues == null
                ? List.of() : publicationIssues);
        additional.stream().filter(issue -> !combined.contains(issue)).forEach(combined::add);
        return new ProductFamilyDto(
                id, familyKey, publicHandle, categoryId, categoryKey, categoryName,
                categoryPosition, collectionKey, collections, productPosition,
                cardFeaturedProductId, tags, websiteStatus, orderAppStatus, catalogueStatus,
                active, name, summary, description, format, highlights, seoTitle,
                seoDescription, dimensions, texts, packages, images, externalIdentifiers,
                priceObservations, provenance, conflicts, members, List.copyOf(combined),
                variantCount);
    }

    /** Request-copy helper used by clients that edit the revisioned family text snapshot. */
    public ProductFamilyDto withTexts(List<TextDto> replacementTexts) {
        return new ProductFamilyDto(
                id, familyKey, publicHandle, categoryId, categoryKey, categoryName,
                categoryPosition, collectionKey, collections, productPosition,
                cardFeaturedProductId, tags, websiteStatus, orderAppStatus, catalogueStatus,
                active, name, summary, description, format, highlights, seoTitle,
                seoDescription, dimensions,
                replacementTexts == null ? List.of() : List.copyOf(replacementTexts),
                packages, images, externalIdentifiers, priceObservations, provenance, conflicts,
                members, publicationIssues, variantCount);
    }

    public static ProductFamilyDto from(
            ProductFamilyEntity family,
            List<ProductExternalIdentifierEntity> identifiers,
            List<ProductPriceObservationEntity> prices,
            List<ProductProvenanceEntity> provenance,
            List<CatalogImportConflictEntity> conflicts,
            List<ProductEntity> memberRows,
            ObjectMapper json) {
        List<ImageDto> images = family.photos.stream().map(photo -> new ImageDto(
                photo.id, photo.sourceKey, photo.sourceAssetId, photo.sourceUrl,
                photo.originalFilename, photo.originalWidthPx, photo.originalHeightPx,
                "/api/product-families/" + family.id + "/images/" + photo.id + "/small",
                "/api/product-families/" + family.id + "/images/" + photo.id + "/large",
                photo.smallSha256, photo.smallWidthPx, photo.smallHeightPx,
                photo.largeSha256, photo.largeWidthPx, photo.largeHeightPx,
                photo.position, photo.variantProduct == null ? null : photo.variantProduct.id,
                photo.variantExternalId, photo.variantColor,
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
                item.primaryCollection, item.collection.mobileName,
                item.collection.featuredProductId)).toList();

        List<ProductEntity> orderedMembers = memberRows == null ? List.of() : memberRows.stream()
                .sorted(Comparator.comparingInt((ProductEntity item) -> item.variantPosition)
                        .thenComparing(item -> item.id, Comparator.nullsLast(Long::compareTo)))
                .toList();
        long variantCount = orderedMembers.size();
        List<String> issues = publicationIssues(family, orderedMembers, json);
        return new ProductFamilyDto(
                family.id, family.familyKey, family.publicHandle, family.categoryId,
                family.categoryKey, family.categoryName, family.categoryPosition,
                family.collectionKey, collections, family.productPosition,
                family.cardFeaturedProductId,
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
                orderedMembers.stream().map(item -> new MemberDto(
                        item.id, item.canonicalVariantKey, item.sku, item.name,
                        item.colour, item.variantSize, item.colourHex,
                        item.variantPosition, item.active)).toList(),
                issues, variantCount);
    }

    /** Compatibility projection for callers that only need readiness counts. */
    public static ProductFamilyDto from(
            ProductFamilyEntity family,
            List<ProductExternalIdentifierEntity> identifiers,
            List<ProductPriceObservationEntity> prices,
            List<ProductProvenanceEntity> provenance,
            List<CatalogImportConflictEntity> conflicts,
            long variantCount,
            ObjectMapper json) {
        ProductFamilyDto dto = from(family, identifiers, prices, provenance, conflicts, List.of(), json);
        return new ProductFamilyDto(
                dto.id, dto.familyKey, dto.publicHandle, dto.categoryId, dto.categoryKey,
                dto.categoryName, dto.categoryPosition, dto.collectionKey, dto.collections,
                dto.productPosition, dto.cardFeaturedProductId, dto.tags,
                dto.websiteStatus, dto.orderAppStatus,
                dto.catalogueStatus, dto.active, dto.name, dto.summary, dto.description,
                dto.format, dto.highlights, dto.seoTitle, dto.seoDescription, dto.dimensions,
                dto.texts, dto.packages, dto.images, dto.externalIdentifiers,
                dto.priceObservations, dto.provenance, dto.conflicts, List.of(),
                publicationIssues(family, variantCount, json), variantCount);
    }

    public static List<String> publicationIssues(
            ProductFamilyEntity family, List<ProductEntity> memberRows, ObjectMapper json) {
        List<ProductEntity> members = memberRows == null ? List.of() : memberRows;
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
        else if (family.photos.stream().noneMatch(photo -> publicPhoto(photo, members, json))) {
            issues.add("Minstens één publiceerbare foto met afmetingen, alt-tekst "
                    + "en actieve variantkoppeling is verplicht");
        }
        if (members.stream().noneMatch(member -> member.active)) {
            issues.add("Minstens één actieve variant is verplicht");
        }
        if (members.stream().anyMatch(member -> member.active
                && !blank(member.colour) && blank(member.colourHex))) {
            issues.add("Kleurstaal ontbreekt voor een actieve gekleurde variant");
        }
        if (FamilyVariantRules.hasDuplicateOptions(family, members)) {
            issues.add(FamilyVariantRules.OPTION_ISSUE);
        }
        if (FamilyVariantRules.hasInvalidPositions(members)) {
            issues.add(FamilyVariantRules.POSITION_ISSUE);
        }
        if (family.cardFeaturedProductId != null) {
            ProductEntity featured = members.stream().filter(member -> member.active
                            && java.util.Objects.equals(member.id, family.cardFeaturedProductId))
                    .findFirst().orElse(null);
            if (featured == null) {
                issues.add("Uitgelicht kaartproduct is geen actieve familievariant");
            } else if (family.photos.stream().noneMatch(photo ->
                    photoForVariant(photo, featured, members, json))) {
                issues.add("Uitgelicht kaartproduct heeft geen eigen of familiebrede publieke foto");
            }
        }
        return List.copyOf(issues);
    }

    private static boolean publicPhoto(
            ProductFamilyPhotoEntity photo, List<ProductEntity> members, ObjectMapper json) {
        if (!FamilyPhotoPublicationPolicy.hasPublicMetadata(photo, json)) return false;
        ProductEntity resolved = FamilyPhotoVariantResolver.resolvePhoto(photo, members);
        return FamilyPhotoVariantResolver.familyWide(photo)
                || resolved != null && resolved.active;
    }

    private static boolean photoForVariant(
            ProductFamilyPhotoEntity photo, ProductEntity product,
            List<ProductEntity> members, ObjectMapper json) {
        if (!FamilyPhotoPublicationPolicy.hasPublicMetadata(photo, json)) return false;
        if (FamilyPhotoVariantResolver.familyWide(photo)) return true;
        ProductEntity resolved = FamilyPhotoVariantResolver.resolvePhoto(photo, members);
        return resolved != null && resolved.active
                && java.util.Objects.equals(resolved.id, product.id);
    }

    /** Compatibility check for callers that only know the member count. */
    static List<String> publicationIssues(
            ProductFamilyEntity family, long variantCount, ObjectMapper json) {
        List<String> issues = new ArrayList<>(publicationIssues(family, List.of(), json));
        issues.remove("Minstens één actieve variant is verplicht");
        issues.remove("Kleurstaal ontbreekt voor een actieve gekleurde variant");
        issues.remove(FamilyVariantRules.OPTION_ISSUE);
        issues.remove(FamilyVariantRules.POSITION_ISSUE);
        issues.remove("Uitgelicht kaartproduct is geen actieve familievariant");
        issues.remove("Uitgelicht kaartproduct heeft geen eigen of familiebrede publieke foto");
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
