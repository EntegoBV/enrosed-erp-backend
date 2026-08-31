package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One-time-safe bridge from the five existing Foam product photos to canonical family galleries.
 *
 * The source product photo remains untouched. A deterministic small rendition and a family-photo
 * row are added only when they are missing, so production and test can use their own existing red
 * source image while sharing the same canonical catalogue identity.
 */
@ApplicationScoped
public class CatalogFoamPhotoBackfillService {
    static final String PRIMARY_SOURCE_KEY = "catalog-primary-red";

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(CatalogFoamPhotoBackfillService.class);

    private static final List<FamilySpec> SPECS = List.of(
            new FamilySpec("foam-half-heart-25", "ENR-ODOO-HALF-HEART-FOAM-25-RED",
                    false),
            new FamilySpec("foam-half-heart-40", "ENR-ODOO-HALF-HEART-FOAM-40-RED",
                    false),
            new FamilySpec("foam-bear-25", "ENR-P06",
                    true),
            new FamilySpec("foam-heart-15", "ENR-P09",
                    false),
            new FamilySpec("foam-bear-with-heart-25", "ENR-P05",
                    false));

    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final ProductFamilyWriteGuard writeGuard;
    private final CatalogMutationLock mutationLock;
    private final ProductVariantLinkService variantLinks;
    private final FamilyCollectionAlignmentService familyCollections;
    private final FamilyMemberCacheService familyMembers;
    private final FamilyImageVariantService imageVariants;
    private final FamilyPhotoPublicationPolicy publication;
    private final FamilyPhotoCompatibilityService compatibility;
    private final PhotoStorage storage;
    private final PhotoRenditionService renditions;
    private final ObjectMapper json;

    public CatalogFoamPhotoBackfillService(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            ProductFamilyWriteGuard writeGuard,
            CatalogMutationLock mutationLock,
            ProductVariantLinkService variantLinks,
            FamilyCollectionAlignmentService familyCollections,
            FamilyMemberCacheService familyMembers,
            FamilyImageVariantService imageVariants,
            FamilyPhotoPublicationPolicy publication,
            FamilyPhotoCompatibilityService compatibility,
            PhotoStorage storage,
            PhotoRenditionService renditions,
            ObjectMapper json) {
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.writeGuard = writeGuard;
        this.mutationLock = mutationLock;
        this.variantLinks = variantLinks;
        this.familyCollections = familyCollections;
        this.familyMembers = familyMembers;
        this.imageVariants = imageVariants;
        this.publication = publication;
        this.compatibility = compatibility;
        this.storage = storage;
        this.renditions = renditions;
        this.json = json;
    }

    @Transactional
    public Result apply() {
        mutationLock.acquire();
        List<Target> targets = resolveTargets();
        lockTargets(targets);

        int canonicalFamilies = 0;
        int canonicalVariants = 0;
        int createdFamilies = 0;
        int createdVariants = 0;
        int linkedPhotos = 0;
        List<ProductFamilyEntity> changedFamilies = new ArrayList<>();

        for (Target target : targets) {
            FamilySpec spec = target.spec();
            ProductEntity red = product(target.redProductId());
            boolean compatibilityChanged = target.familyId() == null;
            ProductFamilyEntity family = red.familyId == null
                    ? createFamily(spec, red)
                    : family(red.familyId);
            if (target.familyId() == null) createdFamilies++;

            ProductFamilyEntity keyOwner = families.find("familyKey", spec.familyKey())
                    .firstResult();
            if (keyOwner != null && !Objects.equals(keyOwner.id, family.id)) {
                throw new IllegalStateException("Canonieke Foam-familiecode bestaat dubbel: "
                        + spec.familyKey());
            }
            if (!Objects.equals(family.familyKey, spec.familyKey())) {
                family.familyKey = spec.familyKey();
                canonicalFamilies++;
            }
            if (family.highlightsJson == null) family.highlightsJson = "[]";
            if (family.tagsJson == null) family.tagsJson = "[]";
            family.updatedAt = Instant.now();

            if (!Objects.equals(red.familyId, family.id)) {
                red.familyId = family.id;
            }
            List<ProductEntity> members = products.list(
                    "familyId = ?1 order by variantPosition, id", family.id);
            if (members.stream().noneMatch(member -> Objects.equals(member.id, red.id))) {
                members = new ArrayList<>(members);
                members.add(red);
            }
            for (ProductEntity member : members) {
                member.familyKey = spec.familyKey();
                String canonicalKey = canonicalVariantKey(spec.familyKey(), member.colour);
                if (canonicalKey != null && !Objects.equals(member.canonicalVariantKey, canonicalKey)) {
                    ProductEntity owner = products.find("canonicalVariantKey", canonicalKey)
                            .firstResult();
                    if (owner != null && !Objects.equals(owner.id, member.id)) {
                        throw new IllegalStateException("Canonieke Foam-variantcode bestaat dubbel: "
                                + canonicalKey);
                    }
                    member.canonicalVariantKey = canonicalKey;
                    canonicalVariants++;
                }
            }

            if (spec.ensureMixed() && members.stream().noneMatch(member ->
                    Objects.equals("foam-bear-25-mixed", member.canonicalVariantKey))) {
                Product duplicate = variantLinks.duplicateAsVariant(
                        red.id, "Mixed", "#DD92C9", red.variantSize);
                ProductEntity mixed = product(duplicate.id());
                mixed.familyKey = spec.familyKey();
                mixed.canonicalVariantKey = "foam-bear-25-mixed";
                createdVariants++;
                compatibilityChanged = true;
            }

            families.flush();
            familyMembers.sync(family);
            if (ensurePrimaryPhoto(family, red)) {
                linkedPhotos++;
                compatibilityChanged = true;
            }
            if (compatibilityChanged) changedFamilies.add(family);
        }

        families.flush();
        for (ProductFamilyEntity family : changedFamilies) compatibility.sync(family);
        LOG.infof("Foam-catalogusbackfill: %d families gecanoniseerd, %d families gemaakt, "
                        + "%d varianten gecanoniseerd, %d varianten gemaakt, %d foto's gekoppeld",
                canonicalFamilies, createdFamilies, canonicalVariants, createdVariants, linkedPhotos);
        return new Result(canonicalFamilies, createdFamilies, canonicalVariants,
                createdVariants, linkedPhotos);
    }

    private List<Target> resolveTargets() {
        List<Target> result = new ArrayList<>();
        for (FamilySpec spec : SPECS) {
            ProductEntity red = products.find("sku", spec.redSku()).firstResult();
            if (red == null) {
                LOG.warnf("Foam-catalogusbackfill overgeslagen voor %s: rode bron-SKU %s ontbreekt",
                        spec.familyKey(), spec.redSku());
                continue;
            }
            result.add(new Target(spec, red.id, red.familyId, red.categoryId));
        }
        return List.copyOf(result);
    }

    /** Locks every existing aggregate in the editor's global family -> product -> category order. */
    private void lockTargets(List<Target> targets) {
        List<Long> familyIds = targets.stream().map(Target::familyId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        writeGuard.lockFamilies(familyIds);

        LinkedHashSet<Long> productIds = new LinkedHashSet<>();
        for (Target target : targets) {
            productIds.add(target.redProductId());
            if (target.familyId() != null) {
                products.list("familyId", target.familyId()).stream()
                        .map(product -> product.id).filter(Objects::nonNull).forEach(productIds::add);
            }
        }
        writeGuard.lockProducts(productIds);

        targets.stream().map(Target::categoryId).filter(Objects::nonNull).distinct().sorted()
                .forEach(categoryId -> categories.findById(
                        categoryId, LockModeType.PESSIMISTIC_WRITE));
    }

    private ProductFamilyEntity createFamily(FamilySpec spec, ProductEntity source) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = spec.familyKey();
        family.publicHandle = source.publicHandle;
        family.active = true;
        family.name = first(source.publicName, source.name, spec.familyKey());
        family.summary = source.description;
        family.description = source.description;
        family.format = source.variantSize;
        family.highlightsJson = "[]";
        family.tagsJson = "[]";
        family.websiteStatus = state(source.websiteStatus) == PublicationState.PUBLISHED
                ? PublicationState.PUBLISHED : PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.PUBLISHED;
        family.productPosition = nextFamilyPosition(source.categoryId);
        family.dimensionLength = source.productLengthCm;
        family.dimensionWidth = source.productWidthCm;
        family.dimensionHeight = source.productHeightCm;
        family.dimensionUnit = "cm";
        family.createdAt = Instant.now();
        family.updatedAt = family.createdAt;
        if (source.categoryId != null) {
            CategoryEntity category = categories.findById(source.categoryId);
            if (category != null) {
                family.categoryId = category.id;
                family.categoryKey = CategoryPublicKey.from(category.code);
                family.categoryName = category.name;
                family.categoryPosition = category.position;
            }
        }
        families.persist(family);
        familyCollections.alignPrimary(family);
        families.flush();
        source.familyId = family.id;
        source.familyKey = spec.familyKey();
        return family;
    }

    private int nextFamilyPosition(Long categoryId) {
        if (categoryId == null) return 0;
        return families.list("categoryId", categoryId).stream()
                .mapToInt(family -> family.productPosition).max().orElse(-1) + 1;
    }

    private boolean ensurePrimaryPhoto(ProductFamilyEntity family, ProductEntity red) {
        ProductFamilyPhotoEntity existing = family.photos.stream()
                .filter(photo -> PRIMARY_SOURCE_KEY.equals(photo.sourceKey)).findFirst().orElse(null);
        if (existing != null) {
            List<CatalogChannel> currentChannels = publication.publishedChannels(existing);
            List<CatalogChannel> requiredChannels = withCatalogueChannel(currentChannels);
            boolean changed = existing.position != 0
                    || existing.variantProduct == null
                    || !Objects.equals(existing.variantProduct.id, red.id)
                    || !currentChannels.equals(requiredChannels);
            makePrimary(family, existing);
            imageVariants.assign(existing, red);
            if (!currentChannels.equals(requiredChannels)) {
                publication.replacePublishedChannels(existing, requiredChannels);
            }
            return changed;
        }

        ProductPhotoEntity source = red.photos.stream()
                .filter(photo -> photo.familyPhotoId == null)
                .min(Comparator.comparingInt(photo -> photo.position)).orElse(null);
        if (source == null) {
            LOG.warnf("Foam-catalogusfoto ontbreekt voor %s: rode SKU %s heeft geen eigen foto",
                    family.familyKey, red.sku);
            return false;
        }

        PhotoUploadPolicy.ValidatedPhoto upload;
        try (InputStream input = storage.read(source.storageKey)) {
            upload = PhotoUploadPolicy.validate(source.originalFilename, input);
        } catch (Exception exception) {
            throw new IllegalStateException("Rode bronfoto kon niet worden ingelezen voor "
                    + family.familyKey, exception);
        }
        PhotoRenditionService.Rendition small = renditions.small(upload);
        String smallStorageKey;
        PhotoStorage.Stored smallStored;
        String checksum = sha256(upload.bytes());
        if (Objects.equals(small.sha256(), checksum)) {
            smallStorageKey = source.storageKey;
            smallStored = new PhotoStorage.Stored(source.storageKey, source.sizeBytes,
                    positive(source.widthPx, small.width()), positive(source.heightPx, small.height()));
        } else {
            smallStorageKey = "sha256-" + small.sha256() + small.extension();
            smallStored = storage.storeKnown(smallStorageKey, small.filename(),
                    small.contentType(), small.bytes());
        }

        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = PRIMARY_SOURCE_KEY;
        photo.sourceAssetId = "product-photo:" + source.id;
        photo.originalFilename = upload.originalFilename();
        photo.originalWidthPx = positive(source.widthPx, small.width());
        photo.originalHeightPx = positive(source.heightPx, small.height());
        photo.smallStorageKey = smallStorageKey;
        photo.smallContentType = small.contentType();
        photo.smallSha256 = small.sha256();
        photo.smallSizeBytes = smallStored.sizeBytes();
        photo.smallWidthPx = positive(smallStored.widthPx(), small.width());
        photo.smallHeightPx = positive(smallStored.heightPx(), small.height());
        photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
        photo.largeStorageKey = source.storageKey;
        photo.largeContentType = upload.contentType();
        photo.largeSha256 = checksum;
        photo.largeSizeBytes = upload.bytes().length;
        photo.largeWidthPx = positive(source.widthPx, small.width());
        photo.largeHeightPx = positive(source.heightPx, small.height());
        photo.position = 0;
        imageVariants.assign(photo, red);
        photo.altTextSource = "CATALOG_BACKFILL";
        photo.altTextsJson = writeAlts(first(family.name, red.publicName, red.name, "Foam roses"));
        publication.replacePublishedChannels(photo, List.of(CatalogChannel.CATALOGUE));
        family.photos.forEach(item -> item.position++);
        family.photos.add(photo);
        family.photos.sort(Comparator.comparingInt(item -> item.position));
        families.flush();
        return true;
    }

    /** Keep an administrator's public-channel choices while retaining the catalogue seed. */
    private static List<CatalogChannel> withCatalogueChannel(List<CatalogChannel> channels) {
        if (channels.contains(CatalogChannel.CATALOGUE)) return channels;
        LinkedHashSet<CatalogChannel> selected = new LinkedHashSet<>(channels);
        selected.add(CatalogChannel.CATALOGUE);
        return List.of(CatalogChannel.values()).stream().filter(selected::contains).toList();
    }

    private static void makePrimary(ProductFamilyEntity family, ProductFamilyPhotoEntity primary) {
        if (primary.position == 0) return;
        family.photos.stream().filter(photo -> photo != primary && photo.position < primary.position)
                .forEach(photo -> photo.position++);
        primary.position = 0;
        family.photos.sort(Comparator.comparingInt(photo -> photo.position));
    }

    private String writeAlts(String alt) {
        try {
            return json.writeValueAsString(List.of(
                    new ProductFamilyDto.AltTextDto(Language.EN, alt)));
        } catch (Exception exception) {
            throw new IllegalStateException("Alt-tekst voor Foam-catalogusfoto kon niet worden gemaakt",
                    exception);
        }
    }

    static String canonicalVariantKey(String familyKey, String colour) {
        String suffix = canonicalColour(colour);
        return suffix == null ? null : familyKey + "-" + suffix;
    }

    private static String canonicalColour(String colour) {
        if (colour == null || colour.isBlank()) return null;
        String value = colour.strip().toLowerCase(Locale.ROOT);
        if (Set.of("red", "rood", "rouge", "rot").contains(value)) return "red";
        if (Set.of("pink", "roze", "rosa").contains(value)) return "pink";
        if (Set.of("mixed", "gemengd", "multicolore", "gemischt", "mixto", "misto",
                "mieszany", "karışık").contains(value)) return "mixed";
        return null;
    }

    static List<String> targetImageKeys() {
        return SPECS.stream().map(spec -> spec.familyKey() + ":" + PRIMARY_SOURCE_KEY).toList();
    }

    private ProductEntity product(long id) {
        ProductEntity product = products.findById(id);
        if (product == null) throw new IllegalStateException("Foam-bronproduct ontbreekt: " + id);
        return product;
    }

    private ProductFamilyEntity family(long id) {
        ProductFamilyEntity family = families.findById(id);
        if (family == null) throw new IllegalStateException("Foam-productfamilie ontbreekt: " + id);
        return family;
    }

    private static int positive(Integer value, int fallback) {
        return value != null && value > 0 ? value : Math.max(1, fallback);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 ontbreekt", exception);
        }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }

    private static PublicationState state(PublicationState value) {
        return value == null ? PublicationState.DRAFT : value;
    }

    record FamilySpec(String familyKey, String redSku, boolean ensureMixed) {}
    private record Target(FamilySpec spec, long redProductId, Long familyId, Long categoryId) {}
    public record Result(int canonicalFamilies, int createdFamilies, int canonicalVariants,
                         int createdVariants, int linkedPhotos) {}
}
