package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Exact versioned contract produced by the source-audited catalogue generator. */
public record CanonicalCatalogManifest(
        String schemaVersion,
        ImportDescriptor importDescriptor,
        List<CategoryManifest> categories,
        List<FamilyManifest> families,
        ValidationSummary validationSummary
) {
    public record ImportDescriptor(
            String importKey,
            Instant generatedAt,
            String transformVersion,
            String sourceDigest,
            String payloadSha256,
            List<String> sourcePriority,
            List<SourceDescriptor> sources
    ) {}

    public record SourceDescriptor(String sourceType, String filename, String sha256) {}

    public record ValidationSummary(
            Integer familyCount,
            Integer websitePublishedFamilyCount,
            Integer reviewFamilyCount,
            Integer odooOnlyFamilyCount,
            Integer variantCount,
            Integer logicalImageCount,
            Integer imageRenditionCount,
            Integer uniqueRenditionBlobCount,
            Integer shopifyAltTextImages,
            Integer generatedFallbackAltTextImages,
            Integer odooRows,
            Integer excludedNonProductOdooRows,
            Integer conflicts,
            Integer inventoryKnownVariants,
            Integer inventoryUnknownVariants,
            Integer inventoryKnownStockTotal,
            Integer shopifyProductIdentifiers,
            Integer shopifyVariantIdentifiers,
            Integer packagingGtinCandidates,
            Integer pdfDimensionObservations,
            Integer pdfPackageObservations,
            Map<String, Integer> priceObservationCounts
    ) {}

    public record CategoryManifest(
            String key,
            String name,
            String eyebrow,
            String description,
            Integer position
    ) {}

    public record FamilyManifest(
            String canonicalFamilyKey,
            String publicHandle,
            Boolean active,
            String cardFeaturedCanonicalVariantKey,
            CategoryManifest category,
            List<CollectionManifest> collections,
            Integer productPosition,
            List<String> tags,
            RequestedPublication requestedPublication,
            List<FamilyTextManifest> texts,
            List<DimensionObservationManifest> dimensions,
            List<PackageManifest> packages,
            List<ImageManifest> images,
            List<VariantManifest> variants,
            List<ExternalIdentifierManifest> externalIdentifiers,
            List<PriceObservationManifest> priceObservations,
            List<ProvenanceManifest> provenance,
            List<ConflictManifest> conflicts
    ) {}

    public record FamilyTextManifest(
            Language language,
            String name,
            String summary,
            String description,
            String format,
            List<String> highlights,
            String seoTitle,
            String seoDescription
    ) {}

    public record VariantManifest(
            String canonicalVariantKey,
            String sku,
            String skuProvenance,
            String sourceSku,
            String name,
            String color,
            String size,
            String colourHex,
            Integer position,
            Boolean active,
            Boolean inventoryKnown,
            Integer stockQuantity,
            Boolean publicAvailability,
            String barcode,
            List<ExternalIdentifierManifest> externalIdentifiers,
            List<PriceObservationManifest> priceObservations,
            List<ProvenanceManifest> provenance,
            List<PackageManifest> packages
    ) {}

    public record RequestedPublication(
            PublicationState websiteStatus,
            PublicationState orderAppStatus,
            PublicationState catalogueStatus
    ) {}

    public record CollectionManifest(
            String key,
            String name,
            String eyebrow,
            String description,
            Integer position,
            Boolean primary,
            String mobileName,
            String featuredCanonicalVariantKey
    ) {}

    /** Values remain ordered because many source documents do not confirm axis meaning. */
    public record DimensionObservationManifest(
            String dimensionType,
            List<BigDecimal> values,
            String unit,
            String rawValue,
            Boolean axisMeaningConfirmed,
            String sourceType,
            String sourceLocation,
            Boolean operational,
            String confidence
    ) {}

    public record PackageManifest(
            String packageType,
            String variantCanonicalKey,
            DimensionValueManifest dimensions,
            Integer piecesPerPackage,
            String sourceType,
            String sourceLocation,
            Boolean operational,
            String confidence
    ) {}

    public record DimensionValueManifest(
            List<BigDecimal> values,
            String unit,
            String rawValue,
            Boolean axisMeaningConfirmed
    ) {}

    public record ImageManifest(
            String sourceId,
            String sourceUrl,
            Integer sourceWidth,
            Integer sourceHeight,
            String filename,
            String contentType,
            Integer position,
            String altText,
            String altTextSource,
            String variantCanonicalKey,
            String variantColor,
            ImageRenditionManifest small,
            ImageRenditionManifest large
    ) {}

    public record ImageRenditionManifest(
            String sha256,
            Integer width,
            Integer height,
            String localSourcePath,
            String bytesBase64
    ) {}

    public record ExternalIdentifierManifest(
            String source,
            String identifierType,
            String value,
            Boolean confirmed
    ) {}

    /** Null currency/tax is intentionally preserved when the source did not state it. */
    public record PriceObservationManifest(
            String priceType,
            BigDecimal amount,
            String currency,
            String taxContext,
            String incoterm,
            String market,
            String rawText,
            String sourceLocation
    ) {}

    public record ProvenanceManifest(
            String sourceType,
            String sourceLocation,
            String fieldPath,
            /** Exact JSON scalar/object/array from the one-time source audit; never String-coerced. */
            JsonNode sourceValue,
            String confidence
    ) {}

    public record ConflictManifest(
            String code,
            String message,
            String severity,
            String confidence,
            List<String> relatedSourceRecords,
            String status
    ) {}
}
