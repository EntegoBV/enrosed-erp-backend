package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.FamilyPhotoCompatibilityService;
import be.enrosed.catalog.application.PhotoReferenceService;
import be.enrosed.catalog.application.PhotoUploadPolicy;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
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
    private final CanonicalCatalogDaos.Families families;
    private final CanonicalCatalogDaos.Collections collections;
    private final CanonicalCatalogDaos.ExternalIdentifiers identifiers;
    private final CanonicalCatalogDaos.PriceObservations prices;
    private final CanonicalCatalogDaos.Provenance provenance;
    private final CanonicalCatalogDaos.ImportConflicts conflicts;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final PhotoStorage photoStorage;
    private final PhotoReferenceService photoReferences;
    private final FamilyPhotoCompatibilityService familyPhotoCompatibility;
    private final ObjectMapper json;

    public ProductFamilyResource(
            CanonicalCatalogDaos.Families families,
            CanonicalCatalogDaos.Collections collections,
            CanonicalCatalogDaos.ExternalIdentifiers identifiers,
            CanonicalCatalogDaos.PriceObservations prices,
            CanonicalCatalogDaos.Provenance provenance,
            CanonicalCatalogDaos.ImportConflicts conflicts,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            PhotoStorage photoStorage,
            PhotoReferenceService photoReferences,
            FamilyPhotoCompatibilityService familyPhotoCompatibility,
            ObjectMapper json) {
        this.families = families;
        this.collections = collections;
        this.identifiers = identifiers;
        this.prices = prices;
        this.provenance = provenance;
        this.conflicts = conflicts;
        this.products = products;
        this.categories = categories;
        this.photoStorage = photoStorage;
        this.photoReferences = photoReferences;
        this.familyPhotoCompatibility = familyPhotoCompatibility;
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
        applyEditable(family, request);
        requireUnique(family, null);
        ensureRequestedPublicationIsValid(family, 0);
        families.persist(family);
        families.flush();
        return Response.status(Response.Status.CREATED).entity(dto(family)).build();
    }

    @PUT @Path("/{id}") @Transactional
    public ProductFamilyDto update(@PathParam("id") long id, ProductFamilyDto request) {
        ProductFamilyEntity family = family(id);
        applyEditable(family, request);
        requireUnique(family, id);
        long variants = products.count("familyId = ?1 and active = true", id);
        ensureRequestedPublicationIsValid(family, variants);
        products.update("familyKey = ?1, categoryId = ?2, name = ?3, description = ?4 "
                        + "where familyId = ?5",
                family.familyKey, family.categoryId, family.name, family.description, id);
        families.flush();
        return dto(family);
    }

    @POST @Path("/{id}/images") @Consumes(MediaType.MULTIPART_FORM_DATA) @Transactional
    public ProductFamilyDto uploadImage(
            @PathParam("id") long id,
            @RestForm("file") FileUpload file,
            @RestForm("variantExternalId") String variantExternalId,
            @RestForm("variantColor") String variantColor) throws IOException {
        if (file == null) throw new BadRequestException("Geen fotobestand meegestuurd");
        ProductFamilyEntity family = family(id);
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
        photo.variantExternalId = optional(variantExternalId);
        photo.variantColor = optional(variantColor);
        photo.altTextSource = "ADMIN";
        photo.altTextsJson = "[]";
        family.photos.add(photo);
        families.flush();
        familyPhotoCompatibility.sync(family);
        return dto(family);
    }

    @PUT @Path("/{id}/images/order") @Transactional
    public ProductFamilyDto reorderImages(@PathParam("id") long id, List<Long> imageIds) {
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
        return dto(family);
    }

    public record AltRequest(Language language, String alt) {}

    @PUT @Path("/{id}/images/{imageId}/alt") @Transactional
    public ProductFamilyDto setImageAlt(@PathParam("id") long id,
                                        @PathParam("imageId") long imageId,
                                        AltRequest request) {
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
        families.flush();
        return dto(family);
    }

    @DELETE @Path("/{id}/images/{imageId}") @Transactional
    public ProductFamilyDto deleteImage(@PathParam("id") long id, @PathParam("imageId") long imageId) {
        ProductFamilyEntity family = family(id);
        ProductFamilyPhotoEntity photo = photo(family, imageId);
        if (family.photos.size() == 1 && isPublished(family)) {
            throw new BusinessRuleException("Een gepubliceerde productfamilie moet minstens één foto houden");
        }
        String small = photo.smallStorageKey;
        String large = photo.largeStorageKey;
        family.photos.remove(photo);
        for (int index = 0; index < family.photos.size(); index++) family.photos.get(index).position = index;
        families.flush();
        familyPhotoCompatibility.sync(family);
        photoReferences.deleteIfUnreferenced(small);
        if (!Objects.equals(small, large)) photoReferences.deleteIfUnreferenced(large);
        return dto(family);
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

    private void applyEditable(ProductFamilyEntity family, ProductFamilyDto request) {
        if (request == null) throw new BusinessRuleException("Geen productfamilie meegestuurd");
        family.familyKey = required(request.familyKey(), "Familiecode");
        family.publicHandle = handle(request.publicHandle());
        family.active = request.active();
        family.name = required(request.name(), "Naam");
        family.summary = optional(request.summary());
        family.description = optional(request.description());
        family.format = optional(request.format());
        family.highlightsJson = write(request.highlights());
        family.collectionKey = optional(request.collectionKey());
        family.productPosition = request.productPosition();
        family.tagsJson = write(request.tags());
        family.websiteStatus = state(request.websiteStatus());
        family.orderAppStatus = state(request.orderAppStatus());
        family.catalogueStatus = state(request.catalogueStatus());
        family.seoTitle = optional(request.seoTitle());
        family.seoDescription = optional(request.seoDescription());
        family.updatedAt = Instant.now();
        if (request.dimensions() != null) {
            family.dimensionLength = request.dimensions().length();
            family.dimensionWidth = request.dimensions().width();
            family.dimensionHeight = request.dimensions().height();
            family.dimensionUnit = optional(request.dimensions().unit());
            family.dimensionRaw = optional(request.dimensions().raw());
        }
        applyCategory(family, request);
        replaceTexts(family, request.texts());
        /* Imported package observations are audit/master data. The reduced general family form
           must never rewrite their owner, source, confidence or operational meaning. */
        replaceCollections(family, request.collections());
    }

    private void applyCategory(ProductFamilyEntity family, ProductFamilyDto request) {
        if (request.categoryId() != null) {
            CategoryEntity category = categories.findById(request.categoryId());
            if (category == null) throw new BusinessRuleException("Onbekende categorie " + request.categoryId());
            family.categoryId = category.id;
            family.categoryKey = category.code;
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
        family.texts.clear();
        Set<Language> seen = EnumSet.noneOf(Language.class);
        for (ProductFamilyDto.TextDto input : safeList(texts)) {
            if (input == null || input.language() == null || !seen.add(input.language())) {
                throw new BusinessRuleException("Elke familietaal mag exact één keer voorkomen");
            }
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = input.language();
            text.name = optional(input.name());
            text.summary = optional(input.summary());
            text.description = optional(input.description());
            text.format = optional(input.format());
            text.highlightsJson = write(input.highlights());
            text.seoTitle = optional(input.seoTitle());
            text.seoDescription = optional(input.seoDescription());
            family.texts.add(text);
        }
    }

    private void replaceCollections(ProductFamilyEntity family,
                                    List<ProductFamilyDto.CollectionDto> requested) {
        if (requested == null) return;
        family.collections.clear();
        Set<String> seen = new HashSet<>();
        int primaryCount = 0;
        for (ProductFamilyDto.CollectionDto input : requested) {
            String key = required(input.key(), "Collectiecode");
            if (!seen.add(key)) throw new BusinessRuleException("Dubbele collectiecode " + key);
            ProductCollectionEntity collection = collections.find("collectionKey", key).firstResult();
            if (collection == null) {
                collection = new ProductCollectionEntity();
                collection.collectionKey = key;
                collections.persist(collection);
            }
            collection.name = required(input.name(), "Collectienaam");
            collection.eyebrow = optional(input.eyebrow());
            collection.description = optional(input.description());
            collection.position = input.position();

            ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
            membership.family = family;
            membership.collection = collection;
            membership.position = input.position();
            membership.primaryCollection = input.primary();
            if (membership.primaryCollection) primaryCount++;
            family.collections.add(membership);
        }
        if (primaryCount > 1) {
            throw new BusinessRuleException("Een productfamilie kan maar één primaire collectie hebben");
        }
        if (!family.collections.isEmpty() && primaryCount == 0) {
            family.collections.get(0).primaryCollection = true;
        }
        family.collectionKey = family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst()
                .map(item -> item.collection.collectionKey).orElse(optional(family.collectionKey));
    }

    private ProductFamilyDto dto(ProductFamilyEntity family) {
        return ProductFamilyDto.from(
                family,
                identifiers.list("ownerType = ?1 and familyId = ?2", "FAMILY", family.id),
                prices.list("familyId", family.id),
                provenance.list("ownerType = ?1 and familyId = ?2", "FAMILY", family.id),
                conflicts.list("familyKey", family.familyKey),
                products.count("familyId", family.id), json);
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

    private static void ensureRequestedPublicationIsValid(ProductFamilyEntity family, long variants) {
        if (isPublished(family) && !ProductFamilyDto.publicationIssues(family, variants).isEmpty()) {
            throw new BusinessRuleException("Productfamilie kan nog niet gepubliceerd worden: "
                    + String.join("; ", ProductFamilyDto.publicationIssues(family, variants)));
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
        String handle = optional(value);
        if (handle != null && !handle.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessRuleException("Publieke handle mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
        return handle;
    }
    private static String required(String value, String label) {
        String result = optional(value);
        if (result == null) throw new BusinessRuleException(label + " is verplicht");
        return result;
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static <T> List<T> safeList(List<T> value) { return value == null ? List.of() : value; }
}
