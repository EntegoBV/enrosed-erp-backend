package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.FamilyPhotoCompatibilityService;
import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.application.FamilyImageVariantService;
import be.enrosed.catalog.application.FamilyVariantRules;
import be.enrosed.catalog.application.ProductFamilyWriteGuard;
import be.enrosed.catalog.application.CategoryPublicKey;
import be.enrosed.catalog.application.PublishedFamilyGalleryGuard;
import be.enrosed.catalog.application.FamilyMemberCacheService;
import be.enrosed.catalog.application.FamilyCollectionAlignmentService;
import be.enrosed.catalog.application.FeaturedProductSelectionService;
import be.enrosed.catalog.application.PhotoReferenceService;
import be.enrosed.catalog.application.PhotoRenditionService;
import be.enrosed.catalog.application.PhotoUploadPolicy;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Administrator CRUD for family-level website/order-app master data. */
@Path("/api/product-families")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductFamilyResource {
    private static final int MAX_SHORT = 255;
    private static final int MAX_SUMMARY = 2_000;
    private static final int MAX_LONG = 10_000;
    private static final int MAX_HIGHLIGHT = 1_000;
    private final CanonicalCatalogDaos.Families families;
    private final ProductFamilyDtoFactory familyDtos;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final PhotoStorage photoStorage;
    private final PhotoRenditionService photoRenditions;
    private final PhotoReferenceService photoReferences;
    private final FamilyPhotoCompatibilityService familyPhotoCompatibility;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final FamilyImageVariantService familyImageVariants;
    private final PublishedFamilyGalleryGuard galleryGuard;
    private final FamilyMemberCacheService memberCache;
    private final FamilyCollectionAlignmentService familyCollections;
    private final FeaturedProductSelectionService featuredProducts;
    private final ProductFamilyWriteGuard familyWrites;
    private final CanonicalCatalogDaos.ImportConflicts importConflicts;
    private final CanonicalCatalogDaos.ImportBatches importBatches;
    private final ObjectMapper json;

    @Inject
    be.enrosed.catalog.application.WebsiteRebuildService websiteRebuild;

    @Inject
    be.enrosed.catalog.application.PublicLocalizationCompletenessService localization;

    @Inject
    Instance<ActivityLogService> activity;

    public ProductFamilyResource(
            CanonicalCatalogDaos.Families families,
            ProductFamilyDtoFactory familyDtos,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            PhotoStorage photoStorage,
            PhotoRenditionService photoRenditions,
            PhotoReferenceService photoReferences,
            FamilyPhotoCompatibilityService familyPhotoCompatibility,
            FamilyPhotoPublicationPolicy photoPublication,
            FamilyImageVariantService familyImageVariants,
            PublishedFamilyGalleryGuard galleryGuard,
            FamilyMemberCacheService memberCache,
            FamilyCollectionAlignmentService familyCollections,
            FeaturedProductSelectionService featuredProducts,
            ProductFamilyWriteGuard familyWrites,
            CanonicalCatalogDaos.ImportConflicts importConflicts,
            CanonicalCatalogDaos.ImportBatches importBatches,
            ObjectMapper json) {
        this.families = families;
        this.familyDtos = familyDtos;
        this.products = products;
        this.categories = categories;
        this.photoStorage = photoStorage;
        this.photoRenditions = photoRenditions;
        this.photoReferences = photoReferences;
        this.familyPhotoCompatibility = familyPhotoCompatibility;
        this.photoPublication = photoPublication;
        this.familyImageVariants = familyImageVariants;
        this.galleryGuard = galleryGuard;
        this.memberCache = memberCache;
        this.familyCollections = familyCollections;
        this.featuredProducts = featuredProducts;
        this.familyWrites = familyWrites;
        this.importConflicts = importConflicts;
        this.importBatches = importBatches;
        this.json = json;
    }

    @GET
    public List<ProductFamilyDto> list() {
        return families.findAll().list().stream()
                .sorted(Comparator.comparingInt((ProductFamilyEntity item) -> item.categoryPosition)
                        .thenComparingInt(item -> item.productPosition)
                        .thenComparing(item -> safe(item.name), String.CASE_INSENSITIVE_ORDER))
                .map(this::dto).toList();
    }

    @GET @Path("/{id}")
    public ProductFamilyDto get(@PathParam("id") long id) { return dto(family(id)); }

    @POST @Transactional
    public Response create(ProductFamilyDto request) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.createdAt = Instant.now();
        applyEditable(family, request, true);
        requireUnique(family, null);
        requireUniqueFamilyPosition(family, null);
        validateCardFeature(family);
        ensureRequestedPublicationIsValid(family, List.of(), true, true);
        families.persist(family);
        families.flush();
        recordFamilyCreated(family);
        queueWebsite();
        return Response.status(Response.Status.CREATED).entity(dto(family)).build();
    }

    @PUT @Path("/{id}") @Transactional
    public ProductFamilyDto update(@PathParam("id") long id, ProductFamilyDto request) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        FamilyAuditSnapshot before = FamilyAuditSnapshot.from(family);
        requireStablePublicIdentity(family, request);
        boolean wasPublished = isPublished(family);
        boolean wasReady = family.websiteStatus == PublicationState.READY
                || family.orderAppStatus == PublicationState.READY
                || family.catalogueStatus == PublicationState.READY;
        applyEditable(family, request, false);
        requireUnique(family, id);
        requireUniqueFamilyPosition(family, id);
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", id);
        memberCache.sync(family);
        families.flush();
        featuredProducts.clearInvalidReferencesForFamily(family);
        validateCardFeature(family);
        ensureRequestedPublicationIsValid(family, members, !wasPublished, !wasReady);
        families.flush();
        recordFamilyUpdated(family, before);
        return changed(family);
    }

    public record WebsiteVisibilityRequest(boolean visible) {}

    public record WebsiteVisibilityResult(
            ProductFamilyDto family,
            boolean rebuildQueued,
            String notice) {}

    /** One exact stock-bearing member in a guarded draft-identity finalization. */
    public record VariantIdentityRequest(
            String sku,
            String expectedCanonicalVariantKey,
            String canonicalVariantKey) {}

    /**
     * Optimistic preconditions and the complete target identity for a draft family. The SKU list
     * is deliberately complete rather than incremental: a concurrently added, removed or moved
     * member makes the whole command fail without changing any identity.
     */
    public record FinalizeDraftIdentityRequest(
            String expectedFamilyKey,
            String familyKey,
            String publicHandle,
            List<VariantIdentityRequest> variants) {}

    /**
     * Changes only the public website switch. This deliberately does not accept a full family
     * snapshot, so a quick action in the website workspace cannot overwrite a concurrent title,
     * translation, category, image, order-app or catalogue edit.
     */
    @PUT @Path("/{id}/website-visibility") @Transactional
    public WebsiteVisibilityResult setWebsiteVisibility(
            @PathParam("id") long id, WebsiteVisibilityRequest request) {
        if (request == null) {
            throw new BusinessRuleException("Kies of de productreeks zichtbaar is op de website");
        }
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        PublicationState wanted = request.visible()
                ? PublicationState.PUBLISHED : PublicationState.DRAFT;
        if (family.websiteStatus == wanted) {
            return new WebsiteVisibilityResult(dto(family), false, null);
        }
        if (request.visible() && !family.active) {
            throw new BusinessRuleException(
                    "Activeer de productreeks eerst in ERP voordat u ze op de website toont");
        }

        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", id);
        if (request.visible()) {
            PublicationState previous = family.websiteStatus;
            family.websiteStatus = wanted;
            try {
                ensureRequestedPublicationIsValid(family, members, true, false);
            } catch (RuntimeException failure) {
                /* Keep the managed entity coherent even when this method is invoked inside a
                   caller-owned transaction that catches the validation exception. */
                family.websiteStatus = previous;
                throw failure;
            }
        } else {
            family.websiteStatus = wanted;
        }
        family.updatedAt = Instant.now();
        families.flush();
        recordWebsiteVisibility(family, request.visible());
        boolean rebuildQueued = queueWebsite();
        return new WebsiteVisibilityResult(
                dto(family),
                rebuildQueued,
                rebuildQueued ? null
                        : "Zichtbaarheid is opgeslagen. Websitepublicatie wacht tot de andere "
                                + "openstaande publicatiepunten zijn opgelost.");
    }

    /**
     * Finalizes imported placeholder identities while the complete family is still internal.
     * Normal family and product editors keep their immutable-live-identity rules; this narrow
     * command is the only supported path from an observed draft key to semantic canonical keys.
     */
    @PUT @Path("/{id}/finalize-draft-identity") @Transactional
    public ProductFamilyDto finalizeDraftIdentity(
            @PathParam("id") long id, FinalizeDraftIdentityRequest request) {
        if (request == null) {
            throw new BusinessRuleException("Geen identiteitsfinalisatie meegestuurd");
        }
        String expectedFamilyKey = technicalKey(
                request.expectedFamilyKey(), "Verwachte familiecode");
        String targetFamilyKey = technicalKey(request.familyKey(), "Nieuwe familiecode");
        String targetHandle = technicalKey(request.publicHandle(), "Publieke handle");

        lockFamily(id);
        ProductFamilyEntity family = family(id);
        List<ProductEntity> observedMembers = products.list(
                "familyId = ?1 order by variantPosition, id", id);
        familyWrites.lockProducts(observedMembers.stream().map(member -> member.id).toList());
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", id);

        requireCompletelyDraft(family, members);
        if (members.isEmpty()) {
            throw new BusinessRuleException(
                    "Een familie zonder SKU's kan geen variantidentiteit krijgen");
        }

        LinkedHashMap<String, ProductEntity> membersBySku = new LinkedHashMap<>();
        for (ProductEntity member : members) {
            String sku = required(member.sku, "SKU", MAX_SHORT);
            if (membersBySku.put(sku, member) != null) {
                throw new BusinessRuleException("Dubbele SKU in productfamilie: " + sku);
            }
        }
        LinkedHashMap<String, String> requestedBySku = new LinkedHashMap<>();
        LinkedHashMap<String, String> expectedBySku = new LinkedHashMap<>();
        Set<String> requestedVariantKeys = new HashSet<>();
        Set<String> expectedVariantKeys = new HashSet<>();
        for (VariantIdentityRequest variant : safeList(request.variants())) {
            if (variant == null) {
                throw new BusinessRuleException("Een variantidentiteit mag niet leeg zijn");
            }
            String sku = required(variant.sku(), "SKU", MAX_SHORT);
            String expectedVariantKey = optional(variant.expectedCanonicalVariantKey());
            if (expectedVariantKey != null) {
                expectedVariantKey = technicalKey(
                        expectedVariantKey, "Verwachte canonieke variantcode");
                if (!expectedVariantKeys.add(expectedVariantKey)) {
                    throw new BusinessRuleException(
                            "Verwachte canonieke variantcode " + expectedVariantKey
                                    + " komt meer dan één keer voor");
                }
            }
            String variantKey = technicalKey(
                    variant.canonicalVariantKey(), "Canonieke variantcode");
            if (requestedBySku.put(sku, variantKey) != null) {
                throw new BusinessRuleException("SKU " + sku + " komt meer dan één keer voor");
            }
            expectedBySku.put(sku, expectedVariantKey);
            if (!requestedVariantKeys.add(variantKey)) {
                throw new BusinessRuleException(
                        "Canonieke variantcode " + variantKey + " komt meer dan één keer voor");
            }
        }
        if (!membersBySku.keySet().equals(requestedBySku.keySet())) {
            throw new BusinessRuleException(
                    "SKU-lidmaatschap is gewijzigd; verwacht exact "
                            + requestedBySku.keySet() + " maar vond " + membersBySku.keySet());
        }

        boolean alreadyFinalized = Objects.equals(family.familyKey, targetFamilyKey)
                && Objects.equals(family.publicHandle, targetHandle)
                && membersBySku.entrySet().stream().allMatch(entry -> Objects.equals(
                        entry.getValue().canonicalVariantKey, requestedBySku.get(entry.getKey())));
        if (alreadyFinalized) return dto(family);

        if (!Objects.equals(family.familyKey, expectedFamilyKey)) {
            throw new BusinessRuleException(
                    "Familiecode is gewijzigd; verwacht " + expectedFamilyKey
                            + " maar vond " + family.familyKey);
        }
        if (family.publicHandle != null && !Objects.equals(family.publicHandle, targetHandle)) {
            throw new BusinessRuleException(
                    "De productfamilie heeft al een andere vaste publieke handle");
        }
        for (Map.Entry<String, ProductEntity> entry : membersBySku.entrySet()) {
            String current = optional(entry.getValue().canonicalVariantKey);
            String expected = expectedBySku.get(entry.getKey());
            if (!Objects.equals(current, expected)) {
                throw new BusinessRuleException(
                        "Variantcode van SKU " + entry.getKey()
                                + " is gewijzigd; verwacht " + expected + " maar vond " + current);
            }
        }

        List<CatalogImportConflictEntity> ownedImportConflicts = requireOwnedImportConflicts(
                family, expectedFamilyKey, expectedBySku, requestedBySku);
        requireIdentityAvailable(family, members, targetFamilyKey, targetHandle, requestedVariantKeys);
        family.familyKey = targetFamilyKey;
        family.publicHandle = targetHandle;
        family.updatedAt = Instant.now();
        membersBySku.forEach((sku, member) ->
                member.canonicalVariantKey = requestedBySku.get(sku));
        migrateOwnedImportConflicts(
                ownedImportConflicts, targetFamilyKey, expectedBySku, requestedBySku);
        memberCache.sync(family);
        families.flush();
        recordIdentityFinalization(family, members.size());
        return dto(family);
    }

    /**
     * Conflict rows predate a family FK. They are moved only when their import batch proves that
     * they came from this family's last canonical import; ambiguous historical rows block the
     * command instead of being reassigned by a coincidentally matching text key.
     */
    private List<CatalogImportConflictEntity> requireOwnedImportConflicts(
            ProductFamilyEntity family,
            String expectedFamilyKey,
            Map<String, String> expectedBySku,
            Map<String, String> requestedBySku) {
        List<CatalogImportConflictEntity> rows = importConflicts.find(
                "familyKey", expectedFamilyKey)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list();
        if (rows.isEmpty()) return rows;
        String lastImportKey = optional(family.lastImportKey);
        if (lastImportKey == null) {
            throw new BusinessRuleException(
                    "Historische importconflicten hebben geen bewijsbare eigenaar; "
                            + "koppel ze eerst aan een importbatch");
        }
        Set<String> expectedVariantKeys = expectedBySku.values().stream()
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<String> targetVariantKeys = new HashSet<>(requestedBySku.values());
        for (CatalogImportConflictEntity row : rows) {
            CatalogImportBatchEntity batch = row.importBatchId == null
                    ? null : importBatches.findById(row.importBatchId);
            if (batch == null || !Objects.equals(lastImportKey, batch.importKey)) {
                throw new BusinessRuleException(
                        "Historisch importconflict " + row.id
                                + " hoort niet bewijsbaar bij de laatste familie-import");
            }
            String variantKey = optional(row.canonicalVariantKey);
            if (variantKey != null && !expectedVariantKeys.contains(variantKey)
                    && !targetVariantKeys.contains(variantKey)) {
                throw new BusinessRuleException(
                        "Historisch importconflict " + row.id
                                + " verwijst naar een onverwachte variantcode");
            }
        }
        return rows;
    }

    private static void migrateOwnedImportConflicts(
            List<CatalogImportConflictEntity> rows,
            String targetFamilyKey,
            Map<String, String> expectedBySku,
            Map<String, String> requestedBySku) {
        Map<String, String> variantRenames = new HashMap<>();
        expectedBySku.forEach((sku, expected) -> {
            if (expected != null) variantRenames.put(expected, requestedBySku.get(sku));
        });
        for (CatalogImportConflictEntity row : rows) {
            row.familyKey = targetFamilyKey;
            String replacement = variantRenames.get(optional(row.canonicalVariantKey));
            if (replacement != null) row.canonicalVariantKey = replacement;
        }
    }

    private void requireCompletelyDraft(
            ProductFamilyEntity family, List<ProductEntity> members) {
        if (state(family.websiteStatus) != PublicationState.DRAFT
                || state(family.orderAppStatus) != PublicationState.DRAFT
                || state(family.catalogueStatus) != PublicationState.DRAFT
                || members.stream().anyMatch(member ->
                        state(member.websiteStatus) != PublicationState.DRAFT
                                || state(member.orderAppStatus) != PublicationState.DRAFT)) {
            throw new BusinessRuleException(
                    "Identiteit kan alleen worden gefinaliseerd wanneer familie en alle SKU's "
                            + "op elk kanaal concept zijn");
        }
    }

    private void requireIdentityAvailable(
            ProductFamilyEntity family,
            List<ProductEntity> members,
            String familyKey,
            String publicHandle,
            Set<String> canonicalVariantKeys) {
        ProductFamilyEntity keyOwner = families.find("familyKey", familyKey).firstResult();
        if (keyOwner != null && !Objects.equals(keyOwner.id, family.id)) {
            throw new BusinessRuleException("Familiecode " + familyKey + " bestaat al");
        }
        ProductFamilyEntity handleOwner = families.find("publicHandle", publicHandle).firstResult();
        if (handleOwner != null && !Objects.equals(handleOwner.id, family.id)) {
            throw new BusinessRuleException("Publieke handle " + publicHandle + " bestaat al");
        }
        Set<Long> memberIds = members.stream().map(member -> member.id).collect(
                java.util.stream.Collectors.toSet());
        for (String variantKey : canonicalVariantKeys) {
            ProductEntity keyVariant = products.find(
                    "canonicalVariantKey", variantKey).firstResult();
            if (keyVariant != null && !memberIds.contains(keyVariant.id)) {
                throw new BusinessRuleException(
                        "Canonieke variantcode " + variantKey + " bestaat al");
            }
        }
    }

    private void requireStablePublicIdentity(
            ProductFamilyEntity current, ProductFamilyDto request) {
        if (request == null) throw new BusinessRuleException("Geen productfamilie meegestuurd");
        String requestedFamilyKey = optional(request.familyKey());
        if (request.familyKey() != null
                && !Objects.equals(current.familyKey, requestedFamilyKey)) {
            throw new UnprocessableBusinessRuleException(
                    "Familiecode is een vaste technische sleutel en kan na aanmaak niet "
                            + "worden gewijzigd. Pas voor klanten de publieke naam aan.");
        }
        String requestedHandle = optional(request.publicHandle());
        if (request.publicHandle() != null
                && !Objects.equals(current.publicHandle, requestedHandle)) {
            throw new UnprocessableBusinessRuleException(
                    "Publieke handle is na aanmaak een vaste URL-sleutel en kan niet via "
                            + "de gewone productfamilie-editor worden gewijzigd. Gebruik "
                            + "hiervoor een gecontroleerde URL-migratie met redirects.");
        }
    }

    @POST @Path("/{id}/images") @Consumes(MediaType.MULTIPART_FORM_DATA) @Transactional
    public ProductFamilyDto uploadImage(
            @PathParam("id") long id,
            @RestForm("file") FileUpload file,
            @RestForm("variantProductId") Long variantProductId,
            @RestForm("variantExternalId") String variantExternalId,
            @RestForm("variantColor") String variantColor) throws IOException {
        if (file == null) throw new BadRequestException("Geen fotobestand meegestuurd");
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductEntity variant = variantProductId == null
                ? null : familyImageVariants.requireMember(family, variantProductId);
        PhotoUploadPolicy.ValidatedPhoto upload;
        try (InputStream input = Files.newInputStream(file.uploadedFile())) {
            upload = PhotoUploadPolicy.validate(file.fileName(), input);
        }
        String checksum = sha256(upload.bytes());
        String sourceKey = "admin-" + checksum;
        Optional<ProductFamilyPhotoEntity> existing = family.photos.stream()
                .filter(photo -> sourceKey.equals(photo.sourceKey)).findFirst();
        if (existing.isPresent()) return dto(family);

        /* Decode and render before storing either blob: corrupt images cannot leave a half upload. */
        PhotoRenditionService.Rendition small = photoRenditions.small(upload);
        String largeStorageKey = "sha256-" + checksum + extension(upload.contentType());
        PhotoStorage.Stored largeStored = photoStorage.storeKnown(
                largeStorageKey, upload.originalFilename(), upload.contentType(), upload.bytes());
        String smallStorageKey = "sha256-" + small.sha256() + small.extension();
        PhotoStorage.Stored smallStored = Objects.equals(smallStorageKey, largeStorageKey)
                ? largeStored
                : photoStorage.storeKnown(
                        smallStorageKey, small.filename(), small.contentType(), small.bytes());
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = upload.originalFilename();
        photo.originalWidthPx = largeStored.widthPx();
        photo.originalHeightPx = largeStored.heightPx();
        photo.smallStorageKey = smallStorageKey;
        photo.smallContentType = small.contentType();
        photo.smallSha256 = small.sha256();
        photo.smallSizeBytes = smallStored.sizeBytes();
        photo.smallWidthPx = smallStored.widthPx();
        photo.smallHeightPx = smallStored.heightPx();
        photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
        photo.largeStorageKey = largeStorageKey;
        photo.largeContentType = upload.contentType();
        photo.largeSha256 = checksum;
        photo.largeSizeBytes = largeStored.sizeBytes();
        photo.largeWidthPx = largeStored.widthPx();
        photo.largeHeightPx = largeStored.heightPx();
        photo.position = family.photos.size();
        if (variant == null) {
            photo.variantExternalId = optional(variantExternalId);
            photo.variantColor = optional(variantColor);
        } else {
            familyImageVariants.assign(photo, variant);
        }
        photo.altTextSource = "ADMIN";
        photo.altTextsJson = "[]";
        /* Uploading is an internal asset action. Publication is a separate, visible command. */
        photo.publishedChannelsJson = "[]";
        family.photos.add(photo);
        families.flush();
        familyPhotoCompatibility.sync(family);
        recordPhotoUploaded(family, photo);
        return changed(family);
    }

    public record VariantLinkRequest(Long variantProductId) {}

    /** Links a family image to one SKU by stable product id, or null to make it family-wide. */
    @PUT @Path("/{id}/images/{imageId}/variant") @Transactional
    public ProductFamilyDto linkImageVariant(
            @PathParam("id") long id,
            @PathParam("imageId") long imageId,
            VariantLinkRequest request) {
        if (request == null) throw new BusinessRuleException("Geen variantkoppeling meegestuurd");
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        Long beforeVariantId = variantProductId(photo);
        familyImageVariants.link(family, photo, request.variantProductId());
        galleryGuard.validate(family);
        Long afterVariantId = variantProductId(photo);
        recordPhotoActivity(
                family,
                afterVariantId == null
                        ? "Variantkoppeling van foto verwijderd"
                        : beforeVariantId == null
                                ? "Foto aan variant gekoppeld"
                                : "Variantkoppeling van foto aangepast",
                ActivityChangeSet.create().add(
                        photoField(photo, "variantProductId"),
                        "Variantkoppeling foto #" + photo.id,
                        beforeVariantId,
                        afterVariantId));
        return changed(family);
    }

    @PUT @Path("/{id}/images/order") @Transactional
    public ProductFamilyDto reorderImages(@PathParam("id") long id, List<Long> imageIds) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        Map<Long, Integer> beforePositions = photoPositions(family);
        List<Long> wanted = imageIds == null ? List.of() : imageIds;
        if (wanted.size() != family.photos.size() || new HashSet<>(wanted).size() != wanted.size()
                || family.photos.stream().anyMatch(photo -> !wanted.contains(photo.id))) {
            throw new BusinessRuleException("De fotovolgorde moet elke familiefoto exact één keer bevatten");
        }
        Map<Long, Integer> positions = new HashMap<>();
        for (int index = 0; index < wanted.size(); index++) positions.put(wanted.get(index), index);
        family.photos.forEach(photo -> photo.position = positions.get(photo.id));
        family.photos.sort(Comparator.comparingInt(photo -> photo.position));
        families.flush();
        familyPhotoCompatibility.sync(family);
        ActivityChangeSet changes = ActivityChangeSet.create();
        Map<Long, Integer> afterPositions = photoPositions(family);
        beforePositions.forEach((photoId, beforePosition) -> changes.add(
                "photo." + photoId + ".position",
                "Positie foto #" + photoId,
                displayPosition(beforePosition),
                displayPosition(afterPositions.get(photoId))));
        recordPhotoActivity(
                ActivityLogService.ACTION_PHOTO_REORDERED,
                family,
                "Fotovolgorde aangepast",
                changes);
        return changed(family);
    }

    public record AltRequest(Language language, String alt) {}

    public record ImagePublicationRequest(List<CatalogChannel> channels) {}

    /**
     * Explicit public action used by the image menu. A right-click UI may shortcut to this route,
     * but publication remains a first-class authenticated command rather than an upload side effect.
     */
    @PUT @Path("/{id}/images/{imageId}/publication") @Transactional
    public ProductFamilyDto setImagePublication(
            @PathParam("id") long id,
            @PathParam("imageId") long imageId,
            ImagePublicationRequest request) {
        if (request == null || request.channels() == null) {
            throw new BusinessRuleException(
                    "Kies voor welke kanalen de foto gepubliceerd wordt; geen kanaal betekent intern");
        }
        if (request.channels().stream().anyMatch(Objects::isNull)) {
            throw new BusinessRuleException("Een publicatiekanaal mag niet leeg zijn");
        }
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        List<CatalogChannel> beforeChannels = photoPublication.publishedChannels(photo);
        List<CatalogChannel> wantedChannels = canonicalChannels(request.channels());
        if (beforeChannels.equals(wantedChannels)) return dto(family);
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        if (!wantedChannels.isEmpty() && !photoPublication.isEligible(photo, members)) {
            throw new BusinessRuleException(
                    "Foto kan nog niet gepubliceerd worden: voeg geldige afmetingen en minstens "
                            + "één alt-tekst toe en koppel ze aan een actieve variant of de hele familie");
        }
        photoPublication.replacePublishedChannels(photo, wantedChannels);
        galleryGuard.validate(family);
        families.flush();
        recordPhotoActivity(
                family,
                "Fotopublicatie aangepast",
                ActivityChangeSet.create().add(
                        photoField(photo, "publishedChannels"),
                        "Publicatiekanalen foto #" + photo.id,
                        channelLabel(beforeChannels),
                        channelLabel(wantedChannels)));
        return changed(family);
    }

    @PUT @Path("/{id}/images/{imageId}/alt") @Transactional
    public ProductFamilyDto setImageAlt(@PathParam("id") long id,
                                        @PathParam("imageId") long imageId,
                                        AltRequest request) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        if (request == null || request.language() == null) {
            throw new BusinessRuleException("Taal is verplicht voor een alt-tekst");
        }
        Map<Language, String> values = new EnumMap<>(Language.class);
        for (ProductFamilyDto.AltTextDto current : ProductFamilyDto.read(
                json, photo.altTextsJson,
                new TypeReference<List<ProductFamilyDto.AltTextDto>>() {})) {
            values.put(current.language(), current.alt());
        }
        String alt = optional(request.alt());
        String beforeAlt = values.get(request.language());
        if (Objects.equals(beforeAlt, alt)) return dto(family);
        if (alt == null) values.remove(request.language()); else values.put(request.language(), alt);
        photo.altTextsJson = write(values.entrySet().stream()
                .map(entry -> new ProductFamilyDto.AltTextDto(entry.getKey(), entry.getValue()))
                .toList());
        galleryGuard.validate(family);
        families.flush();
        recordPhotoActivity(
                family,
                alt == null ? "Alt-tekst verwijderd" : "Alt-tekst aangepast",
                ActivityChangeSet.create()
                        .add(
                                photoField(photo, "alt." + request.language() + ".present"),
                                "Alt-tekst aanwezig (" + request.language() + ")",
                                beforeAlt != null,
                                alt != null)
                        .privateValue(
                                photoField(photo, "alt." + request.language()),
                                "Alt-tekst (" + request.language() + ") foto #" + photo.id,
                                beforeAlt,
                                alt));
        return changed(family);
    }

    @DELETE @Path("/{id}/images/{imageId}") @Transactional
    public ProductFamilyDto deleteImage(@PathParam("id") long id, @PathParam("imageId") long imageId) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        int beforePosition = photo.position;
        Long beforeVariantId = variantProductId(photo);
        List<CatalogChannel> beforeChannels = photoPublication.publishedChannels(photo);
        String small = photo.smallStorageKey;
        String large = photo.largeStorageKey;
        family.photos.remove(photo);
        for (int index = 0; index < family.photos.size(); index++) family.photos.get(index).position = index;
        galleryGuard.validate(family);
        families.flush();
        familyPhotoCompatibility.sync(family);
        photoReferences.deleteIfUnreferenced(small);
        if (!Objects.equals(small, large)) photoReferences.deleteIfUnreferenced(large);
        recordPhotoActivity(
                ActivityLogService.ACTION_PHOTO_DELETED,
                family,
                "Foto verwijderd",
                ActivityChangeSet.create()
                        .add(
                                "photo." + imageId + ".deleted",
                                "Foto #" + imageId + " verwijderd",
                                false,
                                true)
                        .add(
                                "photo." + imageId + ".position",
                                "Positie foto #" + imageId,
                                displayPosition(beforePosition),
                                null)
                        .add(
                                "photo." + imageId + ".variantProductId",
                                "Variantkoppeling foto #" + imageId,
                                beforeVariantId,
                                null)
                        .add(
                                "photo." + imageId + ".publishedChannels",
                                "Publicatiekanalen foto #" + imageId,
                                channelLabel(beforeChannels),
                                null));
        return changed(family);
    }

    @GET @Path("/{id}/images/{imageId}/{rendition}") @Produces(MediaType.WILDCARD)
    public Response image(@PathParam("id") long id, @PathParam("imageId") long imageId,
                          @PathParam("rendition") String rendition) {
        ProductFamilyPhotoEntity photo = photo(family(id), imageId);
        boolean small = "small".equalsIgnoreCase(rendition);
        if (!small && !"large".equalsIgnoreCase(rendition)) throw new NotFoundException("Foto", imageId);
        String key = small ? photo.smallStorageKey : photo.largeStorageKey;
        String type = small ? photo.smallContentType : photo.largeContentType;
        return PhotoResponses.inline(photoStorage.read(key), type, photo.originalFilename)
                .header("Cache-Control", "private, max-age=60").build();
    }

    private void applyEditable(
            ProductFamilyEntity family, ProductFamilyDto request, boolean initializeTexts) {
        if (request == null) throw new BusinessRuleException("Geen productfamilie meegestuurd");
        if (initializeTexts) {
            family.familyKey = required(request.familyKey(), "Familiecode", MAX_SHORT);
            family.publicHandle = handle(request.publicHandle());
        }
        family.active = request.active();
        family.name = required(request.name(), "Naam", MAX_SHORT);
        family.summary = bounded(request.summary(), MAX_SUMMARY, "Samenvatting");
        family.description = bounded(request.description(), MAX_LONG, "Beschrijving");
        family.format = bounded(request.format(), MAX_SHORT, "Formaat");
        family.highlightsJson = writeBounded(
                validHighlights(request.highlights()), MAX_LONG, "Highlights");
        family.productPosition = request.productPosition();
        family.cardFeaturedProductId = request.cardFeaturedProductId();
        family.tagsJson = writeBounded(request.tags(), MAX_LONG, "Tags");
        family.websiteStatus = state(request.websiteStatus());
        family.orderAppStatus = state(request.orderAppStatus());
        family.catalogueStatus = state(request.catalogueStatus());
        family.seoTitle = bounded(request.seoTitle(), MAX_SHORT, "SEO-titel");
        family.seoDescription = bounded(
                request.seoDescription(), MAX_SUMMARY, "SEO-beschrijving");
        family.updatedAt = Instant.now();
        if (request.dimensions() != null) {
            family.dimensionLength = request.dimensions().length();
            family.dimensionWidth = request.dimensions().width();
            family.dimensionHeight = request.dimensions().height();
            family.dimensionUnit = bounded(
                    request.dimensions().unit(), MAX_SHORT, "Afmetingseenheid");
            family.dimensionRaw = bounded(request.dimensions().raw(), 1_000, "Bronafmeting");
        }
        applyCategory(family, request);
        /* Existing public copy is owned by the revisioned atomic product-translation endpoint.
           The general family PUT may initialize a new family but cannot clobber later edits. */
        if (initializeTexts) replaceTexts(family, request.texts());
        /* Imported package observations are audit/master data. The reduced general family form
           must never rewrite their owner, source, confidence or operational meaning. */
        replaceCollections(family, request.collections());
        familyCollections.alignPrimary(family);
    }

    private void applyCategory(ProductFamilyEntity family, ProductFamilyDto request) {
        if (request.categoryId() != null) {
            CategoryEntity category = categories.findById(
                    request.categoryId(), LockModeType.PESSIMISTIC_WRITE);
            if (category == null) throw new BusinessRuleException("Onbekende categorie " + request.categoryId());
            /* A family update already owns the family lock. Refresh while holding the category
               row so a concurrent category editor cannot leave cached name/order metadata. */
            categories.getEntityManager().refresh(category, LockModeType.PESSIMISTIC_WRITE);
            family.categoryId = category.id;
            family.categoryKey = CategoryPublicKey.from(category.code);
            family.categoryName = category.name;
            family.categoryPosition = category.position;
        } else {
            family.categoryId = null;
            family.categoryKey = optional(request.categoryKey());
            family.categoryName = optional(request.categoryName());
            family.categoryPosition = request.categoryPosition();
        }
    }

    private void replaceTexts(ProductFamilyEntity family, List<ProductFamilyDto.TextDto> texts) {
        Set<Language> seen = EnumSet.noneOf(Language.class);
        List<ProductFamilyTextEntity> replacements = new ArrayList<>();
        for (ProductFamilyDto.TextDto input : safeList(texts)) {
            if (input == null || input.language() == null || !seen.add(input.language())) {
                throw new BusinessRuleException("Elke familietaal mag exact één keer voorkomen");
            }
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = input.language();
            text.name = bounded(input.name(), MAX_SHORT, "Vertaalde familienaam");
            text.summary = bounded(input.summary(), MAX_SUMMARY, "Vertaalde samenvatting");
            text.description = bounded(input.description(), MAX_LONG, "Vertaalde beschrijving");
            text.format = bounded(input.format(), MAX_SHORT, "Vertaald formaat");
            text.highlightsJson = writeBounded(
                    validHighlights(input.highlights()), MAX_LONG, "Vertaalde highlights");
            text.seoTitle = bounded(input.seoTitle(), MAX_SHORT, "Vertaalde SEO-titel");
            text.seoDescription = bounded(
                    input.seoDescription(), MAX_SUMMARY, "Vertaalde SEO-beschrijving");
            replacements.add(text);
        }
        family.texts.clear();
        family.texts.addAll(replacements);
    }

    private void replaceCollections(ProductFamilyEntity family,
                                    List<ProductFamilyDto.CollectionDto> requested) {
        familyCollections.replaceMemberships(family, requested == null ? null : requested.stream()
                .map(input -> new FamilyCollectionAlignmentService.MembershipRequest(
                        input.key(), input.position(), input.primary()))
                .toList());
    }

    private ProductFamilyDto dto(ProductFamilyEntity family) {
        return familyDtos.from(family);
    }

    private ProductFamilyDto changed(ProductFamilyEntity family) {
        /* No completeness veto here: editing a live family (a rename, a
           photo) must go through. The full check runs where a channel is
           switched on; what is missing stays visible on the family. */
        queueWebsite();
        return dto(family);
    }

    private void recordFamilyCreated(ProductFamilyEntity family) {
        recordFamilyActivity(
                ActivityLogService.ACTION_CREATED,
                family,
                "Productreeks aangemaakt",
                familyChanges(FamilyAuditSnapshot.empty(), FamilyAuditSnapshot.from(family)));
    }

    private void recordFamilyUpdated(
            ProductFamilyEntity family, FamilyAuditSnapshot before) {
        recordFamilyActivity(
                ActivityLogService.ACTION_UPDATED,
                family,
                "Productreeks bijgewerkt",
                familyChanges(before, FamilyAuditSnapshot.from(family)));
    }

    private void recordPhotoUploaded(
            ProductFamilyEntity family, ProductFamilyPhotoEntity photo) {
        recordPhotoActivity(
                ActivityLogService.ACTION_PHOTO_ADDED,
                family,
                "Foto toegevoegd",
                ActivityChangeSet.create()
                        .add(
                                photoField(photo, "uploaded"),
                                "Foto #" + photo.id + " toegevoegd",
                                false,
                                true)
                        .add(
                                photoField(photo, "position"),
                                "Positie foto #" + photo.id,
                                null,
                                displayPosition(photo.position))
                        .add(
                                photoField(photo, "variantProductId"),
                                "Variantkoppeling foto #" + photo.id,
                                null,
                                variantProductId(photo))
                        .add(
                                photoField(photo, "contentType"),
                                "Bestandsformaat foto #" + photo.id,
                                null,
                                photo.largeContentType)
                        .add(
                                photoField(photo, "widthPx"),
                                "Breedte foto #" + photo.id,
                                null,
                                photo.largeWidthPx)
                        .add(
                                photoField(photo, "heightPx"),
                                "Hoogte foto #" + photo.id,
                                null,
                                photo.largeHeightPx));
    }

    private void recordPhotoActivity(
            ProductFamilyEntity family, String summary, ActivityChangeSet changes) {
        recordFamilyActivity(
                ActivityLogService.ACTION_UPDATED, family, summary, changes);
    }

    private void recordPhotoActivity(
            String action,
            ProductFamilyEntity family,
            String summary,
            ActivityChangeSet changes) {
        recordFamilyActivity(action, family, summary, changes);
    }

    private void recordFamilyActivity(
            String action,
            ProductFamilyEntity family,
            String summary,
            ActivityChangeSet changes) {
        if (activity == null || !activity.isResolvable()) return;
        if (changes == null) return;
        var details = changes.build();
        if (details.isEmpty()) return;
        activity.get().record(
                action,
                ActivityLogService.ENTITY_PRODUCT_FAMILY,
                String.valueOf(family.id),
                family.name,
                summary,
                details);
    }

    private static ActivityChangeSet familyChanges(
            FamilyAuditSnapshot before, FamilyAuditSnapshot after) {
        return ActivityChangeSet.create()
                .add("familyKey", "Familiecode", before.familyKey(), after.familyKey())
                .add("publicHandle", "Publieke handle", before.publicHandle(), after.publicHandle())
                .add("active", "Actief", before.active(), after.active())
                .privateValue("name", "Naam", before.name(), after.name())
                .privateValue("summary", "Samenvatting", before.summary(), after.summary())
                .privateValue("description", "Beschrijving", before.description(), after.description())
                .privateValue("format", "Formaattekst", before.format(), after.format())
                .privateValue("highlights", "Highlights", before.highlights(), after.highlights())
                .add("categoryId", "Categorie", before.categoryId(), after.categoryId())
                .add("categoryKey", "Categoriecode", before.categoryKey(), after.categoryKey())
                .add(
                        "categoryPosition",
                        "Categoriepositie",
                        before.categoryPosition(),
                        after.categoryPosition())
                .add(
                        "productPosition",
                        "Productpositie",
                        before.productPosition(),
                        after.productPosition())
                .add(
                        "cardFeaturedProductId",
                        "Uitgelicht kaartproduct",
                        before.cardFeaturedProductId(),
                        after.cardFeaturedProductId())
                .add(
                        "primaryCollection",
                        "Primaire collectie",
                        before.primaryCollection(),
                        after.primaryCollection())
                .add(
                        "collectionCount",
                        "Aantal collecties",
                        before.collectionCount(),
                        after.collectionCount())
                .privateValue(
                        "collections",
                        "Collectielidmaatschappen",
                        before.collections(),
                        after.collections())
                .privateValue("tags", "Tags", before.tags(), after.tags())
                .add(
                        "websiteStatus",
                        "Websitestatus",
                        before.websiteStatus(),
                        after.websiteStatus())
                .add(
                        "orderAppStatus",
                        "Orderapp-status",
                        before.orderAppStatus(),
                        after.orderAppStatus())
                .add(
                        "catalogueStatus",
                        "Catalogusstatus",
                        before.catalogueStatus(),
                        after.catalogueStatus())
                .privateValue("seoTitle", "SEO-titel", before.seoTitle(), after.seoTitle())
                .privateValue(
                        "seoDescription",
                        "SEO-beschrijving",
                        before.seoDescription(),
                        after.seoDescription())
                .add(
                        "dimensionLength",
                        "Lengte",
                        before.dimensionLength(),
                        after.dimensionLength())
                .add(
                        "dimensionWidth",
                        "Breedte",
                        before.dimensionWidth(),
                        after.dimensionWidth())
                .add(
                        "dimensionHeight",
                        "Hoogte",
                        before.dimensionHeight(),
                        after.dimensionHeight())
                .add(
                        "dimensionUnit",
                        "Afmetingseenheid",
                        before.dimensionUnit(),
                        after.dimensionUnit())
                .privateValue(
                        "dimensionRaw",
                        "Ruwe afmetingsnotitie",
                        before.dimensionRaw(),
                        after.dimensionRaw())
                .add(
                        "translationCount",
                        "Aantal vertalingen",
                        before.translationCount(),
                        after.translationCount())
                .privateValue(
                        "translations",
                        "Vertalingen",
                        before.translations(),
                        after.translations());
    }

    private static String photoField(ProductFamilyPhotoEntity photo, String suffix) {
        return "photo." + photo.id + "." + suffix;
    }

    private static Long variantProductId(ProductFamilyPhotoEntity photo) {
        return photo.variantProduct == null ? null : photo.variantProduct.id;
    }

    private static Map<Long, Integer> photoPositions(ProductFamilyEntity family) {
        Map<Long, Integer> positions = new LinkedHashMap<>();
        family.photos.stream()
                .sorted(Comparator.comparingInt(item -> item.position))
                .forEach(photo -> positions.put(photo.id, photo.position));
        return positions;
    }

    private static Integer displayPosition(Integer storedPosition) {
        return storedPosition == null ? null : storedPosition + 1;
    }

    private static List<CatalogChannel> canonicalChannels(
            Collection<CatalogChannel> requested) {
        EnumSet<CatalogChannel> selected = EnumSet.noneOf(CatalogChannel.class);
        selected.addAll(requested);
        return Arrays.stream(CatalogChannel.values()).filter(selected::contains).toList();
    }

    private static String channelLabel(Collection<CatalogChannel> channels) {
        if (channels == null || channels.isEmpty()) return "Intern";
        return channels.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", "));
    }

    private record FamilyAuditSnapshot(
            String familyKey,
            String publicHandle,
            Boolean active,
            String name,
            String summary,
            String description,
            String format,
            String highlights,
            Long categoryId,
            String categoryKey,
            Integer categoryPosition,
            Integer productPosition,
            Long cardFeaturedProductId,
            String primaryCollection,
            Integer collectionCount,
            String collections,
            String tags,
            PublicationState websiteStatus,
            PublicationState orderAppStatus,
            PublicationState catalogueStatus,
            String seoTitle,
            String seoDescription,
            java.math.BigDecimal dimensionLength,
            java.math.BigDecimal dimensionWidth,
            java.math.BigDecimal dimensionHeight,
            String dimensionUnit,
            String dimensionRaw,
            Integer translationCount,
            String translations) {

        private static FamilyAuditSnapshot empty() {
            return new FamilyAuditSnapshot(
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        private static FamilyAuditSnapshot from(ProductFamilyEntity family) {
            String memberships = family.collections.stream()
                    .filter(item -> item.collection != null)
                    .sorted(Comparator
                            .comparing((ProductFamilyCollectionEntity item) ->
                                    safe(item.collection.collectionKey))
                            .thenComparingInt(item -> item.position))
                    .map(item -> safe(item.collection.collectionKey)
                            + ":" + item.position + ":" + item.primaryCollection)
                    .collect(java.util.stream.Collectors.joining("|"));
            String primary = family.collections.stream()
                    .filter(item -> item.primaryCollection && item.collection != null)
                    .map(item -> item.collection.collectionKey)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(family.collectionKey);
            String translations = family.texts.stream()
                    .sorted(Comparator.comparing(item -> item.language))
                    .map(item -> String.join("\u001f",
                            String.valueOf(item.language),
                            safe(item.name),
                            safe(item.summary),
                            safe(item.description),
                            safe(item.format),
                            safe(item.highlightsJson),
                            safe(item.seoTitle),
                            safe(item.seoDescription)))
                    .collect(java.util.stream.Collectors.joining("\u001e"));
            return new FamilyAuditSnapshot(
                    family.familyKey,
                    family.publicHandle,
                    family.active,
                    family.name,
                    family.summary,
                    family.description,
                    family.format,
                    family.highlightsJson,
                    family.categoryId,
                    family.categoryKey,
                    family.categoryPosition,
                    family.productPosition,
                    family.cardFeaturedProductId,
                    primary,
                    family.collections.size(),
                    memberships,
                    family.tagsJson,
                    family.websiteStatus,
                    family.orderAppStatus,
                    family.catalogueStatus,
                    family.seoTitle,
                    family.seoDescription,
                    family.dimensionLength,
                    family.dimensionWidth,
                    family.dimensionHeight,
                    family.dimensionUnit,
                    family.dimensionRaw,
                    family.texts.size(),
                    translations);
        }
    }

    private void recordWebsiteVisibility(ProductFamilyEntity family, boolean visible) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(
                ActivityLogService.ACTION_STATUS_CHANGED,
                ActivityLogService.ENTITY_PRODUCT_FAMILY,
                String.valueOf(family.id),
                family.name,
                visible
                        ? "Productreeks zichtbaar gemaakt op de website"
                        : "Productreeks verborgen van de website",
                ActivityChangeSet.create()
                        .add("websiteVisible", "Zichtbaar op website", !visible, visible)
                        .build());
    }

    private void recordIdentityFinalization(ProductFamilyEntity family, int variantCount) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(
                ActivityLogService.ACTION_IDENTITY_FINALIZED,
                ActivityLogService.ENTITY_PRODUCT_FAMILY,
                String.valueOf(family.id),
                family.name,
                "Conceptidentiteit definitief gemaakt voor " + variantCount + " variant(en)",
                ActivityChangeSet.create()
                        .add("identityFinalized", "Conceptidentiteit", "Concept", "Definitief")
                        .add("variantCount", "Aantal varianten", null, variantCount)
                        .build());
    }

    private boolean queueWebsite() {
        if (websiteRebuild != null && familyWrites.websiteBuildReady()) {
            websiteRebuild.queue();
            return true;
        }
        return false;
    }

    private ProductFamilyEntity family(long id) {
        ProductFamilyEntity family = families.findById(id);
        if (family == null) throw new NotFoundException("Productfamilie", id);
        return family;
    }

    private static ProductFamilyPhotoEntity photo(ProductFamilyEntity family, long imageId) {
        return family.photos.stream().filter(item -> Objects.equals(item.id, imageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Familiefoto", imageId));
    }

    private void requireUnique(ProductFamilyEntity family, Long currentId) {
        ProductFamilyEntity key = families.find("familyKey", family.familyKey).firstResult();
        if (key != null && !Objects.equals(key.id, currentId)) {
            throw new BusinessRuleException("Familiecode " + family.familyKey + " bestaat al");
        }
        if (family.publicHandle != null) {
            ProductFamilyEntity handle = families.find("publicHandle", family.publicHandle).firstResult();
            if (handle != null && !Objects.equals(handle.id, currentId)) {
                throw new BusinessRuleException("Publieke handle " + family.publicHandle + " bestaat al");
            }
        }
    }

    private void validateCardFeature(ProductFamilyEntity family) {
        if (family.cardFeaturedProductId != null) {
            featuredProducts.requireFamilyMember(family, family.cardFeaturedProductId);
        }
    }

    private void lockFamily(long familyId) {
        familyWrites.lockFamilies(List.of(familyId));
    }

    private void requireUniqueFamilyPosition(ProductFamilyEntity family, Long currentId) {
        if (family.productPosition < 0) {
            throw new BusinessRuleException("Productpositie binnen de categorie mag niet negatief zijn");
        }
        if (family.categoryId == null && optional(family.categoryKey) == null) return;
        boolean collision = families.listAll().stream()
                .filter(existing -> !Objects.equals(existing.id, currentId))
                .filter(existing -> family.categoryId != null
                        ? Objects.equals(existing.categoryId, family.categoryId)
                        : Objects.equals(optional(existing.categoryKey), optional(family.categoryKey)))
                .anyMatch(existing -> existing.productPosition == family.productPosition);
        if (collision) {
            throw new BusinessRuleException(
                    "Productpositie " + family.productPosition
                            + " is al in gebruik binnen deze categorie");
        }
    }

    /**
     * Publishing is the gate; editing is not. The full completeness check
     * (texts, translations, photos) runs only when this request switches a
     * channel on - renaming a series that is already live must not be held
     * hostage by a missing German footer name. What is missing stays
     * visible as attention points on the family itself.
     */
    private void ensureRequestedPublicationIsValid(
            ProductFamilyEntity family, List<ProductEntity> members,
            boolean publishingNow, boolean readyingNow) {
        List<String> issues = ProductFamilyDto.publicationIssues(family, members, json);
        if (localization != null) {
            List<String> localized = localization.issues(family, members);
            if (!localized.isEmpty()) {
                List<String> combined = new ArrayList<>(issues);
                combined.addAll(localized);
                issues = List.copyOf(combined);
            }
        }
        if (publishingNow && isPublished(family) && !issues.isEmpty()) {
            throw new BusinessRuleException("Productfamilie kan nog niet gepubliceerd worden: "
                    + String.join("; ", issues));
        }
        boolean anyReady = family.websiteStatus == PublicationState.READY
                || family.orderAppStatus == PublicationState.READY
                || family.catalogueStatus == PublicationState.READY;
        List<String> readyBlockers = issues.stream().filter(issue ->
                issue.equals(FamilyVariantRules.OPTION_ISSUE)
                        || issue.equals(FamilyVariantRules.POSITION_ISSUE)
                        || family.websiteStatus == PublicationState.READY
                            && issue.startsWith("website.")
                        || family.catalogueStatus == PublicationState.READY
                            && issue.startsWith("catalog.")
                        || family.websiteStatus == PublicationState.READY
                            && issue.equals("Kleurstaal ontbreekt voor een actieve gekleurde variant"))
                .toList();
        if (readyingNow && anyReady && !readyBlockers.isEmpty()) {
            throw new BusinessRuleException(
                    "Productfamilie kan nog niet klaar voor publicatie worden gezet: "
                            + String.join("; ", readyBlockers));
        }
        if (family.id != null && (publishingNow && isPublished(family)
                || readyingNow && anyReady)) {
            familyWrites.validateFamilies(
                    List.of(family.id), ProductFamilyWriteGuard.WriteKind.PUBLICATION);
        }
    }

    private static boolean isPublished(ProductFamilyEntity family) {
        return family.websiteStatus == PublicationState.PUBLISHED
                || family.orderAppStatus == PublicationState.PUBLISHED
                || family.catalogueStatus == PublicationState.PUBLISHED;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException exception) {
            throw new BusinessRuleException("Kan productfamiliegegevens niet serialiseren");
        }
    }

    private String writeBounded(Object value, int max, String label) {
        String encoded = write(value);
        if (encoded.length() > max) {
            throw new BusinessRuleException(label + " zijn samen langer dan " + max + " tekens");
        }
        return encoded;
    }

    private static List<String> validHighlights(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = bounded(value, MAX_HIGHLIGHT, "Highlight");
            if (normalized == null) {
                throw new BusinessRuleException("Highlights mogen niet leeg zijn");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is niet beschikbaar", exception);
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private static PublicationState state(PublicationState state) {
        return state == null ? PublicationState.DRAFT : state;
    }
    private static String handle(String value) {
        String handle = bounded(value, MAX_SHORT, "Publieke handle");
        if (handle != null && !handle.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessRuleException("Publieke handle mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
        return handle;
    }
    private static String technicalKey(String value, String label) {
        String key = required(value, label, MAX_SHORT);
        if (!key.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessRuleException(
                    label + " mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
        return key;
    }
    private static String required(String value, String label) {
        return required(value, label, Integer.MAX_VALUE);
    }
    private static String required(String value, String label, int max) {
        String result = bounded(value, max, label);
        if (result == null) throw new BusinessRuleException(label + " is verplicht");
        return result;
    }
    private static String bounded(String value, int max, String label) {
        String result = optional(value);
        if (result != null && result.length() > max) {
            throw new BusinessRuleException(label + " is langer dan " + max + " tekens");
        }
        return result;
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static <T> List<T> safeList(List<T> value) { return value == null ? List.of() : value; }
}
