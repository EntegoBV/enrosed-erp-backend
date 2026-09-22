package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Kind;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Role;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Scope;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.application.ProductPhotoOverviewService.PhotoKey;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Photo commands of the ERP photo section. Each one answers with the fresh overview.
 *
 * Picking a series photo for a role that needs a channel publishes it there first (its other
 * channels stay), so a choice never points at a photo the channel cannot show.
 */
@ApplicationScoped
public class ProductPhotoCommandService {
    private final CatalogDaos.Products products;
    private final CanonicalCatalogDaos.Families families;
    private final ProductFamilyWriteGuard familyWrites;
    private final ProductService productService;
    private final ProductPhotoOverviewService overviews;
    private final PublicFamilyPhotoProjection publicPhotos;
    private final FamilyPhotoPublicationPolicy publication;
    private final FamilyPhotoVariantResolver variants;
    private final FamilyPhotoCompatibilityService compatibility;
    private final FamilyImageVariantService familyImageVariants;
    private final FamilyPhotoUploadService uploads;
    private final PublishedFamilyGalleryGuard galleryGuard;
    private final PhotoStorage photoStorage;
    private final ObjectMapper json;

    @Inject
    WebsiteRebuildService websiteRebuild;

    @Inject
    Instance<ActivityLogService> activity;

    public ProductPhotoCommandService(
            CatalogDaos.Products products,
            CanonicalCatalogDaos.Families families,
            ProductFamilyWriteGuard familyWrites,
            ProductService productService,
            ProductPhotoOverviewService overviews,
            PublicFamilyPhotoProjection publicPhotos,
            FamilyPhotoPublicationPolicy publication,
            FamilyPhotoVariantResolver variants,
            FamilyPhotoCompatibilityService compatibility,
            FamilyImageVariantService familyImageVariants,
            FamilyPhotoUploadService uploads,
            PublishedFamilyGalleryGuard galleryGuard,
            PhotoStorage photoStorage,
            ObjectMapper json) {
        this.products = products;
        this.families = families;
        this.familyWrites = familyWrites;
        this.productService = productService;
        this.overviews = overviews;
        this.publicPhotos = publicPhotos;
        this.publication = publication;
        this.variants = variants;
        this.compatibility = compatibility;
        this.familyImageVariants = familyImageVariants;
        this.uploads = uploads;
        this.galleryGuard = galleryGuard;
        this.photoStorage = photoStorage;
        this.json = json;
    }

    /** A null key hands the role back to the automatic choice. */
    @Transactional
    public ProductPhotoOverviewDto setRole(long productId, Role role, String photoKey) {
        if (role == null) throw new BusinessRuleException("Kies waarvoor de foto gebruikt wordt");
        Locked locked = lock(productId);
        PhotoKey key = photoKey == null || photoKey.isBlank() ? null : PhotoKey.parse(photoKey);
        switch (role) {
            case MAIN -> setLead(locked, PhotoRole.WEBSITE, key);
            case CATALOGUE_VARIANT -> setLead(locked, PhotoRole.CATALOGUE, key);
            case QUOTE -> setQuote(locked, key);
            case CATALOGUE_OVERVIEW, CATALOGUE_DETAIL -> setCatalogueChoice(locked, role, key);
        }
        return overviews.overview(productId);
    }

    /**
     * Turns an own product photo into a series photo of the product's family, in one
     * transaction: the same bytes stay one series photo, the website keeps showing it, and its
     * leads and family choices move along before the own copy is removed.
     */
    @Transactional
    public ProductPhotoOverviewDto promote(long productId, String photoKey, Scope scope) {
        if (scope != Scope.THIS_VARIANT && scope != Scope.ALL_VARIANTS) {
            throw new BusinessRuleException("Kies of de foto alleen voor deze kleur of voor alle kleuren geldt");
        }
        if (photoKey == null || photoKey.isBlank()) throw new BusinessRuleException("Kies een foto");
        PhotoKey key = PhotoKey.parse(photoKey);
        Locked locked = lock(productId);
        ProductFamilyEntity family = requireFamily(locked);
        ProductEntity product = locked.product();
        List<ProductEntity> members = locked.members();
        if (key.kind() != Kind.OWN) throw new BusinessRuleException("Deze foto staat al bij de reeksfoto's");
        ProductPhotoEntity own = ownRow(product, key.id());
        long ownId = own.id;
        long signedOwn = -ownId;

        /* What the public channels show today must survive the move. */
        EnumSet<CatalogChannel> channels = EnumSet.noneOf(CatalogChannel.class);
        for (CatalogChannel channel : CatalogChannel.values()) {
            if (WebsiteQuotePhotoChoice.isAvailable(
                    publicPhotos.images(family, members, channel), signedOwn)) {
                channels.add(channel);
            }
        }
        if (Objects.equals(family.catalogueOverviewPhotoId, signedOwn)
                || Objects.equals(family.catalogueDetailPhotoId, signedOwn)) {
            channels.add(CatalogChannel.CATALOGUE);
        }
        Set<PhotoRole> ownLeads = Arrays.stream(PhotoRole.values())
                .filter(role -> ProductPhotoOverviewService.leads(own, role))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PhotoRole.class)));

        PhotoUploadPolicy.ValidatedPhoto bytes;
        try (InputStream input = photoStorage.read(own.storageKey)) {
            bytes = PhotoUploadPolicy.validate(own.originalFilename, input);
        } catch (IOException exception) {
            throw new BusinessRuleException("De foto kon niet worden ingelezen");
        }
        String checksum = FamilyPhotoUploadService.sha256(bytes.bytes());
        ProductFamilyPhotoEntity series = FamilyPhotoUploadService.existing(family, checksum).orElse(null);
        boolean created = series == null;
        Long beforeVariantId = null;
        if (created) {
            series = uploads.store(family, bytes,
                    scope == Scope.THIS_VARIANT ? product : null, null, null).photo();
        } else {
            beforeVariantId = series.variantProduct == null ? null : series.variantProduct.id;
            /* An existing series photo is widened, never narrowed: whole family when asked,
               or when it belongs to another colour so that colour keeps it too. */
            boolean appliesHere = variants.rank(series, product, members) < 2;
            if (!variants.isFamilyWide(series) && (scope == Scope.ALL_VARIANTS || !appliesHere)) {
                familyImageVariants.assign(series, null);
            }
        }
        families.flush();
        List<CatalogChannel> beforeChannels = publication.publishedChannels(series);
        EnumSet<CatalogChannel> wantedChannels = EnumSet.noneOf(CatalogChannel.class);
        wantedChannels.addAll(beforeChannels);
        wantedChannels.addAll(channels);
        if (!beforeChannels.containsAll(wantedChannels)) {
            publication.requireEligible(series, members);
            publication.replacePublishedChannels(series, wantedChannels);
        }
        families.flush();
        compatibility.sync(family);

        ProductPhotoEntity projection = projectionRow(product, series.id);
        if (projection == null) {
            throw new IllegalStateException("Reeksfoto " + series.id + " heeft geen kopie bij product " + productId);
        }
        for (PhotoRole role : ownLeads) {
            product.photos.forEach(photo -> withoutLead(photo, role));
            withLead(projection, role);
        }
        Long seriesId = series.id;
        ActivityChangeSet changes = ActivityChangeSet.create()
                .add("photo." + seriesId + ".promotedFrom", "Losse foto in reeksfoto #" + seriesId,
                        null, "P" + ownId)
                .add("photo." + seriesId + ".variantProductId", "Variantkoppeling foto #" + seriesId,
                        created ? null : beforeVariantId,
                        series.variantProduct == null ? null : series.variantProduct.id)
                .add("photo." + seriesId + ".publishedChannels", "Publicatiekanalen foto #" + seriesId,
                        created ? null : channelLabel(beforeChannels),
                        channelLabel(publication.publishedChannels(series)));
        if (Objects.equals(family.catalogueOverviewPhotoId, signedOwn)) {
            changes.add("catalogueOverviewPhotoId", "Catalogusoverzicht foto", signedOwn, seriesId);
            family.catalogueOverviewPhotoId = seriesId;
        }
        if (Objects.equals(family.catalogueDetailPhotoId, signedOwn)) {
            changes.add("catalogueDetailPhotoId", "Catalogusdetail foto", signedOwn, seriesId);
            family.catalogueDetailPhotoId = seriesId;
        }
        if (Objects.equals(family.websiteQuotePhotoId, signedOwn)) {
            changes.add("websiteQuotePhotoId", "Foto op 'Vraag een offerte'", signedOwn, seriesId);
            family.websiteQuotePhotoId = seriesId;
        }
        family.updatedAt = Instant.now();
        families.flush();
        /* The regular removal path keeps the product guards, gallery guard, media registry
           and blob clean-up; the catalogue choices that blocked it have just moved. */
        productService.removePhoto(productId, ownId);
        recordFamily(created ? ActivityLogService.ACTION_PHOTO_ADDED : ActivityLogService.ACTION_UPDATED,
                family, created ? "Losse foto in de reeks gezet" : "Dubbele losse foto opgeruimd", changes);
        queueWebsite();
        return overviews.overview(productId);
    }

    private void setLead(Locked locked, PhotoRole role, PhotoKey key) {
        ProductEntity product = locked.product();
        if (key == null) {
            ProductPhotoEntity current = ProductPhotoOverviewService.leadRow(product, role);
            if (current == null) return;
            products.flush();
            productService.setPhotoLead(product.id, current.id, role, false);
            return;
        }
        ProductPhotoEntity row;
        if (key.kind() == Kind.OWN) {
            row = ownRow(product, key.id());
        } else {
            ProductFamilyEntity family = requireFamily(locked);
            ProductFamilyPhotoEntity image = familyPhoto(family, key.id());
            if (variants.rank(image, product, locked.members()) >= 2) {
                throw new BusinessRuleException("Deze reeksfoto hoort bij een andere kleur. "
                        + "Kies een foto van deze kleur of van alle kleuren.");
            }
            boolean published = publish(family, image, locked.members(),
                    role == PhotoRole.WEBSITE ? CatalogChannel.WEBSITE : CatalogChannel.CATALOGUE);
            row = projectionRow(product, image.id);
            if (row == null) {
                families.flush();
                compatibility.sync(family);
                row = projectionRow(product, image.id);
            }
            if (row == null) {
                throw new BusinessRuleException("Deze reeksfoto staat nog niet bij dit product; laad opnieuw");
            }
            if (published) queueWebsite();
        }
        products.flush();
        productService.setPhotoLead(product.id, row.id, role, true);
    }

    private void setQuote(Locked locked, PhotoKey key) {
        ProductFamilyEntity family = requireFamily(locked);
        List<ProductEntity> members = locked.members();
        Long chosen = null;
        if (key != null && key.kind() == Kind.SERIES) {
            ProductFamilyPhotoEntity image = familyPhoto(family, key.id());
            publish(family, image, members, CatalogChannel.WEBSITE);
            if (!WebsiteQuotePhotoChoice.isAvailable(
                    publicPhotos.images(family, members, CatalogChannel.WEBSITE), image.id)) {
                throw new BusinessRuleException("Deze reeksfoto kan niet op de website: "
                        + "koppel ze aan een actieve kleur of aan alle kleuren");
            }
            chosen = image.id;
        } else if (key != null) {
            ProductPhotoEntity own = members.stream().flatMap(member -> member.photos.stream())
                    .filter(photo -> photo.familyPhotoId == null && Objects.equals(photo.id, key.id()))
                    .findFirst().orElseThrow(() -> new NotFoundException("Foto", key.id()));
            if (!WebsiteQuotePhotoChoice.isAvailable(
                    publicPhotos.images(family, members, CatalogChannel.WEBSITE), -own.id)) {
                throw new BusinessRuleException(
                        "Deze losse foto staat niet op de website. Zet hem eerst in de reeks.");
            }
            chosen = -own.id;
        }
        Long before = family.websiteQuotePhotoId;
        if (!Objects.equals(before, chosen)) {
            family.websiteQuotePhotoId = chosen;
            family.updatedAt = Instant.now();
            families.flush();
            recordFamily(ActivityLogService.ACTION_UPDATED, family,
                    chosen == null ? "Offertefoto weer automatisch" : "Offertefoto gekozen",
                    ActivityChangeSet.create().add("websiteQuotePhotoId",
                            "Foto op 'Vraag een offerte'", before, chosen));
        }
        queueWebsite();
    }

    private void setCatalogueChoice(Locked locked, Role role, PhotoKey key) {
        ProductFamilyEntity family = requireFamily(locked);
        List<ProductEntity> members = locked.members();
        Long chosen = null;
        if (key != null) {
            if (key.kind() == Kind.SERIES) {
                publish(family, familyPhoto(family, key.id()), members, CatalogChannel.CATALOGUE);
            }
            chosen = key.signedId();
            Long wanted = chosen;
            boolean allowed = CataloguePhotoChoices.available(family, members, json).stream()
                    .anyMatch(choice -> Objects.equals(choice.id(), wanted));
            if (!allowed) {
                throw new BusinessRuleException("Kies een eigen foto van een actieve variant in deze reeks "
                        + "of een reeksfoto die voor de catalogus gepubliceerd is");
            }
        }
        boolean overview = role == Role.CATALOGUE_OVERVIEW;
        Long before = overview ? family.catalogueOverviewPhotoId : family.catalogueDetailPhotoId;
        if (Objects.equals(before, chosen)) return;
        if (overview) family.catalogueOverviewPhotoId = chosen; else family.catalogueDetailPhotoId = chosen;
        family.updatedAt = Instant.now();
        families.flush();
        recordFamily(ActivityLogService.ACTION_UPDATED, family, "Catalogusfoto’s gekozen",
                ActivityChangeSet.create().add(
                        overview ? "catalogueOverviewPhotoId" : "catalogueDetailPhotoId",
                        overview ? "Catalogusoverzicht foto" : "Catalogusdetail foto",
                        before, chosen));
    }

    /** Adds one channel to a series photo and keeps its others; true when it changed. */
    private boolean publish(ProductFamilyEntity family, ProductFamilyPhotoEntity image,
                            List<ProductEntity> members, CatalogChannel channel) {
        List<CatalogChannel> before = publication.publishedChannels(image);
        if (before.contains(channel)) return false;
        publication.requireEligible(image, members);
        EnumSet<CatalogChannel> wanted = EnumSet.of(channel);
        wanted.addAll(before);
        publication.replacePublishedChannels(image, wanted);
        galleryGuard.validate(family);
        families.flush();
        recordFamily(ActivityLogService.ACTION_UPDATED, family, "Fotopublicatie aangepast",
                ActivityChangeSet.create().add(
                        "photo." + image.id + ".publishedChannels",
                        "Publicatiekanalen foto #" + image.id,
                        channelLabel(before),
                        channelLabel(publication.publishedChannels(image))));
        return true;
    }

    private record Locked(ProductEntity product, ProductFamilyEntity family, List<ProductEntity> members) {}

    /** Same order as every product-photo write: the family first, then the product row. */
    private Locked lock(long productId) {
        ProductEntity observed = products.findById(productId);
        if (observed == null) throw new NotFoundException("Product", productId);
        Long familyId = observed.familyId;
        if (familyId != null) familyWrites.lockFamilies(List.of(familyId));
        Long lockedFamilyId = familyWrites.lockProduct(productId);
        if (!Objects.equals(lockedFamilyId, familyId)) {
            throw new BusinessRuleException(
                    "Product is gelijktijdig naar een ander model verplaatst; laad het opnieuw");
        }
        ProductEntity product = products.findById(productId);
        ProductFamilyEntity family = familyId == null ? null : families.findById(familyId);
        List<ProductEntity> members = family == null ? List.of(product)
                : products.list("familyId = ?1 order by variantPosition, id", familyId);
        return new Locked(product, family, members);
    }

    private static ProductFamilyEntity requireFamily(Locked locked) {
        if (locked.family() == null) {
            throw new BusinessRuleException(
                    "Dit product hoort niet bij een reeks; kies eerst een reeks voor dit product");
        }
        return locked.family();
    }

    private static ProductPhotoEntity ownRow(ProductEntity product, long photoId) {
        return product.photos.stream()
                .filter(photo -> photo.familyPhotoId == null && Objects.equals(photo.id, photoId))
                .findFirst().orElseThrow(() -> new NotFoundException("Foto", photoId));
    }

    private static ProductFamilyPhotoEntity familyPhoto(ProductFamilyEntity family, long imageId) {
        return family.photos.stream().filter(image -> Objects.equals(image.id, imageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Familiefoto", imageId));
    }

    private static ProductPhotoEntity projectionRow(ProductEntity product, long familyPhotoId) {
        return product.photos.stream()
                .filter(photo -> Objects.equals(photo.familyPhotoId, familyPhotoId))
                .findFirst().orElse(null);
    }

    private static void withLead(ProductPhotoEntity photo, PhotoRole role) {
        Set<String> roles = leadRoles(photo);
        roles.add(role.name());
        photo.leadRoles = String.join(",", roles);
    }

    private static void withoutLead(ProductPhotoEntity photo, PhotoRole role) {
        Set<String> roles = leadRoles(photo);
        if (!roles.remove(role.name())) return;
        photo.leadRoles = roles.isEmpty() ? null : String.join(",", roles);
    }

    /** Sorted like the domain mapper writes them, keeping words this version does not know. */
    private static Set<String> leadRoles(ProductPhotoEntity photo) {
        Set<String> roles = new java.util.TreeSet<>();
        if (photo.leadRoles == null) return roles;
        Arrays.stream(photo.leadRoles.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).forEach(roles::add);
        return roles;
    }

    private static String channelLabel(java.util.Collection<CatalogChannel> channels) {
        if (channels == null || channels.isEmpty()) return "Intern";
        return channels.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    private void recordFamily(String action, ProductFamilyEntity family, String summary,
                              ActivityChangeSet changes) {
        if (activity == null || !activity.isResolvable()) return;
        var details = changes.build();
        if (details.isEmpty()) return;
        activity.get().record(action, ActivityLogService.ENTITY_PRODUCT_FAMILY,
                String.valueOf(family.id), family.name, summary, details);
    }

    private void queueWebsite() {
        if (websiteRebuild != null && familyWrites.websiteBuildReady()) websiteRebuild.queue();
    }
}
