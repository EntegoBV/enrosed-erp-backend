package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.*;
import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest.*;
import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.sales.adapter.out.persistence.SalesDaos;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.PanacheSourcingRepositories;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates and atomically applies the one-time, source-audited canonical catalogue manifest.
 * Historical source names are kept only as read-only provenance; runtime catalogue reads never
 * call Shopify, Odoo, the old website data, or the PDF.
 */
@ApplicationScoped
public class CatalogMigrationService {
    private static final String SCHEMA_VERSION = "1.0";
    public static final String FULL_RESET_CONFIRMATION = "RESET-ALL-ENROSED-DATA";
    private static final String FAMILY_OWNER = "FAMILY";
    private static final String VARIANT_OWNER = "VARIANT";
    private static final List<String> APPLICATION_TABLES = List.of(
            "quote_revision_line", "quote_revision", "quote_event",
            "sales_pallet_item", "sales_pallet", "sales_order_line", "sales_order",
            "purchase_order_line", "purchase_order",
            "product_text", "product_photo", "product_family_collection",
            "product_family_text", "product_family_photo", "product_package",
            "product_external_identifier", "product_price_observation",
            "product_provenance", "product_dimension_observation", "catalog_import_conflict",
            "product", "product_family", "photo_blob", "product_collection", "category_text",
            "category", "hs_code", "customer", "country", "discount_tier", "supplier",
            "freight_rate", "market_source_state", "company_profile",
            "content_translation_text", "content_translation",
            "catalog_localization_backfill", "website_rebuild", "catalog_import_batch");

    private final CanonicalCatalogDaos.Families families;
    private final CanonicalCatalogDaos.Collections collections;
    private final CanonicalCatalogDaos.Packages packages;
    private final CanonicalCatalogDaos.DimensionObservations dimensionObservations;
    private final CanonicalCatalogDaos.ExternalIdentifiers externalIdentifiers;
    private final CanonicalCatalogDaos.PriceObservations priceObservations;
    private final CanonicalCatalogDaos.Provenance provenance;
    private final CanonicalCatalogDaos.ImportBatches importBatches;
    private final CanonicalCatalogDaos.ImportConflicts conflicts;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final PanacheSourcingRepositories.PurchaseOrderDao purchaseOrders;
    private final PanacheSourcingRepositories.SupplierDao suppliers;
    private final SalesDaos.Orders salesOrders;
    private final SalesDaos.Revisions revisions;
    private final SalesDaos.Events events;
    private final PhotoStorage photoStorage;
    private final PhotoReferenceService photoReferences;
    private final BarcodeValidator barcodeValidator;
    private final FeaturedProductSelectionService featuredProducts;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final PublicContentSeedLoader publicContent;
    private final WebsiteRebuildService websiteRebuild;
    private final CatalogMutationLock mutationLock;

    public CatalogMigrationService(
            CanonicalCatalogDaos.Families families,
            CanonicalCatalogDaos.Collections collections,
            CanonicalCatalogDaos.Packages packages,
            CanonicalCatalogDaos.DimensionObservations dimensionObservations,
            CanonicalCatalogDaos.ExternalIdentifiers externalIdentifiers,
            CanonicalCatalogDaos.PriceObservations priceObservations,
            CanonicalCatalogDaos.Provenance provenance,
            CanonicalCatalogDaos.ImportBatches importBatches,
            CanonicalCatalogDaos.ImportConflicts conflicts,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            PanacheSourcingRepositories.PurchaseOrderDao purchaseOrders,
            PanacheSourcingRepositories.SupplierDao suppliers,
            SalesDaos.Orders salesOrders,
            SalesDaos.Revisions revisions,
            SalesDaos.Events events,
            PhotoStorage photoStorage,
            PhotoReferenceService photoReferences,
            BarcodeValidator barcodeValidator,
            FeaturedProductSelectionService featuredProducts,
            ObjectMapper json,
            EntityManager entityManager,
            PublicContentSeedLoader publicContent,
            WebsiteRebuildService websiteRebuild,
            CatalogMutationLock mutationLock) {
        this.families = families;
        this.collections = collections;
        this.packages = packages;
        this.dimensionObservations = dimensionObservations;
        this.externalIdentifiers = externalIdentifiers;
        this.priceObservations = priceObservations;
        this.provenance = provenance;
        this.importBatches = importBatches;
        this.conflicts = conflicts;
        this.products = products;
        this.categories = categories;
        this.purchaseOrders = purchaseOrders;
        this.suppliers = suppliers;
        this.salesOrders = salesOrders;
        this.revisions = revisions;
        this.events = events;
        this.photoStorage = photoStorage;
        this.photoReferences = photoReferences;
        this.barcodeValidator = barcodeValidator;
        this.featuredProducts = featuredProducts;
        this.json = json;
        this.entityManager = entityManager;
        this.publicContent = publicContent;
        this.websiteRebuild = websiteRebuild;
        this.mutationLock = mutationLock;
    }

    public CatalogMigrationPreflight preflight(CanonicalCatalogManifest manifest) {
        return preflight(manifest, null);
    }

    public CatalogMigrationPreflight preflight(
            CanonicalCatalogManifest manifest, String verifiedPayloadSha256) {
        Validation validation = validate(manifest);
        if (verifiedPayloadSha256 != null && (manifest == null || manifest.importDescriptor() == null
                || !verifiedPayloadSha256.equals(manifest.importDescriptor().payloadSha256()))) {
            validation.problems.add("Geverifieerde payload hash komt niet overeen met importDescriptor");
        }
        ReferenceCounts references = referenceCounts(existingProductIds());
        return new CatalogMigrationPreflight(
                validation.problems.isEmpty(), List.copyOf(validation.problems),
                List.copyOf(validation.warnings), validation.familyCount,
                validation.variantCount, validation.imageCount, products.count(),
                references.purchaseOrderLines, references.salesOrderLines,
                references.salesPalletItems, references.quoteRevisionLines,
                references.total() > 0, applicationRowCounts(), FULL_RESET_CONFIRMATION);
    }

    @Transactional
    public CatalogMigrationResult apply(CatalogMigrationApplyRequest request) {
        return apply(request, null);
    }

    @Transactional
    public CatalogMigrationResult apply(
            CatalogMigrationApplyRequest request, String verifiedPayloadSha256) {
        if (request == null || request.manifest() == null) {
            throw new BusinessRuleException("Geen canoniek catalogusmanifest meegestuurd");
        }
        CanonicalCatalogManifest manifest = request.manifest();
        Validation validation = validate(manifest);
        if (!validation.problems.isEmpty()) {
            throw new BusinessRuleException("Catalogusmanifest is ongeldig: "
                    + String.join("; ", validation.problems));
        }

        String importKey = required(manifest.importDescriptor().importKey());
        String payloadSha256 = manifest.importDescriptor().payloadSha256();
        String contentDigest = computePayloadSha256(manifest);
        if (request.fullReset() && (!request.replaceExistingProducts()
                || !FULL_RESET_CONFIRMATION.equals(request.confirmation()))) {
            throw new BusinessRuleException("Volledige reset vereist replaceExistingProducts=true "
                    + "en exact bevestigingsteken " + FULL_RESET_CONFIRMATION);
        }
        if (request.fullReset() && !payloadSha256.equals(verifiedPayloadSha256)) {
            throw new BusinessRuleException("Volledige reset vereist een byte-voor-byte "
                    + "geverifieerde payloadSha256");
        }
        /* Global order for migration/startup work is advisory transaction lock first, then
           family/product/category rows. Ordinary editors never take this advisory lock. */
        mutationLock.acquire();
        CatalogImportBatchEntity prior = importBatches.find("importKey", importKey).firstResult();
        if (prior != null && !request.fullReset()) {
            if ("APPLIED".equals(prior.status)
                    && payloadSha256.equals(prior.payloadSha256)
                    && contentDigest.equals(prior.contentDigest)) {
                return new CatalogMigrationResult(
                        importKey, true, prior.familyCount, prior.variantCount, prior.imageCount,
                        0, 0, 0, 0, prior.conflictCount,
                        request.fullReset(), Map.of(), validation.warnings);
            }
            throw new BusinessRuleException("Importcode " + importKey
                    + " bestaat al met andere brondata of een onvolledige status");
        }

        ReferenceCounts references = referenceCounts(existingProductIds());
        if (!request.fullReset() && request.replaceExistingProducts()
                && references.total() > 0 && !request.deleteReferencingTestGraphs()) {
            throw new BusinessRuleException("Bestaande producten worden nog gebruikt in "
                    + references.total() + " order-, pallet- of revisieregels; kies expliciet "
                    + "voor het verwijderen van de testgrafen of voor een volledige reset");
        }
        if (!request.fullReset() && !request.replaceExistingProducts()
                && (products.count() > 0 || families.count() > 0)) {
            throw new BusinessRuleException("Er bestaan al producten. Gebruik productvervanging, "
                    + "een volledige reset, of herhaal exact dezelfde importcode");
        }

        int deletedSalesOrders = 0;
        int deletedPurchaseOrders = 0;
        int deletedProducts = Math.toIntExact(products.count());
        Map<String, Long> clearedRows = new LinkedHashMap<>();
        Set<String> obsoletePhotoKeys = new LinkedHashSet<>();

        if (request.fullReset()) {
            clearedRows.putAll(fullReset());
            deletedSalesOrders = Math.toIntExact(clearedRows.getOrDefault("sales_order", 0L));
            deletedPurchaseOrders = Math.toIntExact(clearedRows.getOrDefault("purchase_order", 0L));
            entityManager.clear();
        } else if (request.replaceExistingProducts()) {
            if (references.total() > 0) {
                GraphDeleteCounts graphCounts = deleteReferencingGraphs(existingProductIds());
                deletedSalesOrders = graphCounts.salesOrders;
                deletedPurchaseOrders = graphCounts.purchaseOrders;
            }
            obsoletePhotoKeys.addAll(allProductPhotoKeys());
            clearProductDomain();
            entityManager.flush();
            for (String key : obsoletePhotoKeys) photoReferences.deleteIfUnreferenced(key);
        }

        CatalogImportBatchEntity batch = new CatalogImportBatchEntity();
        batch.importKey = importKey;
        batch.sourceDigest = manifest.importDescriptor().sourceDigest();
        batch.payloadSha256 = payloadSha256;
        batch.contentDigest = contentDigest;
        batch.transformVersion = manifest.importDescriptor().transformVersion();
        batch.status = "APPLYING";
        batch.generatedAt = manifest.importDescriptor().generatedAt();
        importBatches.persist(batch);
        importBatches.flush();

        Map<String, CategoryEntity> categoryByKey = applyCategories(manifest.categories());
        ImportStats stats = new ImportStats();
        for (FamilyManifest input : safe(manifest.families())) {
            applyFamily(input, manifest, batch, categoryByKey, stats);
        }
        resolveFeaturedSelections(manifest);

        batch.status = "APPLIED";
        batch.appliedAt = Instant.now();
        batch.familyCount = stats.families;
        batch.variantCount = stats.variants;
        batch.imageCount = stats.images;
        batch.conflictCount = stats.conflicts;
        entityManager.flush();
        publicContent.ensureSeeded();
        websiteRebuild.queue();

        return new CatalogMigrationResult(
                importKey, false, stats.families, stats.variants, stats.images,
                stats.reusedBlobs, deletedProducts, deletedSalesOrders,
                deletedPurchaseOrders, stats.conflicts, request.fullReset(),
                Collections.unmodifiableMap(clearedRows), validation.warnings);
    }

    private void applyFamily(
            FamilyManifest input,
            CanonicalCatalogManifest manifest,
            CatalogImportBatchEntity batch,
            Map<String, CategoryEntity> categoryByKey,
            ImportStats stats) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = input.canonicalFamilyKey();
        family.publicHandle = input.publicHandle();
        family.active = Boolean.TRUE.equals(input.active());
        family.productPosition = integer(input.productPosition());
        family.tagsJson = write(safe(input.tags()));
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        family.createdAt = Instant.now();
        family.updatedAt = family.createdAt;
        family.lastImportKey = batch.importKey;

        CategoryManifest categoryInput = input.category();
        if (categoryInput != null) {
            CategoryEntity category = categoryByKey.get(categoryInput.key());
            family.categoryId = category == null ? null : category.id;
            family.categoryKey = categoryInput.key();
            family.categoryName = categoryInput.name();
            family.categoryPosition = integer(categoryInput.position());
        }

        family.name = textValue(input.texts(), FamilyTextManifest::name);
        family.summary = textValue(input.texts(), FamilyTextManifest::summary);
        family.description = textValue(input.texts(), FamilyTextManifest::description);
        family.format = textValue(input.texts(), FamilyTextManifest::format);
        family.highlightsJson = write(textList(input.texts(), FamilyTextManifest::highlights));
        family.seoTitle = textValue(input.texts(), FamilyTextManifest::seoTitle);
        family.seoDescription = textValue(input.texts(), FamilyTextManifest::seoDescription);
        applyOperationalDimensions(family, input.dimensions());
        applyFamilyTexts(family, input.texts());
        applyCollections(family, input);
        families.persist(family);
        families.flush();

        persistDimensions(family, input.dimensions());
        persistExternalIdentifiers(FAMILY_OWNER, family.familyKey, family.id, null,
                input.externalIdentifiers());
        persistPrices(FAMILY_OWNER, family.familyKey, family.id, null,
                input.priceObservations(), manifest.importDescriptor().generatedAt());
        persistProvenance(FAMILY_OWNER, family.familyKey, family.id, null,
                input.provenance(), batch.importKey, manifest.importDescriptor().generatedAt());

        Map<String, ProductEntity> variantByKey = new LinkedHashMap<>();
        for (VariantManifest variantInput : safe(input.variants())) {
            ProductEntity variant = applyVariant(family, variantInput, input, manifest, batch);
            variantByKey.put(variantInput.canonicalVariantKey(), variant);
            stats.variants++;
        }

        persistPackages(family, null, input.packages(), variantByKey);
        for (VariantManifest variantInput : safe(input.variants())) {
            ProductEntity variant = variantByKey.get(variantInput.canonicalVariantKey());
            persistPackages(family, variant, variantInput.packages(), variantByKey);
        }

        List<ProductFamilyPhotoEntity> familyPhotos = new ArrayList<>();
        for (ImageManifest image : safe(input.images())) {
            ProductFamilyPhotoEntity stored = persistImage(family, image, variantByKey, stats);
            familyPhotos.add(stored);
            persistImageAltProvenance(family, image, batch.importKey,
                    manifest.importDescriptor().generatedAt());
            stats.images++;
        }
        attachEffectiveProductPhotos(variantByKey, familyPhotos);

        for (ConflictManifest conflict : safe(input.conflicts())) {
            persistConflict(batch.id, family.familyKey, conflict);
            stats.conflicts++;
        }

        RequestedPublication requested = input.requestedPublication();
        family.websiteStatus = requested == null || requested.websiteStatus() == null
                ? PublicationState.DRAFT : requested.websiteStatus();
        family.orderAppStatus = requested == null || requested.orderAppStatus() == null
                ? PublicationState.DRAFT : requested.orderAppStatus();
        family.catalogueStatus = requested == null || requested.catalogueStatus() == null
                ? PublicationState.DRAFT : requested.catalogueStatus();
        family.updatedAt = Instant.now();
        stats.families++;
    }

    private ProductEntity applyVariant(
            ProductFamilyEntity family,
            VariantManifest input,
            FamilyManifest familyInput,
            CanonicalCatalogManifest manifest,
            CatalogImportBatchEntity batch) {
        ProductEntity product = new ProductEntity();
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.canonicalVariantKey = input.canonicalVariantKey();
        product.canonicalBarcode = optional(input.barcode());
        product.variantPosition = integer(input.position());
        product.inventoryKnown = Boolean.TRUE.equals(input.inventoryKnown());
        product.publicAvailability = input.publicAvailability();
        product.sku = optional(input.sku()) == null
                ? generatedSku(input.canonicalVariantKey()) : input.sku().strip();
        product.name = family.name;
        product.publicName = product.name;
        product.colour = optional(input.color());
        product.variantSize = optional(input.size());
        product.colourHex = optional(input.colourHex());
        product.description = family.description;
        product.categoryId = family.categoryId;
        product.supplierId = null;
        product.active = Boolean.TRUE.equals(input.active());
        product.publicHandle = null;
        product.websiteStatus = PublicationState.DRAFT;
        product.orderAppStatus = PublicationState.DRAFT;
        product.stockQuantity = product.inventoryKnown && input.stockQuantity() != null
                ? input.stockQuantity() : 0;
        product.piecesPerCarton = 1;
        product.exwCurrency = null;
        applyProductDimensions(product, familyInput.dimensions());
        applyOperationalCarton(product, familyInput.packages(), input.packages(), input.canonicalVariantKey());
        applyVariantTexts(product, familyInput.texts(), input);
        products.persist(product);
        products.flush();

        persistExternalIdentifiers(VARIANT_OWNER, input.canonicalVariantKey(), family.id, product.id,
                input.externalIdentifiers());
        persistPrices(VARIANT_OWNER, input.canonicalVariantKey(), family.id, product.id,
                input.priceObservations(), manifest.importDescriptor().generatedAt());
        persistProvenance(VARIANT_OWNER, input.canonicalVariantKey(), family.id, product.id,
                input.provenance(), batch.importKey, manifest.importDescriptor().generatedAt());
        persistSkuIdentity(family, product, input, manifest, batch);
        return product;
    }

    /** Keeps generated-vs-source identity explicit even when the manifest supplies the generated SKU. */
    private void persistSkuIdentity(
            ProductFamilyEntity family, ProductEntity product, VariantManifest input,
            CanonicalCatalogManifest manifest, CatalogImportBatchEntity batch) {
        String skuProvenance = optional(input.skuProvenance()) == null
                ? "UNSPECIFIED" : input.skuProvenance().strip();
        persistSkuProvenance(family, product, input, batch, manifest,
                "sku", product.sku, skuProvenance);
        persistSkuProvenance(family, product, input, batch, manifest,
                "skuProvenance", skuProvenance, skuProvenance);
        persistSkuProvenance(family, product, input, batch, manifest,
                "sourceSku", input.sourceSku(), skuProvenance);
    }

    private void persistSkuProvenance(
            ProductFamilyEntity family, ProductEntity product, VariantManifest input,
            CatalogImportBatchEntity batch, CanonicalCatalogManifest manifest,
            String field, Object rawValue, String source) {
        ProductProvenanceEntity item = new ProductProvenanceEntity();
        item.ownerType = VARIANT_OWNER;
        item.ownerKey = input.canonicalVariantKey();
        item.familyId = family.id;
        item.productId = product.id;
        item.fieldName = field;
        item.source = source;
        item.rawValue = write(rawValue);
        item.confidence = "HIGH";
        item.status = "GENERATED_INTERNAL".equals(source) ? "CANONICAL_GENERATED" : "OBSERVED";
        item.observedAt = manifest.importDescriptor().generatedAt();
        item.importKey = batch.importKey;
        provenance.persist(item);
    }

    private ProductFamilyPhotoEntity persistImage(
            ProductFamilyEntity family,
            ImageManifest input,
            Map<String, ProductEntity> variantByKey,
            ImportStats stats) {
        StoredRendition small = storeRendition(input.filename(), input.contentType(), input.small());
        StoredRendition large = storeRendition(input.filename(), input.contentType(), input.large());
        if (small.reused) stats.reusedBlobs++;
        if (large.reused) stats.reusedBlobs++;

        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = input.sourceId();
        photo.sourceAssetId = input.sourceId();
        photo.sourceUrl = input.sourceUrl();
        photo.originalFilename = input.filename();
        photo.originalWidthPx = input.sourceWidth();
        photo.originalHeightPx = input.sourceHeight();
        photo.smallStorageKey = small.storageKey;
        photo.smallContentType = input.contentType();
        photo.smallSha256 = input.small().sha256();
        photo.smallSizeBytes = small.sizeBytes;
        photo.smallWidthPx = input.small().width();
        photo.smallHeightPx = input.small().height();
        photo.largeStorageKey = large.storageKey;
        photo.largeContentType = input.contentType();
        photo.largeSha256 = input.large().sha256();
        photo.largeSizeBytes = large.sizeBytes;
        photo.largeWidthPx = input.large().width();
        photo.largeHeightPx = input.large().height();
        photo.position = integer(input.position());
        photo.variantProduct = input.variantCanonicalKey() == null
                ? null : variantByKey.get(input.variantCanonicalKey());
        photo.variantExternalId = optional(input.variantCanonicalKey());
        photo.variantColor = optional(input.variantColor());
        photo.altTextSource = optional(input.altTextSource());
        photo.altTextsJson = write(List.of(
                new ProductFamilyDto.AltTextDto(Language.EN, input.altText().strip())));
        entityManager.persist(photo);
        family.photos.add(photo);
        return photo;
    }

    private void attachEffectiveProductPhotos(
            Map<String, ProductEntity> variants,
            List<ProductFamilyPhotoEntity> images) {
        for (Map.Entry<String, ProductEntity> entry : variants.entrySet()) {
            List<ProductFamilyPhotoEntity> ordered = images.stream()
                    .filter(image -> imageRank(image, entry.getValue()) < 2)
                    .sorted(Comparator
                            .comparingInt((ProductFamilyPhotoEntity image) ->
                                    imageRank(image, entry.getValue()))
                            .thenComparingInt(image -> image.position))
                    .toList();
            int position = 0;
            for (ProductFamilyPhotoEntity familyPhoto : ordered) {
                ProductPhotoEntity productPhoto = new ProductPhotoEntity();
                productPhoto.product = entry.getValue();
                productPhoto.storageKey = familyPhoto.largeStorageKey;
                productPhoto.originalFilename = familyPhoto.originalFilename;
                productPhoto.contentType = familyPhoto.largeContentType;
                productPhoto.sizeBytes = familyPhoto.largeSizeBytes;
                productPhoto.widthPx = familyPhoto.largeWidthPx;
                productPhoto.heightPx = familyPhoto.largeHeightPx;
                productPhoto.position = position++;
                productPhoto.familyPhotoId = familyPhoto.id;
                entityManager.persist(productPhoto);
                entry.getValue().photos.add(productPhoto);
            }
        }
    }

    private static int imageRank(ProductFamilyPhotoEntity image, ProductEntity variant) {
        if (image.variantProduct != null && Objects.equals(image.variantProduct.id, variant.id)) return 0;
        if (Objects.equals(image.variantExternalId, variant.canonicalVariantKey)) return 0;
        if (image.variantProduct == null && image.variantExternalId == null
                && image.variantColor == null) return 1;
        return 2;
    }

    private StoredRendition storeRendition(
            String filename, String contentType, ImageRenditionManifest rendition) {
        byte[] bytes = decode(rendition.bytesBase64());
        String storageKey = "sha256-" + rendition.sha256() + extension(contentType);
        if (photoStorage.exists(storageKey)) {
            try (InputStream existing = photoStorage.read(storageKey)) {
                if (!rendition.sha256().equals(sha256(existing.readAllBytes()))) {
                    throw new BusinessRuleException("Bestaande fotoblob " + storageKey
                            + " heeft niet de verwachte checksum");
                }
            } catch (BusinessRuleException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new BusinessRuleException("Bestaande fotoblob " + storageKey
                        + " kon niet worden gecontroleerd");
            }
            return new StoredRendition(storageKey, bytes.length, true);
        }
        photoStorage.storeKnown(storageKey, filename, contentType, bytes);
        return new StoredRendition(storageKey, bytes.length, false);
    }

    private void applyFamilyTexts(ProductFamilyEntity family, List<FamilyTextManifest> inputs) {
        for (FamilyTextManifest input : safe(inputs)) {
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = input.language();
            text.name = optional(input.name());
            text.summary = optional(input.summary());
            text.description = optional(input.description());
            text.format = optional(input.format());
            text.highlightsJson = write(safe(input.highlights()));
            text.seoTitle = optional(input.seoTitle());
            text.seoDescription = optional(input.seoDescription());
            family.texts.add(text);
        }
    }

    private void applyVariantTexts(
            ProductEntity product, List<FamilyTextManifest> familyTexts, VariantManifest variant) {
        for (FamilyTextManifest input : safe(familyTexts)) {
            ProductTextEntity text = new ProductTextEntity();
            text.product = product;
            text.language = input.language();
            text.name = optional(input.name());
            text.publicName = text.name;
            text.description = optional(input.description());
            text.colour = input.language() == Language.EN ? optional(variant.color()) : null;
            text.variantSize = input.language() == Language.EN ? optional(variant.size()) : null;
            product.texts.add(text);
        }
    }

    private void applyCollections(ProductFamilyEntity family, FamilyManifest input) {
        List<CollectionManifest> requested = safe(input.collections());
        if (requested.isEmpty() && input.category() != null) {
            CategoryManifest category = input.category();
            requested = List.of(new CollectionManifest(
                    category.key(), category.name(), category.eyebrow(), category.description(),
                    category.position(), true, null, null));
        }
        for (CollectionManifest item : requested) {
            ProductCollectionEntity collection = collections.find("collectionKey", item.key()).firstResult();
            if (collection == null) {
                collection = new ProductCollectionEntity();
                collection.collectionKey = item.key();
                collections.persist(collection);
            }
            collection.name = item.name();
            collection.eyebrow = item.eyebrow();
            collection.description = item.description();
            collection.position = integer(item.position());
            collection.mobileName = optional(item.mobileName());
            ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
            membership.family = family;
            membership.collection = collection;
            membership.position = integer(item.position());
            membership.primaryCollection = Boolean.TRUE.equals(item.primary());
            family.collections.add(membership);
        }
        family.collectionKey = family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst()
                .map(item -> item.collection.collectionKey).orElse(null);
    }

    /** Resolve portable canonical keys only after every family and SKU has been persisted. */
    private void resolveFeaturedSelections(CanonicalCatalogManifest manifest) {
        for (FamilyManifest input : safe(manifest.families())) {
            ProductFamilyEntity family = families.find(
                    "familyKey", input.canonicalFamilyKey()).firstResult();
            String familyFeaturedKey = optional(input.cardFeaturedCanonicalVariantKey());
            family.cardFeaturedProductId = null;
            if (familyFeaturedKey != null) {
                ProductEntity product = productByCanonicalKey(familyFeaturedKey);
                featuredProducts.requireFamilyMember(family, product.id);
                family.cardFeaturedProductId = product.id;
            }

            for (CollectionManifest inputCollection : safe(input.collections())) {
                ProductCollectionEntity collection = collections.find(
                        "collectionKey", inputCollection.key()).firstResult();
                if (collection == null) continue;
                collection.mobileName = optional(inputCollection.mobileName());
                String featuredKey = optional(inputCollection.featuredCanonicalVariantKey());
                ProductEntity featuredProduct = null;
                collection.featuredProductId = null;
                if (featuredKey != null) {
                    featuredProduct = productByCanonicalKey(featuredKey);
                    featuredProducts.requireCollectionMember(collection, featuredProduct.id);
                    collection.featuredProductId = featuredProduct.id;
                }
                CategoryEntity category = categories.find("code", collection.collectionKey).firstResult();
                if (category != null) {
                    category.mobileName = collection.mobileName;
                    category.featuredProductId = null;
                    if (featuredProduct != null) {
                        featuredProducts.requireCategoryMember(
                                category.id, category.code, featuredProduct.id);
                        category.featuredProductId = featuredProduct.id;
                    }
                }
            }
        }
    }

    private ProductEntity productByCanonicalKey(String canonicalVariantKey) {
        ProductEntity product = products.find(
                "canonicalVariantKey", canonicalVariantKey).firstResult();
        if (product == null) {
            throw new BusinessRuleException(
                    "Onbekende canonieke uitgelichte variant " + canonicalVariantKey);
        }
        return product;
    }

    private Map<String, CategoryEntity> applyCategories(List<CategoryManifest> inputs) {
        Map<String, CategoryEntity> result = new LinkedHashMap<>();
        for (CategoryManifest input : safe(inputs)) {
            CategoryEntity category = categories.find("code", input.key()).firstResult();
            if (category == null) {
                category = new CategoryEntity();
                category.code = input.key();
                categories.persist(category);
            }
            category.name = input.name();
            category.description = input.description();
            category.eyebrow = input.eyebrow();
            category.position = integer(input.position());
            result.put(input.key(), category);
        }
        categories.flush();
        return result;
    }

    private void applyOperationalDimensions(
            ProductFamilyEntity family, List<DimensionObservationManifest> inputs) {
        DimensionObservationManifest dimension = operationalDimension(inputs);
        if (dimension == null) return;
        family.dimensionUnit = optional(dimension.unit());
        family.dimensionRaw = optional(dimension.rawValue());
        /*
         * The canonical observation retains axisMeaningConfirmed=false when the source merely
         * prints an ordered "a x b x c" value. The legacy family/product columns cannot retain
         * that nuance, so their compatibility projection deliberately follows the source order;
         * no missing axis or unit is inferred.
         */
        List<BigDecimal> values = safe(dimension.values());
        family.dimensionLength = value(values, 0);
        family.dimensionWidth = value(values, 1);
        family.dimensionHeight = value(values, 2);
    }

    private void applyProductDimensions(
            ProductEntity product, List<DimensionObservationManifest> inputs) {
        DimensionObservationManifest dimension = operationalDimension(inputs);
        if (dimension == null || !"cm".equalsIgnoreCase(dimension.unit())) return;
        List<BigDecimal> values = safe(dimension.values());
        product.productLengthCm = value(values, 0);
        product.productWidthCm = value(values, 1);
        product.productHeightCm = value(values, 2);
    }

    private static DimensionObservationManifest operationalDimension(
            List<DimensionObservationManifest> inputs) {
        return safe(inputs).stream()
                .filter(item -> "PRODUCT_DISPLAY".equals(item.dimensionType()))
                .filter(item -> Boolean.TRUE.equals(item.operational()))
                .filter(item -> !safe(item.values()).isEmpty())
                .sorted(Comparator
                        .comparingInt(CatalogMigrationService::dimensionSourceRank)
                        .thenComparingInt(item -> confidenceRank(item.confidence())))
                .findFirst().orElse(null);
    }

    private void applyOperationalCarton(
            ProductEntity product,
            List<PackageManifest> familyPackages,
            List<PackageManifest> variantPackages,
            String variantKey) {
        PackageManifest carton = carton(safe(variantPackages).stream()
                .filter(item -> item.variantCanonicalKey() == null
                        || Objects.equals(item.variantCanonicalKey(), variantKey)).toList());
        if (carton == null) {
            carton = carton(safe(familyPackages).stream()
                    .filter(item -> item.variantCanonicalKey() == null
                            || Objects.equals(item.variantCanonicalKey(), variantKey)).toList());
        }
        if (carton == null || !"cm".equalsIgnoreCase(carton.dimensions().unit())) return;
        List<BigDecimal> values = safe(carton.dimensions().values());
        product.cartonLengthCm = value(values, 0);
        product.cartonWidthCm = value(values, 1);
        product.cartonHeightCm = value(values, 2);
        if (carton.piecesPerPackage() != null && carton.piecesPerPackage() > 0) {
            product.piecesPerCarton = carton.piecesPerPackage();
        }
    }

    private static PackageManifest carton(List<PackageManifest> candidates) {
        return safe(candidates).stream()
                .filter(item -> "OUTER_CARTON".equals(item.packageType()))
                .filter(item -> Boolean.TRUE.equals(item.operational()))
                .filter(item -> item.dimensions() != null
                        && safe(item.dimensions().values()).size() >= 3)
                .sorted(Comparator
                        .comparingInt((PackageManifest item) ->
                                "PDF".equals(item.sourceType()) ? 0 : 1)
                        .thenComparingInt(item -> confidenceRank(item.confidence())))
                .findFirst().orElse(null);
    }

    private static int dimensionSourceRank(DimensionObservationManifest item) {
        return switch (optional(item.sourceType()) == null ? "" : item.sourceType()) {
            case "PDF" -> 0;
            case "WEBSITE_FRONTEND" -> 1;
            case "ODOO_XLSX" -> 2;
            default -> 3;
        };
    }

    private static int confidenceRank(String confidence) {
        return switch (optional(confidence) == null ? "" : confidence.toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    private void persistDimensions(
            ProductFamilyEntity family, List<DimensionObservationManifest> inputs) {
        int position = 0;
        for (DimensionObservationManifest input : safe(inputs)) {
            ProductDimensionObservationEntity item = new ProductDimensionObservationEntity();
            item.familyId = family.id;
            item.sourceKey = stableKey(family.familyKey, "dimension", String.valueOf(position),
                    input.dimensionType(), input.sourceLocation());
            item.position = position++;
            item.dimensionType = input.dimensionType();
            item.valuesJson = write(safe(input.values()));
            item.unit = input.unit();
            item.rawValue = input.rawValue();
            item.axisMeaningConfirmed = input.axisMeaningConfirmed();
            item.sourceType = input.sourceType();
            item.sourceLocation = input.sourceLocation();
            item.operational = input.operational();
            item.confidence = input.confidence();
            dimensionObservations.persist(item);
        }
    }

    private void persistPackages(
            ProductFamilyEntity family,
            ProductEntity product,
            List<PackageManifest> inputs,
            Map<String, ProductEntity> variantByKey) {
        int position = family.packages.size();
        for (PackageManifest input : safe(inputs)) {
            ProductPackageEntity item = new ProductPackageEntity();
            item.family = family;
            ProductEntity owner = product;
            if (owner == null && input.variantCanonicalKey() != null) {
                owner = variantByKey.get(input.variantCanonicalKey());
            }
            item.productId = owner == null ? null : owner.id;
            item.sourceKey = stableKey(family.familyKey, "package", String.valueOf(position),
                    input.packageType(), input.variantCanonicalKey(), input.sourceLocation());
            item.packageType = input.packageType();
            item.position = position++;
            if (input.dimensions() != null) {
                List<BigDecimal> values = safe(input.dimensions().values());
                item.lengthValue = value(values, 0);
                item.widthValue = value(values, 1);
                item.heightValue = value(values, 2);
                item.dimensionUnit = input.dimensions().unit();
                item.rawValue = input.dimensions().rawValue();
                item.axisMeaningConfirmed = input.dimensions().axisMeaningConfirmed();
            }
            item.piecesPerPackage = input.piecesPerPackage();
            item.variantExternalId = input.variantCanonicalKey();
            item.sourceType = input.sourceType();
            item.sourceLocation = input.sourceLocation();
            item.operational = input.operational();
            item.confidence = input.confidence();
            packages.persist(item);
            family.packages.add(item);
        }
    }

    private void persistExternalIdentifiers(
            String ownerType, String ownerKey, Long familyId, Long productId,
            List<ExternalIdentifierManifest> inputs) {
        for (ExternalIdentifierManifest input : safe(inputs)) {
            ProductExternalIdentifierEntity item = new ProductExternalIdentifierEntity();
            item.ownerType = ownerType;
            item.ownerKey = ownerKey;
            item.familyId = familyId;
            item.productId = productId;
            item.source = input.source();
            item.identifierType = input.identifierType();
            item.externalValue = input.value();
            item.confirmed = input.confirmed();
            externalIdentifiers.persist(item);
        }
    }

    private void persistPrices(
            String ownerType, String ownerKey, Long familyId, Long productId,
            List<PriceObservationManifest> inputs, Instant importedAt) {
        int position = 0;
        for (PriceObservationManifest input : safe(inputs)) {
            ProductPriceObservationEntity item = new ProductPriceObservationEntity();
            item.ownerType = ownerType;
            item.ownerKey = ownerKey;
            item.familyId = familyId;
            item.productId = productId;
            item.sourceKey = stableKey(ownerKey, "price", String.valueOf(position++),
                    input.priceType(), input.sourceLocation(), String.valueOf(input.amount()));
            item.context = input.priceType();
            item.amount = input.amount();
            item.currency = input.currency();
            item.taxTreatment = input.taxContext();
            item.incoterm = input.incoterm();
            item.market = input.market();
            item.publicPrice = ("SHOPIFY_RETAIL".equals(input.priceType())
                    || "SHOPIFY_COMPARE_AT".equals(input.priceType()))
                    && "EUR".equals(input.currency());
            item.publicRole = item.publicPrice
                    ? "SHOPIFY_COMPARE_AT".equals(input.priceType()) ? "COMPARE_AT" : "RETAIL"
                    : null;
            item.rawValue = input.rawText();
            item.sourceLocation = input.sourceLocation();
            /* importedAt is ingestion provenance, not a claimed source observation date. */
            item.observedAt = null;
            priceObservations.persist(item);
        }
    }

    private void persistProvenance(
            String ownerType, String ownerKey, Long familyId, Long productId,
            List<ProvenanceManifest> inputs, String importKey, Instant importedAt) {
        for (ProvenanceManifest input : safe(inputs)) {
            ProductProvenanceEntity item = new ProductProvenanceEntity();
            item.ownerType = ownerType;
            item.ownerKey = ownerKey;
            item.familyId = familyId;
            item.productId = productId;
            item.fieldName = input.fieldPath();
            item.source = input.sourceType();
            item.sourceRecordKey = input.sourceLocation();
            item.rawValue = write(input.sourceValue());
            item.confidence = input.confidence();
            item.status = "OBSERVED";
            item.observedAt = importedAt;
            item.importKey = importKey;
            provenance.persist(item);
        }
    }

    private void persistImageAltProvenance(
            ProductFamilyEntity family, ImageManifest image, String importKey, Instant importedAt) {
        ProductProvenanceEntity item = new ProductProvenanceEntity();
        item.ownerType = FAMILY_OWNER;
        item.ownerKey = family.familyKey;
        item.familyId = family.id;
        item.fieldName = "images[" + image.sourceId() + "].altText";
        item.source = image.altTextSource();
        item.sourceRecordKey = image.sourceUrl();
        item.rawValue = write(image.altText());
        item.confidence = "SHOPIFY".equals(image.altTextSource()) ? "HIGH" : "MEDIUM";
        item.status = "OBSERVED";
        item.observedAt = importedAt;
        item.importKey = importKey;
        provenance.persist(item);
    }

    private void persistConflict(long batchId, String familyKey, ConflictManifest input) {
        CatalogImportConflictEntity item = new CatalogImportConflictEntity();
        item.importBatchId = batchId;
        item.familyKey = familyKey;
        item.fieldName = input.code();
        item.code = input.code();
        item.severity = input.severity();
        item.reason = input.message();
        item.confidence = input.confidence();
        item.status = input.status();
        item.sourceValuesJson = write(safe(input.relatedSourceRecords()));
        conflicts.persist(item);
    }

    private Validation validate(CanonicalCatalogManifest manifest) {
        Validation result = new Validation();
        if (manifest == null) {
            result.problems.add("Manifest ontbreekt");
            return result;
        }
        if (!SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            result.problems.add("schemaVersion moet " + SCHEMA_VERSION + " zijn");
        }
        if (manifest.importDescriptor() == null) {
            result.problems.add("importDescriptor ontbreekt");
        } else {
            if (optional(manifest.importDescriptor().importKey()) == null) {
                result.problems.add("importDescriptor.importKey ontbreekt");
            } else if (!manifest.importDescriptor().importKey().matches("[A-Za-z0-9._-]{1,120}")) {
                result.problems.add("importDescriptor.importKey heeft ongeldige tekens");
            }
            if (manifest.importDescriptor().generatedAt() == null) {
                result.problems.add("importDescriptor.generatedAt ontbreekt");
            }
            if (optional(manifest.importDescriptor().transformVersion()) == null
                    || !manifest.importDescriptor().transformVersion()
                    .matches("\\d{4}-\\d{2}-\\d{2}\\.\\d+")) {
                result.problems.add("importDescriptor.transformVersion is ongeldig");
            }
            if (!validSha(manifest.importDescriptor().sourceDigest())) {
                result.problems.add("importDescriptor.sourceDigest is ongeldig");
            }
            if (!validSha(manifest.importDescriptor().payloadSha256())) {
                result.problems.add("importDescriptor.payloadSha256 is ongeldig");
            }
            Set<String> sourceNames = new HashSet<>();
            for (SourceDescriptor source : safe(manifest.importDescriptor().sources())) {
                if (optional(source.sourceType()) == null || optional(source.filename()) == null
                        || !validSha(source.sha256())) {
                    result.problems.add("Elke bron vereist sourceType, filename en SHA-256");
                }
                String identity = source.sourceType() + "\u0000" + source.filename();
                if (!sourceNames.add(identity)) result.problems.add("Dubbele bron " + source.filename());
            }
            String computedSourceDigest = sha256(safe(manifest.importDescriptor().sources()).stream()
                    .map(source -> source.sourceType() + ":" + source.sha256())
                    .collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8));
            if (!Objects.equals(computedSourceDigest, manifest.importDescriptor().sourceDigest())) {
                result.problems.add("importDescriptor.sourceDigest komt niet overeen met de bronnen");
            }
        }

        Set<String> categoryKeys = new HashSet<>();
        for (CategoryManifest category : safe(manifest.categories())) {
            if (!handle(category.key()) || optional(category.name()) == null) {
                result.problems.add("Elke categorie vereist een geldige key en naam");
            }
            if (!categoryKeys.add(category.key())) result.problems.add("Dubbele categorie " + category.key());
        }

        Set<String> familyKeys = new HashSet<>();
        Set<String> handles = new HashSet<>();
        Set<String> variantKeys = new HashSet<>();
        Set<String> skus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> barcodes = new HashSet<>();
        Map<String, CollectionManifest> collectionDefinitions = new HashMap<>();
        int conflictsFound = 0;
        int knownInventory = 0;
        int unknownInventory = 0;
        int shopifyAlt = 0;
        int generatedAlt = 0;

        for (FamilyManifest family : safe(manifest.families())) {
            result.familyCount++;
            String path = "familie " + family.canonicalFamilyKey();
            if (optional(family.canonicalFamilyKey()) == null
                    || !familyKeys.add(family.canonicalFamilyKey())) {
                result.problems.add("Ontbrekende of dubbele canonicalFamilyKey bij " + path);
            }
            if (family.publicHandle() != null
                    && (!handle(family.publicHandle()) || !handles.add(family.publicHandle()))) {
                result.problems.add("Ongeldige of dubbele publicHandle bij " + path);
            }
            if (family.active() == null) result.problems.add(path + " mist active");
            if (family.category() != null && !categoryKeys.contains(family.category().key())) {
                result.problems.add(path + " verwijst niet naar een top-level categorie");
            }

            List<CollectionManifest> familyCollections = safe(family.collections());
            long primaryCollections = familyCollections.stream()
                    .filter(item -> Boolean.TRUE.equals(item.primary())).count();
            if (!familyCollections.isEmpty() && primaryCollections != 1) {
                result.problems.add(path + " moet exact één primaire collectie hebben");
            }
            for (CollectionManifest collection : familyCollections) {
                if (!handle(collection.key()) || optional(collection.name()) == null) {
                    result.problems.add(path + " heeft een ongeldige collectie");
                    continue;
                }
                CollectionManifest prior = collectionDefinitions.putIfAbsent(collection.key(), collection);
                if (prior != null && (!Objects.equals(prior.name(), collection.name())
                        || !Objects.equals(prior.eyebrow(), collection.eyebrow())
                        || !Objects.equals(prior.description(), collection.description())
                        || !Objects.equals(prior.mobileName(), collection.mobileName())
                        || !Objects.equals(prior.featuredCanonicalVariantKey(),
                        collection.featuredCanonicalVariantKey()))) {
                    result.problems.add("Collectie " + collection.key() + " heeft conflicterende editorial");
                }
                if (optional(collection.featuredCanonicalVariantKey()) != null
                        && !featuredVariantBelongsToCollection(
                        manifest, collection.featuredCanonicalVariantKey(), collection.key())) {
                    result.problems.add("Collectie " + collection.key()
                            + " verwijst niet naar een actieve variant binnen die collectie");
                }
                if (optional(collection.featuredCanonicalVariantKey()) != null
                        && categoryKeys.contains(collection.key())
                        && !featuredVariantBelongsToPrimaryCategory(
                        manifest, collection.featuredCanonicalVariantKey(), collection.key())) {
                    result.problems.add("Categorie " + collection.key()
                            + " verwijst niet naar een actieve variant binnen de primaire categorie");
                }
            }

            Set<Language> textLanguages = EnumSet.noneOf(Language.class);
            for (FamilyTextManifest text : safe(family.texts())) {
                if (text.language() == null || !textLanguages.add(text.language())) {
                    result.problems.add(path + " heeft een ontbrekende of dubbele teksttaal");
                }
            }
            Set<String> familyVariantKeys = safe(family.variants()).stream()
                    .map(VariantManifest::canonicalVariantKey).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            String cardFeaturedKey = optional(family.cardFeaturedCanonicalVariantKey());
            if (cardFeaturedKey != null) {
                VariantManifest selected = safe(family.variants()).stream().filter(variant ->
                                cardFeaturedKey.equals(variant.canonicalVariantKey())
                                        && Boolean.TRUE.equals(variant.active()))
                        .findFirst().orElse(null);
                if (selected == null) {
                    result.problems.add(path
                            + " verwijst niet naar een actieve uitgelichte variant binnen de familie");
                } else if (!manifestPhotoForVariant(family, selected)) {
                    result.problems.add(path
                            + " uitgelichte variant mist een eigen of familiebrede publieke foto");
                }
            }
            boolean websitePublished = requested(family, "WEBSITE") == PublicationState.PUBLISHED;
            boolean websiteReady = websitePublished
                    || requested(family, "WEBSITE") == PublicationState.READY;
            if (websitePublished) {
                result.websitePublishedFamilyCount++;
                validateWebsiteFamily(family, result, path);
            }
            countPrices(result, family.priceObservations());
            countIdentifiers(result, family.externalIdentifiers());
            result.pdfDimensionObservations += safe(family.dimensions()).stream()
                    .filter(item -> "PDF".equals(item.sourceType())).count();
            result.pdfPackageObservations += safe(family.packages()).stream()
                    .filter(item -> "PDF".equals(item.sourceType())).count();

            for (VariantManifest variant : safe(family.variants())) {
                result.variantCount++;
                countPrices(result, variant.priceObservations());
                countIdentifiers(result, variant.externalIdentifiers());
                result.pdfPackageObservations += safe(variant.packages()).stream()
                        .filter(item -> "PDF".equals(item.sourceType())).count();
                String variantPath = path + " variant " + variant.canonicalVariantKey();
                if (optional(variant.canonicalVariantKey()) == null
                        || !variantKeys.add(variant.canonicalVariantKey())) {
                    result.problems.add("Ontbrekende of dubbele canonicalVariantKey bij " + variantPath);
                }
                String sku = optional(variant.sku());
                if (optional(variant.skuProvenance()) == null) {
                    result.problems.add(variantPath + " mist skuProvenance");
                }
                if (sku == null) {
                    if (!"GENERATED_INTERNAL".equals(variant.skuProvenance())) {
                        result.problems.add(variantPath + " mist SKU zonder GENERATED_INTERNAL provenance");
                    }
                } else if (!skus.add(sku)) {
                    result.problems.add("Dubbele SKU " + sku);
                }
                if ("GENERATED_INTERNAL".equals(variant.skuProvenance())
                        && optional(variant.sourceSku()) != null) {
                    result.problems.add(variantPath + " claimt tegelijk een bron-SKU en GENERATED_INTERNAL");
                }
                if (optional(variant.barcode()) != null) {
                    BarcodeValidator.Result barcode = barcodeValidator.validate(variant.barcode());
                    if (!barcode.valid()) result.problems.add(variantPath + " barcode: " + barcode.message());
                    if (!barcodes.add(variant.barcode())) result.problems.add("Dubbele barcode " + variant.barcode());
                }
                if (variant.colourHex() != null
                        && !variant.colourHex().matches("#[0-9A-F]{6}")) {
                    result.problems.add(variantPath + " kleurcode moet exact #RRGGBB zijn");
                }
                if (supportsFeaturedVariantContract(manifest)
                        && websiteReady && Boolean.TRUE.equals(variant.active())
                        && optional(variant.color()) != null
                        && optional(variant.colourHex()) == null) {
                    result.problems.add(variantPath
                            + " mist colourHex voor website READY/PUBLISHED");
                }
                if (variant.inventoryKnown() == null) {
                    result.problems.add(variantPath + " mist inventoryKnown");
                } else if (variant.inventoryKnown()) {
                    knownInventory++;
                    if (variant.stockQuantity() == null || variant.stockQuantity() < 0) {
                        result.problems.add(variantPath + " heeft bekende maar ongeldige voorraad");
                    } else {
                        result.inventoryKnownStockTotal += variant.stockQuantity();
                    }
                } else {
                    unknownInventory++;
                    if (variant.stockQuantity() != null) {
                        result.problems.add(variantPath + " mag bij onbekende voorraad geen nul suggereren");
                    }
                }
                if (websitePublished) validateWebsiteVariant(variant, result, variantPath);
            }

            Set<String> imageSourceIds = new HashSet<>();
            for (ImageManifest image : safe(family.images())) {
                result.imageCount++;
                String imagePath = path + " afbeelding " + image.sourceId();
                if (optional(image.sourceId()) == null || !imageSourceIds.add(image.sourceId())) {
                    result.problems.add(imagePath + " mist een unieke sourceId");
                }
                if (!"image/webp".equals(image.contentType())) {
                    result.problems.add(imagePath + " moet image/webp zijn");
                }
                if (optional(image.altText()) == null || optional(image.altTextSource()) == null) {
                    result.problems.add(imagePath + " mist alt-tekst of alt-bron");
                }
                if ("SHOPIFY".equals(image.altTextSource())) shopifyAlt++;
                if ("WEBSITE_GENERATED".equals(image.altTextSource())) generatedAlt++;
                if (image.variantCanonicalKey() != null
                        && !familyVariantKeys.contains(image.variantCanonicalKey())) {
                    result.problems.add(imagePath + " verwijst naar een onbekende variant");
                }
                if (image.small() != null && validSha(image.small().sha256())) {
                    result.renditionChecksums.add(image.small().sha256());
                }
                if (image.large() != null && validSha(image.large().sha256())) {
                    result.renditionChecksums.add(image.large().sha256());
                }
                validateRendition(image.small(), imagePath + " small", result);
                validateRendition(image.large(), imagePath + " large", result);
            }
            conflictsFound += safe(family.conflicts()).size();
        }

        validateSummary(manifest.validationSummary(), result, conflictsFound,
                knownInventory, unknownInventory, shopifyAlt, generatedAlt);
        if (manifest.importDescriptor() != null
                && validSha(manifest.importDescriptor().payloadSha256())
                && !Objects.equals("enrosed-catalog-"
                        + manifest.importDescriptor().payloadSha256().substring(0, 16),
                        manifest.importDescriptor().importKey())) {
            result.problems.add("importDescriptor.importKey is niet afgeleid van payloadSha256");
        }
        return result;
    }

    private static boolean featuredVariantBelongsToCollection(
            CanonicalCatalogManifest manifest, String variantKey, String collectionKey) {
        return safe(manifest.families()).stream().anyMatch(family -> {
            boolean member = safe(family.collections()).stream()
                    .anyMatch(collection -> Objects.equals(collection.key(), collectionKey));
            if (!member && safe(family.collections()).isEmpty() && family.category() != null) {
                member = Objects.equals(family.category().key(), collectionKey);
            }
            VariantManifest selected = safe(family.variants()).stream().filter(variant ->
                            Objects.equals(variant.canonicalVariantKey(), variantKey)
                                    && Boolean.TRUE.equals(variant.active()))
                    .findFirst().orElse(null);
            return member && selected != null && manifestPhotoForVariant(family, selected);
        });
    }

    private static boolean featuredVariantBelongsToPrimaryCategory(
            CanonicalCatalogManifest manifest, String variantKey, String categoryKey) {
        return safe(manifest.families()).stream().anyMatch(family -> {
            boolean primaryCategory = family.category() != null
                    && Objects.equals(family.category().key(), categoryKey);
            VariantManifest selected = safe(family.variants()).stream().filter(variant ->
                            Objects.equals(variant.canonicalVariantKey(), variantKey)
                                    && Boolean.TRUE.equals(variant.active()))
                    .findFirst().orElse(null);
            return primaryCategory && selected != null && manifestPhotoForVariant(family, selected);
        });
    }

    private static boolean manifestPhotoForVariant(
            FamilyManifest family, VariantManifest selected) {
        return safe(family.images()).stream().anyMatch(image -> {
            if (optional(image.variantCanonicalKey()) == null
                    && optional(image.variantColor()) == null) return true;
            if (Objects.equals(image.variantCanonicalKey(), selected.canonicalVariantKey())) return true;
            if (optional(image.variantCanonicalKey()) != null
                    || optional(image.variantColor()) == null) return false;
            String wanted = image.variantColor().strip().replaceAll("\\s+", " ");
            List<VariantManifest> colourMatches = safe(family.variants()).stream()
                    .filter(variant -> optional(variant.color()) != null)
                    .filter(variant -> wanted.equalsIgnoreCase(
                            variant.color().strip().replaceAll("\\s+", " ")))
                    .toList();
            return colourMatches.size() == 1
                    && Objects.equals(colourMatches.getFirst().canonicalVariantKey(),
                    selected.canonicalVariantKey());
        });
    }

    /** The archived 2026-08-20.4 manifest predates editable swatches and remains replayable. */
    private static boolean supportsFeaturedVariantContract(CanonicalCatalogManifest manifest) {
        if (manifest.importDescriptor() == null) return false;
        String version = optional(manifest.importDescriptor().transformVersion());
        return version != null && version.compareTo("2026-08-20.5") >= 0;
    }

    private void validateWebsiteFamily(FamilyManifest family, Validation result, String path) {
        if (!handle(family.publicHandle())) result.problems.add(path + " mist publieke handle");
        if (family.category() == null || !handle(family.category().key())) {
            result.problems.add(path + " mist publieke categorie");
        }
        if (optional(textValue(family.texts(), FamilyTextManifest::name)) == null) {
            result.problems.add(path + " mist publieke naam");
        }
        if (optional(textValue(family.texts(), FamilyTextManifest::summary)) == null) {
            result.problems.add(path + " mist publieke samenvatting");
        }
        if (optional(textValue(family.texts(), FamilyTextManifest::description)) == null) {
            result.problems.add(path + " mist publieke beschrijving");
        }
        if (optional(textValue(family.texts(), FamilyTextManifest::seoTitle)) == null
                || optional(textValue(family.texts(), FamilyTextManifest::seoDescription)) == null) {
            result.problems.add(path + " mist volledige publieke SEO");
        }
        CollectionManifest primary = safe(family.collections()).stream()
                .filter(item -> Boolean.TRUE.equals(item.primary())).findFirst().orElse(null);
        if (primary == null || optional(primary.eyebrow()) == null
                || optional(primary.description()) == null) {
            result.problems.add(path + " mist primaire collectie-editorial");
        }
        if (safe(family.images()).isEmpty()) result.problems.add(path + " mist publieke afbeeldingen");
        if (safe(family.variants()).stream().noneMatch(item -> Boolean.TRUE.equals(item.active()))) {
            result.problems.add(path + " mist een actieve publieke variant");
        }
    }

    private void validateWebsiteVariant(VariantManifest variant, Validation result, String path) {
        if (!Boolean.TRUE.equals(variant.active())) return;
        if (variant.publicAvailability() == null) {
            result.problems.add(path + " mist expliciete publieke beschikbaarheid");
        }
        PriceObservationManifest retail = safe(variant.priceObservations()).stream()
                .filter(item -> "SHOPIFY_RETAIL".equals(item.priceType()))
                .filter(item -> "EUR".equals(item.currency()))
                .findFirst().orElse(null);
        if (retail == null || retail.amount() == null || retail.amount().signum() < 0) {
            result.problems.add(path + " mist een expliciete publieke EUR-prijs");
        }
    }

    private void validateRendition(
            ImageRenditionManifest rendition, String path, Validation result) {
        if (rendition == null || !validSha(rendition.sha256())
                || rendition.width() == null || rendition.width() <= 0
                || rendition.height() == null || rendition.height() <= 0
                || optional(rendition.bytesBase64()) == null) {
            result.problems.add(path + " mist checksum, bytes of positieve afmetingen");
            return;
        }
        try {
            byte[] bytes = decode(rendition.bytesBase64());
            PhotoUploadPolicy.ValidatedPhoto validated = PhotoUploadPolicy.validate(
                    "migration.webp", new ByteArrayInputStream(bytes));
            if (!"image/webp".equals(validated.contentType())) {
                result.problems.add(path + " bytes zijn geen WebP");
            }
            if (!rendition.sha256().equals(sha256(bytes))) {
                result.problems.add(path + " checksum komt niet overeen met de bytes");
            }
        } catch (RuntimeException exception) {
            result.problems.add(path + " bevat ongeldige base64- of afbeeldingsdata");
        }
    }

    private static void validateSummary(
            ValidationSummary summary, Validation result, int conflicts,
            int knownInventory, int unknownInventory, int shopifyAlt, int generatedAlt) {
        if (summary == null) {
            result.problems.add("validationSummary ontbreekt");
            return;
        }
        checkCount("families", summary.familyCount(), result.familyCount, result);
        checkCount("varianten", summary.variantCount(), result.variantCount, result);
        checkCount("websitefamilies", summary.websitePublishedFamilyCount(),
                result.websitePublishedFamilyCount, result);
        checkCount("logische afbeeldingen", summary.logicalImageCount(), result.imageCount, result);
        checkCount("afbeeldingsrendities", summary.imageRenditionCount(), result.imageCount * 2, result);
        checkCount("unieke afbeeldingsblobs", summary.uniqueRenditionBlobCount(),
                result.renditionChecksums.size(), result);
        checkCount("conflicten", summary.conflicts(), conflicts, result);
        checkCount("varianten met bekende voorraad", summary.inventoryKnownVariants(), knownInventory, result);
        checkCount("varianten met onbekende voorraad", summary.inventoryUnknownVariants(), unknownInventory, result);
        checkCount("Shopify-altteksten", summary.shopifyAltTextImages(), shopifyAlt, result);
        checkCount("gegenereerde altteksten", summary.generatedFallbackAltTextImages(), generatedAlt, result);
        checkCount("bekende voorraad totaal", summary.inventoryKnownStockTotal(),
                result.inventoryKnownStockTotal, result);
        checkCount("Shopify-productidentifiers", summary.shopifyProductIdentifiers(),
                result.shopifyProductIdentifiers, result);
        checkCount("Shopify-variantidentifiers", summary.shopifyVariantIdentifiers(),
                result.shopifyVariantIdentifiers, result);
        checkCount("verpakkings-GTIN-kandidaten", summary.packagingGtinCandidates(),
                result.packagingGtinCandidates, result);
        checkCount("PDF-dimensiewaarnemingen", summary.pdfDimensionObservations(),
                Math.toIntExact(result.pdfDimensionObservations), result);
        checkCount("PDF-verpakkingswaarnemingen", summary.pdfPackageObservations(),
                Math.toIntExact(result.pdfPackageObservations), result);
        Map<String, Integer> expectedPrices = summary.priceObservationCounts() == null
                ? Map.of() : summary.priceObservationCounts();
        if (!expectedPrices.equals(result.priceObservationCounts)) {
            result.problems.add("validationSummary prijswaarnemingen verwacht " + expectedPrices
                    + " maar manifest bevat " + result.priceObservationCounts);
        }
    }

    private static void countPrices(
            Validation result, List<PriceObservationManifest> observations) {
        for (PriceObservationManifest observation : safe(observations)) {
            String type = optional(observation.priceType());
            if (type != null) result.priceObservationCounts.merge(type, 1, Integer::sum);
        }
    }

    private static void countIdentifiers(
            Validation result, List<ExternalIdentifierManifest> identifiers) {
        for (ExternalIdentifierManifest identifier : safe(identifiers)) {
            if ("SHOPIFY".equals(identifier.source())
                    && "PRODUCT_ID".equals(identifier.identifierType())) {
                result.shopifyProductIdentifiers++;
            }
            if ("SHOPIFY".equals(identifier.source())
                    && "VARIANT_ID".equals(identifier.identifierType())) {
                result.shopifyVariantIdentifiers++;
            }
            if ("PACKAGING_GTIN_CANDIDATE".equals(identifier.identifierType())) {
                result.packagingGtinCandidates++;
            }
        }
    }

    private static void checkCount(String label, Integer expected, int actual, Validation result) {
        if (expected != null && expected != actual) {
            result.problems.add("validationSummary " + label + " verwacht " + expected
                    + " maar manifest bevat " + actual);
        }
    }

    private ReferenceCounts referenceCounts(Set<Long> productIds) {
        if (productIds.isEmpty()) return new ReferenceCounts(0, 0, 0, 0);
        return new ReferenceCounts(
                count("select count(l) from PurchaseOrderLineEntity l where l.productId in :ids", productIds),
                count("select count(l) from SalesOrderLineEntity l where l.productId in :ids", productIds),
                count("select count(i) from SalesPalletItemEntity i where i.productId in :ids", productIds),
                count("select count(l) from QuoteRevisionLineEntity l where l.productId in :ids", productIds));
    }

    private long count(String query, Set<Long> ids) {
        return entityManager.createQuery(query, Long.class).setParameter("ids", ids).getSingleResult();
    }

    private GraphDeleteCounts deleteReferencingGraphs(Set<Long> productIds) {
        if (productIds.isEmpty()) return new GraphDeleteCounts(0, 0);
        Set<Long> salesOrderIds = new LinkedHashSet<>(ids(
                "select distinct l.order.id from SalesOrderLineEntity l where l.productId in :ids",
                productIds));
        salesOrderIds.addAll(ids(
                "select distinct i.pallet.order.id from SalesPalletItemEntity i where i.productId in :ids",
                productIds));
        salesOrderIds.addAll(ids(
                "select distinct r.salesOrderId from QuoteRevisionEntity r join r.lines l "
                        + "where l.productId in :ids and r.salesOrderId is not null", productIds));

        Set<Long> revisionIds = new LinkedHashSet<>(ids(
                "select distinct r.id from QuoteRevisionEntity r join r.lines l where l.productId in :ids",
                productIds));
        if (!salesOrderIds.isEmpty()) {
            revisionIds.addAll(entityManager.createQuery(
                            "select r.id from QuoteRevisionEntity r where r.salesOrderId in :ids", Long.class)
                    .setParameter("ids", salesOrderIds).getResultList());
        }
        for (Long revisionId : revisionIds) {
            SalesEntities.QuoteRevisionEntity revision = revisions.findById(revisionId);
            if (revision != null) revisions.delete(revision);
        }
        for (Long orderId : salesOrderIds) {
            events.delete("salesOrderId", orderId);
            SalesEntities.SalesOrderEntity order = salesOrders.findById(orderId);
            if (order != null) salesOrders.delete(order);
        }

        Set<Long> purchaseOrderIds = new LinkedHashSet<>(ids(
                "select distinct l.order.id from PurchaseOrderLineEntity l where l.productId in :ids",
                productIds));
        for (Long orderId : purchaseOrderIds) {
            SourcingEntities.PurchaseOrderEntity order = purchaseOrders.findById(orderId);
            if (order != null) purchaseOrders.delete(order);
        }
        entityManager.flush();
        return new GraphDeleteCounts(salesOrderIds.size(), purchaseOrderIds.size());
    }

    private List<Long> ids(String query, Set<Long> productIds) {
        return entityManager.createQuery(query, Long.class)
                .setParameter("ids", productIds).getResultList();
    }

    private Set<Long> existingProductIds() {
        return products.findAll().list().stream().map(item -> item.id).collect(Collectors.toSet());
    }

    private Set<String> allProductPhotoKeys() {
        Set<String> keys = new LinkedHashSet<>();
        products.findAll().list().forEach(product -> product.photos.forEach(photo -> keys.add(photo.storageKey)));
        families.findAll().list().forEach(family -> family.photos.forEach(photo -> {
            keys.add(photo.smallStorageKey);
            keys.add(photo.largeStorageKey);
        }));
        keys.remove(null);
        return keys;
    }

    private void clearProductDomain() {
        externalIdentifiers.deleteAll();
        priceObservations.deleteAll();
        provenance.deleteAll();
        dimensionObservations.deleteAll();
        conflicts.deleteAll();
        entityManager.createQuery("delete from ProductTextEntity").executeUpdate();
        entityManager.createQuery("delete from ProductPhotoEntity").executeUpdate();
        entityManager.createQuery("delete from ProductFamilyTextEntity").executeUpdate();
        entityManager.createQuery("delete from ProductFamilyPhotoEntity").executeUpdate();
        entityManager.createQuery("delete from ProductPackageEntity").executeUpdate();
        entityManager.createQuery("delete from ProductFamilyCollectionEntity").executeUpdate();
        products.deleteAll();
        families.deleteAll();
        collections.deleteAll();
        entityManager.flush();
    }

    /** FK-safe application-data reset. Schema, sequences and auth configuration remain intact. */
    private Map<String, Long> fullReset() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : APPLICATION_TABLES) {
            Number count = (Number) entityManager.createNativeQuery("select count(*) from " + table)
                    .getSingleResult();
            counts.put(table, count.longValue());
            entityManager.createNativeQuery("delete from " + table).executeUpdate();
        }
        entityManager.flush();
        return counts;
    }

    private Map<String, Long> applicationRowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : APPLICATION_TABLES) {
            Number count = (Number) entityManager.createNativeQuery("select count(*) from " + table)
                    .getSingleResult();
            counts.put(table, count.longValue());
        }
        return Collections.unmodifiableMap(counts);
    }

    String computePayloadSha256(CanonicalCatalogManifest manifest) {
        return sha256(payloadBytes(manifest));
    }

    byte[] payloadBytes(CanonicalCatalogManifest manifest) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("schemaVersion", manifest.schemaVersion());
            payload.set("categories", json.valueToTree(manifest.categories()));
            ArrayNode familyNodes = json.valueToTree(manifest.families());
            familyNodes.forEach(familyNode -> familyNode.path("images").forEach(imageNode -> {
                if (imageNode.path("small") instanceof ObjectNode small) small.remove("bytesBase64");
                if (imageNode.path("large") instanceof ObjectNode large) large.remove("bytesBase64");
            }));
            payload.set("families", familyNodes);
            payload.set("validationSummary", json.valueToTree(manifest.validationSummary()));
            return json.writeValueAsBytes(payload);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new BusinessRuleException("Kan manifest niet deterministisch hashen");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BusinessRuleException("Kan canonieke bronwaarde niet serialiseren");
        }
    }

    private static String textValue(
            List<FamilyTextManifest> texts, Function<FamilyTextManifest, String> field) {
        return orderedTexts(texts).stream().map(field).filter(value -> optional(value) != null)
                .findFirst().orElse(null);
    }

    private static List<String> textList(
            List<FamilyTextManifest> texts, Function<FamilyTextManifest, List<String>> field) {
        return orderedTexts(texts).stream().map(field).filter(value -> !safe(value).isEmpty())
                .findFirst().orElse(List.of());
    }

    private static List<FamilyTextManifest> orderedTexts(List<FamilyTextManifest> texts) {
        return safe(texts).stream().sorted(Comparator.comparingInt(text -> {
            if (text == null || text.language() == null) return 3;
            return switch (text.language()) {
                case EN -> 0;
                case NL -> 1;
                default -> 2;
            };
        })).filter(Objects::nonNull).toList();
    }

    private static PublicationState requested(FamilyManifest family, String channel) {
        if (family.requestedPublication() == null) return PublicationState.DRAFT;
        PublicationState state = switch (channel) {
            case "WEBSITE" -> family.requestedPublication().websiteStatus();
            case "ORDER_APP" -> family.requestedPublication().orderAppStatus();
            case "CATALOGUE" -> family.requestedPublication().catalogueStatus();
            default -> PublicationState.DRAFT;
        };
        return state == null ? PublicationState.DRAFT : state;
    }

    private static BigDecimal value(List<BigDecimal> values, int index) {
        return values.size() > index ? values.get(index) : null;
    }

    private static int integer(Integer value) { return value == null ? 0 : value; }

    private static String generatedSku(String canonicalVariantKey) {
        String slug = required(canonicalVariantKey).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 42) slug = slug.substring(0, 42).replaceAll("-$", "");
        return "ENR-" + slug + "-" + sha256(canonicalVariantKey.getBytes(StandardCharsets.UTF_8))
                .substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String stableKey(String... values) {
        return sha256(String.join("\u0000", Arrays.stream(values)
                .map(value -> value == null ? "" : value).toList()).getBytes(StandardCharsets.UTF_8));
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/webp" -> ".webp";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private static boolean handle(String value) {
        return value != null && value.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private static boolean validSha(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static String required(String value) {
        String result = optional(value);
        if (result == null) throw new BusinessRuleException("Verplichte canonieke sleutel ontbreekt");
        return result;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static byte[] decode(String base64) {
        try { return Base64.getDecoder().decode(required(base64)); }
        catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("Ongeldige base64-afbeelding");
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 ontbreekt", exception); }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static final class Validation {
        final List<String> problems = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int familyCount;
        int variantCount;
        int imageCount;
        int websitePublishedFamilyCount;
        int inventoryKnownStockTotal;
        int shopifyProductIdentifiers;
        int shopifyVariantIdentifiers;
        int packagingGtinCandidates;
        long pdfDimensionObservations;
        long pdfPackageObservations;
        final Set<String> renditionChecksums = new HashSet<>();
        final Map<String, Integer> priceObservationCounts = new TreeMap<>();
    }

    private static final class ImportStats {
        int families;
        int variants;
        int images;
        int conflicts;
        int reusedBlobs;
    }

    private record StoredRendition(String storageKey, long sizeBytes, boolean reused) {}
    private record ReferenceCounts(long purchaseOrderLines, long salesOrderLines,
                                   long salesPalletItems, long quoteRevisionLines) {
        long total() { return purchaseOrderLines + salesOrderLines + salesPalletItems + quoteRevisionLines; }
    }
    private record GraphDeleteCounts(int salesOrders, int purchaseOrders) {}
}
