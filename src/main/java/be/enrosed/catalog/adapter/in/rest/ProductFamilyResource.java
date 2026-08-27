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
import be.enrosed.catalog.application.PhotoUploadPolicy;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
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
    private final PhotoReferenceService photoReferences;
    private final FamilyPhotoCompatibilityService familyPhotoCompatibility;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final FamilyImageVariantService familyImageVariants;
    private final PublishedFamilyGalleryGuard galleryGuard;
    private final FamilyMemberCacheService memberCache;
    private final FamilyCollectionAlignmentService familyCollections;
    private final FeaturedProductSelectionService featuredProducts;
    private final ProductFamilyWriteGuard familyWrites;
    private final ObjectMapper json;

    @Inject
    be.enrosed.catalog.application.WebsiteRebuildService websiteRebuild;

    @Inject
    be.enrosed.catalog.application.PublicLocalizationCompletenessService localization;

    public ProductFamilyResource(
            CanonicalCatalogDaos.Families families,
            ProductFamilyDtoFactory familyDtos,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            PhotoStorage photoStorage,
            PhotoReferenceService photoReferences,
            FamilyPhotoCompatibilityService familyPhotoCompatibility,
            FamilyPhotoPublicationPolicy photoPublication,
            FamilyImageVariantService familyImageVariants,
            PublishedFamilyGalleryGuard galleryGuard,
            FamilyMemberCacheService memberCache,
            FamilyCollectionAlignmentService familyCollections,
            FeaturedProductSelectionService featuredProducts,
            ProductFamilyWriteGuard familyWrites,
            ObjectMapper json) {
        this.families = families;
        this.familyDtos = familyDtos;
        this.products = products;
        this.categories = categories;
        this.photoStorage = photoStorage;
        this.photoReferences = photoReferences;
        this.familyPhotoCompatibility = familyPhotoCompatibility;
        this.photoPublication = photoPublication;
        this.familyImageVariants = familyImageVariants;
        this.galleryGuard = galleryGuard;
        this.memberCache = memberCache;
        this.familyCollections = familyCollections;
        this.featuredProducts = featuredProducts;
        this.familyWrites = familyWrites;
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
        queueWebsite();
        return Response.status(Response.Status.CREATED).entity(dto(family)).build();
    }

    @PUT @Path("/{id}") @Transactional
    public ProductFamilyDto update(@PathParam("id") long id, ProductFamilyDto request) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
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
        return changed(family);
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

        String storageKey = "sha256-" + checksum + extension(upload.contentType());
        PhotoStorage.Stored stored = photoStorage.storeKnown(
                storageKey, upload.originalFilename(), upload.contentType(), upload.bytes());
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = upload.originalFilename();
        photo.smallStorageKey = storageKey;
        photo.smallContentType = upload.contentType();
        photo.smallSha256 = checksum;
        photo.smallSizeBytes = stored.sizeBytes();
        photo.smallWidthPx = stored.widthPx();
        photo.smallHeightPx = stored.heightPx();
        photo.largeStorageKey = storageKey;
        photo.largeContentType = upload.contentType();
        photo.largeSha256 = checksum;
        photo.largeSizeBytes = stored.sizeBytes();
        photo.largeWidthPx = stored.widthPx();
        photo.largeHeightPx = stored.heightPx();
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
        familyImageVariants.link(family, photo, request.variantProductId());
        galleryGuard.validate(family);
        return changed(family);
    }

    @PUT @Path("/{id}/images/order") @Transactional
    public ProductFamilyDto reorderImages(@PathParam("id") long id, List<Long> imageIds) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
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
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        if (!request.channels().isEmpty() && !photoPublication.isEligible(photo, members)) {
            throw new BusinessRuleException(
                    "Foto kan nog niet gepubliceerd worden: voeg geldige afmetingen en minstens "
                            + "één alt-tekst toe en koppel ze aan een actieve variant of de hele familie");
        }
        photoPublication.replacePublishedChannels(photo, request.channels());
        galleryGuard.validate(family);
        families.flush();
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
        if (alt == null) values.remove(request.language()); else values.put(request.language(), alt);
        photo.altTextsJson = write(values.entrySet().stream()
                .map(entry -> new ProductFamilyDto.AltTextDto(entry.getKey(), entry.getValue()))
                .toList());
        galleryGuard.validate(family);
        families.flush();
        return changed(family);
    }

    @DELETE @Path("/{id}/images/{imageId}") @Transactional
    public ProductFamilyDto deleteImage(@PathParam("id") long id, @PathParam("imageId") long imageId) {
        lockFamily(id);
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        String small = photo.smallStorageKey;
        String large = photo.largeStorageKey;
        family.photos.remove(photo);
        for (int index = 0; index < family.photos.size(); index++) family.photos.get(index).position = index;
        galleryGuard.validate(family);
        families.flush();
        familyPhotoCompatibility.sync(family);
        photoReferences.deleteIfUnreferenced(small);
        if (!Objects.equals(small, large)) photoReferences.deleteIfUnreferenced(large);
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

    private void queueWebsite() {
        if (websiteRebuild != null && familyWrites.websiteBuildReady()) {
            websiteRebuild.queue();
        }
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
