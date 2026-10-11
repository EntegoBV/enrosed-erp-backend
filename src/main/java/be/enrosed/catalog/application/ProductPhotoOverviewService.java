package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.CatalogueAssumptionDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.ChosenForDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Kind;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.OtherPhotoDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PhotoDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceReason;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceScope;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceState;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlacesDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Role;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.RoleChoiceDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Scope;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.VisibilityDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.WebsiteReason;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read model behind the ERP photo section: one list of own and series photos per product and
 * the photo each role resolves to, computed with the same projection as the public channels.
 */
@ApplicationScoped
public class ProductPhotoOverviewService {
    private static final Comparator<ProductPhotoEntity> ROW_ORDER = Comparator
            .comparingInt((ProductPhotoEntity photo) -> photo.position)
            .thenComparing(photo -> photo.id, Comparator.nullsLast(Long::compareTo));

    private final CatalogDaos.Products products;
    private final CanonicalCatalogDaos.Families families;
    private final PublicFamilyPhotoProjection publicPhotos;
    private final FamilyPhotoPublicationPolicy publication;
    private final FamilyPhotoVariantResolver variants;
    private final ObjectMapper json;

    public ProductPhotoOverviewService(
            CatalogDaos.Products products,
            CanonicalCatalogDaos.Families families,
            PublicFamilyPhotoProjection publicPhotos,
            FamilyPhotoPublicationPolicy publication,
            FamilyPhotoVariantResolver variants,
            ObjectMapper json) {
        this.products = products;
        this.families = families;
        this.publicPhotos = publicPhotos;
        this.publication = publication;
        this.variants = variants;
        this.json = json;
    }

    /** "F221" is series photo 221, "P5501" own product photo 5501. */
    public record PhotoKey(Kind kind, long id) {
        public static PhotoKey parse(String raw) {
            String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
            if (value.length() < 2 || (value.charAt(0) != 'F' && value.charAt(0) != 'P')) {
                throw new BusinessRuleException("Onbekende fotosleutel '" + raw + "'");
            }
            long id;
            try {
                id = Long.parseLong(value.substring(1));
            } catch (NumberFormatException invalid) {
                throw new BusinessRuleException("Onbekende fotosleutel '" + raw + "'");
            }
            if (id <= 0) throw new BusinessRuleException("Onbekende fotosleutel '" + raw + "'");
            return new PhotoKey(value.charAt(0) == 'F' ? Kind.SERIES : Kind.OWN, id);
        }

        /** Website and catalogue ids: positive family photo, negative own product photo. */
        public static PhotoKey ofSigned(Long signedId) {
            if (signedId == null || signedId == 0) return null;
            return signedId > 0 ? new PhotoKey(Kind.SERIES, signedId) : new PhotoKey(Kind.OWN, -signedId);
        }

        /** A product photo row: its family source for a projection, itself when owned. */
        public static PhotoKey ofRow(ProductPhotoEntity row) {
            return row.familyPhotoId != null
                    ? new PhotoKey(Kind.SERIES, row.familyPhotoId) : new PhotoKey(Kind.OWN, row.id);
        }

        public long signedId() {
            return kind == Kind.SERIES ? id : -id;
        }

        public String value() {
            return (kind == Kind.SERIES ? "F" : "P") + id;
        }
    }

    @Transactional
    public ProductPhotoOverviewDto overview(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) throw new NotFoundException("Product", productId);
        ProductFamilyEntity family = product.familyId == null ? null : families.findById(product.familyId);
        List<ProductEntity> members = family == null ? List.of(product) : products.list(
                "familyId = ?1 order by variantPosition, id", family.id);

        Map<CatalogChannel, Set<Long>> publicIds = new EnumMap<>(CatalogChannel.class);
        List<ProductFamilyPhotoEntity> catalogueImages = List.of();
        List<ProductFamilyPhotoEntity> websiteImages = family == null
                ? List.of() : publicPhotos.images(family, members, CatalogChannel.WEBSITE);
        for (CatalogChannel channel : CatalogChannel.values()) {
            List<ProductFamilyPhotoEntity> images = family == null ? List.of()
                    : channel == CatalogChannel.WEBSITE ? websiteImages
                    : publicPhotos.images(family, members, channel);
            publicIds.put(channel, images.stream().map(image -> image.id).collect(Collectors.toSet()));
            if (channel == CatalogChannel.CATALOGUE) catalogueImages = images;
        }

        Map<Role, RoleChoiceDto> roles = new EnumMap<>(Role.class);
        put(roles, Role.MAIN, main(product));
        Long quote = null;
        List<CataloguePhotoChoices.Choice> availableChoices = List.of();
        if (family != null) {
            if (family.active) {
                quote = WebsiteQuotePhotoChoice.resolve(family, members, publicPhotos, websiteImages);
                put(roles, Role.QUOTE, choice(quote, Objects.equals(family.websiteQuotePhotoId, quote)));
            }
            availableChoices = CataloguePhotoChoices.available(family, members, json);
            Set<Long> catalogueChoices = availableChoices.stream()
                    .map(CataloguePhotoChoices.Choice::id).collect(Collectors.toSet());
            put(roles, Role.CATALOGUE_OVERVIEW,
                    storedChoice(family.catalogueOverviewPhotoId, catalogueChoices));
            put(roles, Role.CATALOGUE_DETAIL,
                    storedChoice(family.catalogueDetailPhotoId, catalogueChoices));
        }
        /* The catalogue's automatic picks depend on the export settings; only a lead is known. */
        ProductPhotoEntity catalogueLead = leadRow(product, PhotoRole.CATALOGUE);
        if (catalogueLead != null) {
            put(roles, Role.CATALOGUE_VARIANT,
                    new RoleChoiceDto(PhotoKey.ofRow(catalogueLead).value(), true));
        }

        List<PhotoDto> photos = new ArrayList<>();
        product.photos.stream().filter(photo -> photo.familyPhotoId == null).sorted(ROW_ORDER)
                .forEach(photo -> photos.add(own(product, family, members, photo, publicIds, roles)));
        if (family != null) {
            family.photos.stream()
                    .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) ->
                                    variants.rank(image, product, members))
                            .thenComparingInt(image -> image.position)
                            .thenComparing(image -> image.id, Comparator.nullsLast(Long::compareTo)))
                    .forEach(image -> photos.add(series(product, family, members, image, publicIds, roles)));
        }

        Places resolved = new Places(product, family, members, websiteImages, catalogueImages,
                availableChoices, quote);
        PlacesDto places = resolved.all();
        /* The public feed drops a series without a photo and one without a colour to sell. */
        boolean seriesLive = family != null && family.active
                && family.websiteStatus == be.enrosed.catalog.domain.PublicationState.PUBLISHED
                && !websiteImages.isEmpty() && !resolved.selection.isEmpty();

        return new ProductPhotoOverviewDto(
                product.id,
                family == null ? null : family.id,
                family == null ? null : family.name,
                fullLabel(product),
                family == null ? null : family.websiteStatus,
                List.copyOf(photos),
                roles.get(Role.MAIN),
                roles.get(Role.QUOTE),
                roles.get(Role.CATALOGUE_VARIANT),
                roles.get(Role.CATALOGUE_OVERVIEW),
                roles.get(Role.CATALOGUE_DETAIL),
                family == null ? null : "LARGE".equals(family.catalogueDetailSize) ? "LARGE" : "STANDARD",
                places,
                otherPhotos(places, photos, members),
                seriesLive,
                seriesLive && product.active && !product.demo,
                new CatalogueAssumptionDto("BROCHURE", 1,
                        resolved.selection.stream().map(member -> member.id).toList(),
                        resolved.inSelection()));
    }

    /**
     * The explicit WEBSITE lead of this colour; otherwise exactly what quotes, invoices, the
     * portal and the ERP lists print ({@code Product.photoForSalesDocument}): the first series
     * projection, then the first photo. The website's own automatic pick only skips unpublished
     * photos; choosing a Hoofdfoto sets the lead and aligns every channel.
     */
    private RoleChoiceDto main(ProductEntity product) {
        ProductPhotoEntity lead = leadRow(product, PhotoRole.WEBSITE);
        if (lead != null) return new RoleChoiceDto(PhotoKey.ofRow(lead).value(), true);
        List<ProductPhotoEntity> rows = product.photos.stream().sorted(ROW_ORDER).toList();
        ProductPhotoEntity fallback = rows.stream().filter(photo -> photo.familyPhotoId != null)
                .findFirst().orElse(rows.isEmpty() ? null : rows.getFirst());
        return fallback == null ? null : new RoleChoiceDto(PhotoKey.ofRow(fallback).value(), false);
    }

    private PhotoDto own(ProductEntity product, ProductFamilyEntity family, List<ProductEntity> members,
                         ProductPhotoEntity photo, Map<CatalogChannel, Set<Long>> publicIds,
                         Map<Role, RoleChoiceDto> roles) {
        PhotoKey key = new PhotoKey(Kind.OWN, photo.id);
        String base = "/api/products/" + product.id + "/photos/" + photo.id;
        VisibilityDto visibility = visibility(publicIds, key.signedId());
        WebsiteReason reason = !visibility.website() ? null
                : leads(photo, PhotoRole.WEBSITE) ? WebsiteReason.LEAD : WebsiteReason.FALLBACK;
        return new PhotoDto(
                key.value(), Kind.OWN, null, photo.id, Scope.THIS_VARIANT,
                product.id, shortLabel(product, members),
                photo.originalFilename, photo.contentType, photo.widthPx, photo.heightPx, photo.sizeBytes,
                base + "/renditions/small", base + "/renditions/medium", base, base + "/download",
                visibility, reason, false, rolesOf(key, roles),
                duplicateOf(product, family, members, photo), null, photo.position, null,
                chosenFor(product, members, photo));
    }

    private PhotoDto series(ProductEntity product, ProductFamilyEntity family, List<ProductEntity> members,
                            ProductFamilyPhotoEntity image, Map<CatalogChannel, Set<Long>> publicIds,
                            Map<Role, RoleChoiceDto> roles) {
        PhotoKey key = new PhotoKey(Kind.SERIES, image.id);
        String base = "/api/product-families/" + family.id + "/images/" + image.id;
        ProductEntity variant = variants.resolve(image, members);
        Scope scope = switch (variants.rank(image, product, members)) {
            case 0 -> Scope.THIS_VARIANT;
            case 1 -> Scope.ALL_VARIANTS;
            default -> Scope.OTHER_VARIANT;
        };
        ProductPhotoEntity projection = product.photos.stream()
                .filter(photo -> Objects.equals(photo.familyPhotoId, image.id)).findFirst().orElse(null);
        VisibilityDto visibility = visibility(publicIds, image.id);
        return new PhotoDto(
                key.value(), Kind.SERIES, image.id, projection == null ? null : projection.id, scope,
                variant == null ? null : variant.id,
                variant == null ? null : shortLabel(variant, members),
                image.originalFilename, image.largeContentType, image.largeWidthPx, image.largeHeightPx,
                image.largeSizeBytes,
                base + "/small", base + "/medium", base + "/large", base + "/original",
                visibility, visibility.website() ? WebsiteReason.PUBLISHED : null,
                publication.isEligible(image, members), rolesOf(key, roles), null, image.position, null,
                publication.publishedChannels(image),
                members.stream().flatMap(member -> member.photos.stream()
                                .filter(photo -> Objects.equals(photo.familyPhotoId, image.id))
                                .flatMap(photo -> chosenFor(member, members, photo).stream()))
                        .toList());
    }

    /** The colour whose row this is, once per stored choice: website first, then catalogue. */
    private static List<ChosenForDto> chosenFor(ProductEntity owner, List<ProductEntity> members,
                                                ProductPhotoEntity row) {
        List<ChosenForDto> chosen = new ArrayList<>();
        if (leads(row, PhotoRole.WEBSITE)) {
            chosen.add(new ChosenForDto(owner.id, shortLabel(owner, members), Role.MAIN));
        }
        if (leads(row, PhotoRole.CATALOGUE)) {
            chosen.add(new ChosenForDto(owner.id, shortLabel(owner, members), Role.CATALOGUE_VARIANT));
        }
        return List.copyOf(chosen);
    }

    /** Another colour's own photo that a place names; this product's list never holds it. */
    private static List<OtherPhotoDto> otherPhotos(PlacesDto places, List<PhotoDto> photos,
                                                   List<ProductEntity> members) {
        Set<String> listed = photos.stream().map(PhotoDto::key).collect(Collectors.toSet());
        Set<String> named = new LinkedHashSet<>();
        for (PlaceDto place : List.of(places.websiteColour(), places.websiteOpening(), places.documents(),
                places.websiteQuote(), places.catalogueColour(), places.catalogueDetail(),
                places.catalogueOverview(), places.catalogueCompactRow())) {
            Stream.of(place.shownKey(), place.chosenKey())
                    .filter(key -> key != null && !listed.contains(key)).forEach(named::add);
        }
        List<OtherPhotoDto> other = new ArrayList<>();
        for (String key : named) {
            long id = PhotoKey.parse(key).id();
            for (ProductEntity member : members) {
                member.photos.stream()
                        .filter(photo -> photo.familyPhotoId == null && Objects.equals(photo.id, id))
                        .findFirst().ifPresent(photo -> {
                            String base = "/api/products/" + member.id + "/photos/" + photo.id;
                            other.add(new OtherPhotoDto(key, member.id, shortLabel(member, members),
                                    photo.originalFilename, photo.widthPx, photo.heightPx,
                                    base + "/renditions/small", base + "/renditions/medium", base));
                        });
            }
        }
        return List.copyOf(other);
    }

    /**
     * What every place really shows, from the rows already loaded: no query and no blob read.
     * The website places follow the public projection, the catalogue places the plan the PDF
     * renderer prints from, for one assumed export: the brochure with one photo per series and
     * every active colour in variant order.
     */
    private final class Places {
        private final ProductEntity product;
        private final ProductFamilyEntity family;
        private final List<ProductEntity> members;
        private final List<ProductFamilyPhotoEntity> websiteImages;
        private final Long quote;
        /** The colours of the assumed catalogue. */
        private final List<ProductEntity> selection;
        private final List<CataloguePhotoPlan.GalleryPhoto> gallery;
        private final Map<String, Long> sizes = new HashMap<>();
        private final CataloguePhotoPlan.FamilySelection brochure;

        Places(ProductEntity product, ProductFamilyEntity family, List<ProductEntity> members,
               List<ProductFamilyPhotoEntity> websiteImages, List<ProductFamilyPhotoEntity> catalogueImages,
               List<CataloguePhotoChoices.Choice> choices, Long quote) {
            this.product = product;
            this.family = family;
            this.members = members;
            this.websiteImages = websiteImages;
            this.quote = quote;
            this.selection = members.stream().filter(member -> member.active && !member.demo)
                    .sorted(Comparator.comparingInt((ProductEntity member) -> member.variantPosition)
                            .thenComparing(member -> member.id, Comparator.nullsLast(Long::compareTo)))
                    .toList();
            /* Same shapes as the catalogue export reads (PanacheCatalogFamilyReader). */
            this.gallery = catalogueImages.stream().map(image -> new CataloguePhotoPlan.GalleryPhoto(
                    image.id, image.largeStorageKey, image.position,
                    image.variantProduct == null ? null : image.variantProduct.id)).toList();
            if (family != null) {
                family.photos.forEach(image -> size(image.largeStorageKey, image.largeSizeBytes));
            }
            catalogueImages.forEach(image -> size(image.largeStorageKey, image.largeSizeBytes));
            members.forEach(member -> member.photos.forEach(
                    photo -> size(photo.storageKey, photo.sizeBytes)));
            this.brochure = CataloguePhotoPlan.family(family != null, gallery,
                    family == null ? null : manual(choices, family.catalogueDetailPhotoId),
                    family == null ? null : manual(choices, family.catalogueOverviewPhotoId),
                    selection.stream().map(Places::variant).toList(), 1, this::usable);
        }

        PlacesDto all() {
            return new PlacesDto(websiteColour(), websiteOpening(), documents(), websiteQuote(),
                    catalogueColour(), catalogueDetail(), catalogueOverview(), catalogueCompactRow());
        }

        boolean inSelection() {
            return selection.stream().anyMatch(member -> Objects.equals(member.id, product.id));
        }

        private PlaceDto websiteColour() {
            ProductPhotoEntity lead = leadRow(product, PhotoRole.WEBSITE);
            Long chosen = signed(lead);
            if (family == null) {
                return place(null, chosen, PlaceReason.NO_SERIES, Role.MAIN, PlaceScope.COLOUR, null);
            }
            if (!product.active || product.demo) {
                return place(null, chosen, PlaceReason.COLOUR_NOT_ACTIVE, Role.MAIN, PlaceScope.COLOUR, null);
            }
            ProductFamilyPhotoEntity primary = publicPhotos.primary(
                    websiteImages, product, members, CatalogChannel.WEBSITE);
            PlaceReason reason;
            if (chosen != null) {
                ProductFamilyPhotoEntity source = seriesPhoto(lead.familyPhotoId);
                reason = source != null && !publication.isSelectedFor(source, CatalogChannel.WEBSITE)
                        ? PlaceReason.NOT_PUBLISHED_FOR_WEBSITE : PlaceReason.NOT_PUBLISHABLE;
            } else if (primary == null) {
                reason = PlaceReason.NO_PHOTO;
            } else if (primary.id < 0) {
                reason = PlaceReason.OWN_PHOTO_FALLBACK;
            } else {
                reason = variants.rank(primary, product, members) == 0
                        ? PlaceReason.OWN_COLOUR_SERIES_PHOTO : PlaceReason.ALL_COLOURS_SERIES_PHOTO;
            }
            Long shown = primary == null ? null : primary.id;
            return place(shown, chosen, reason, Role.MAIN, PlaceScope.COLOUR, colourOf(shown));
        }

        /** The series order decides this photo; no colour's Hoofdfoto does. */
        private PlaceDto websiteOpening() {
            if (family == null || websiteImages.isEmpty()) {
                return place(null, null, family == null ? PlaceReason.NO_SERIES : PlaceReason.NO_PHOTO,
                        null, PlaceScope.SERIES, null);
            }
            Long shown = websiteImages.getFirst().id;
            return place(shown, null, PlaceReason.FIRST_IN_SERIES_ORDER, null, PlaceScope.SERIES,
                    colourOf(shown));
        }

        /** Same rule as {@link #main}: documents print a chosen photo whether published or not. */
        private PlaceDto documents() {
            Long chosen = signed(leadRow(product, PhotoRole.WEBSITE));
            if (chosen != null) {
                return place(chosen, chosen, null, Role.MAIN, PlaceScope.COLOUR, colourOf(chosen));
            }
            List<ProductPhotoEntity> rows = product.photos.stream().sorted(ROW_ORDER).toList();
            ProductPhotoEntity inherited = rows.stream().filter(photo -> photo.familyPhotoId != null)
                    .findFirst().orElse(null);
            Long shown = signed(inherited != null ? inherited : rows.isEmpty() ? null : rows.getFirst());
            return place(shown, null, shown == null ? PlaceReason.NO_PHOTO
                            : inherited != null ? PlaceReason.FIRST_SERIES_PHOTO : PlaceReason.FIRST_OWN_PHOTO,
                    Role.MAIN, PlaceScope.COLOUR, colourOf(shown));
        }

        private PlaceDto websiteQuote() {
            if (family == null) {
                return place(null, null, PlaceReason.NO_SERIES, Role.QUOTE, PlaceScope.SERIES, null);
            }
            Long chosen = existing(family.websiteQuotePhotoId);
            if (!family.active || websiteImages.isEmpty()) {
                return place(null, chosen, family.active ? PlaceReason.NO_PHOTO : PlaceReason.SERIES_NOT_ACTIVE,
                        Role.QUOTE, PlaceScope.SERIES, null);
            }
            PlaceReason reason = PlaceReason.NOT_PUBLISHED_FOR_WEBSITE;
            if (chosen == null) {
                Long firstColour = selection.stream()
                        .map(member -> publicPhotos.primary(
                                websiteImages, member, members, CatalogChannel.WEBSITE))
                        .filter(Objects::nonNull).map(image -> image.id).findFirst().orElse(null);
                reason = Objects.equals(firstColour, quote)
                        ? PlaceReason.FIRST_COLOUR_WEBSITE_PHOTO : PlaceReason.FIRST_IN_SERIES_ORDER;
            }
            return place(quote, chosen, reason, Role.QUOTE, PlaceScope.SERIES, colourOf(quote));
        }

        /** The colour strip is printed for a series with at least two colours in the export. */
        private PlaceDto catalogueColour() {
            ProductPhotoEntity lead = leadRow(product, PhotoRole.CATALOGUE);
            Long chosen = signed(lead);
            if (!inSelection() || family == null || selection.size() < 2) {
                return place(null, chosen, inSelection() ? PlaceReason.SINGLE_COLOUR_SERIES
                                : PlaceReason.COLOUR_NOT_ACTIVE,
                        Role.CATALOGUE_VARIANT, PlaceScope.COLOUR, null);
            }
            CataloguePhotoPlan.Ref shown = CataloguePhotoPlan.variantCandidates(variant(product), true, gallery)
                    .stream().filter(this::usable).findFirst().orElse(null);
            PlaceReason reason;
            if (chosen != null) {
                reason = notInTheStrip(lead);
            } else if (shown == null) {
                reason = PlaceReason.NO_PHOTO;
            } else {
                reason = shown.origin() == CataloguePhotoPlan.Origin.PRODUCT
                        ? PlaceReason.OWN_PHOTO_FIRST : PlaceReason.OWN_COLOUR_SERIES_PHOTO;
            }
            return place(shown == null ? null : shown.signedId(), chosen, reason,
                    Role.CATALOGUE_VARIANT, PlaceScope.COLOUR, shown == null ? null : product);
        }

        private PlaceReason notInTheStrip(ProductPhotoEntity lead) {
            ProductFamilyPhotoEntity source = seriesPhoto(lead.familyPhotoId);
            if (source != null) {
                if (!publication.isSelectedFor(source, CatalogChannel.CATALOGUE)) {
                    return PlaceReason.NOT_PUBLISHED_FOR_CATALOGUE;
                }
                if (!publication.isEligible(source, members)) return PlaceReason.NOT_PUBLISHABLE;
                if (source.variantProduct == null) return PlaceReason.ALL_COLOURS_PHOTO_NOT_USED_PER_COLOUR;
            }
            return PlaceReason.PHOTO_UNREADABLE;
        }

        private PlaceDto catalogueDetail() {
            Long chosen = family == null ? null : existing(family.catalogueDetailPhotoId);
            /* Without a colour the export refuses, so no photo of the series is printed. */
            if (selection.isEmpty()) {
                return place(null, chosen, PlaceReason.COLOUR_NOT_ACTIVE,
                        Role.CATALOGUE_DETAIL, PlaceScope.SERIES, null);
            }
            CataloguePhotoPlan.DetailEntry first = brochure.detail().isEmpty()
                    ? null : brochure.detail().getFirst();
            PlaceReason reason;
            if (chosen != null) {
                reason = PlaceReason.CHOICE_NOT_AVAILABLE;
            } else if (first == null) {
                reason = PlaceReason.NO_PHOTO;
            } else {
                reason = switch (first.basis()) {
                    case VARIANT_LEAD -> Objects.equals(first.productId(), product.id)
                            ? PlaceReason.THIS_COLOUR_LEAD : PlaceReason.OTHER_COLOUR_LEAD_OVERRIDES;
                    case OWN_FILLER -> PlaceReason.OWN_PHOTO_FILLER;
                    case MANUAL_DETAIL, GALLERY -> PlaceReason.FIRST_IN_SERIES_ORDER;
                };
            }
            return place(first == null ? null : first.ref().signedId(), chosen, reason,
                    Role.CATALOGUE_DETAIL, PlaceScope.SERIES, first == null ? null : member(first.productId()));
        }

        private PlaceDto catalogueOverview() {
            Long chosen = family == null ? null : existing(family.catalogueOverviewPhotoId);
            if (selection.isEmpty()) {
                return place(null, chosen, PlaceReason.COLOUR_NOT_ACTIVE,
                        Role.CATALOGUE_OVERVIEW, PlaceScope.SERIES, null);
            }
            CataloguePhotoPlan.DetailEntry first = brochure.detail().isEmpty()
                    ? null : brochure.detail().getFirst();
            CataloguePhotoPlan.Ref manual = brochure.manualOverview();
            Long shown = manual != null ? manual.signedId() : first == null ? null : first.ref().signedId();
            return place(shown, chosen, chosen != null ? PlaceReason.CHOICE_NOT_AVAILABLE
                            : shown == null ? PlaceReason.NO_PHOTO : PlaceReason.SAME_AS_LARGE_PHOTO,
                    Role.CATALOGUE_OVERVIEW, PlaceScope.SERIES,
                    manual != null ? colourOf(shown) : first == null ? null : member(first.productId()));
        }

        /** The compact layout prints an inactive colour too when it is selected; never a demo. */
        private PlaceDto catalogueCompactRow() {
            ProductPhotoEntity lead = leadRow(product, PhotoRole.CATALOGUE);
            Long chosen = signed(lead);
            if (product.demo) {
                return place(null, chosen, PlaceReason.COLOUR_NOT_ACTIVE,
                        Role.CATALOGUE_VARIANT, PlaceScope.COLOUR, null);
            }
            Set<Long> galleryIds = gallery.stream().map(CataloguePhotoPlan.GalleryPhoto::signedId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Long shown = CataloguePhotoPlan.compactRow(variant(product), galleryIds, 1, this::usable)
                    .stream().map(CataloguePhotoPlan.Ref::signedId).findFirst().orElse(null);
            PlaceReason reason;
            if (chosen != null) {
                reason = lead.familyPhotoId != null && !galleryIds.contains(lead.familyPhotoId)
                        ? PlaceReason.NOT_PUBLISHED_FOR_CATALOGUE : PlaceReason.PHOTO_UNREADABLE;
            } else if (shown == null) {
                reason = PlaceReason.NO_PHOTO;
            } else {
                reason = shown < 0 ? PlaceReason.OWN_PHOTO_FIRST : PlaceReason.FIRST_SERIES_PHOTO;
            }
            return place(shown, chosen, reason, Role.CATALOGUE_VARIANT, PlaceScope.COLOUR, colourOf(shown));
        }

        /** The state follows from the two photos; a reason is kept for everything but CHOSEN. */
        private PlaceDto place(Long shownId, Long chosenId, PlaceReason reason, Role role,
                               PlaceScope scope, ProductEntity related) {
            Long shown = existing(shownId);
            Long chosen = existing(chosenId);
            PlaceState state = chosen != null
                    ? chosen.equals(shown) ? PlaceState.CHOSEN : PlaceState.CHOSEN_NOT_SHOWN
                    : shown != null ? PlaceState.AUTOMATIC : PlaceState.NONE;
            return new PlaceDto(
                    shown == null ? null : PhotoKey.ofSigned(shown).value(),
                    chosen == null ? null : PhotoKey.ofSigned(chosen).value(),
                    state, state == PlaceState.CHOSEN ? null : reason, role, scope,
                    related == null ? null : related.id,
                    related == null ? null : shortLabel(related, members),
                    related != null && Objects.equals(related.id, product.id));
        }

        /** Null for a stored id whose photo is gone, so no place ever names a missing photo. */
        private Long existing(Long signedId) {
            if (signedId == null || signedId == 0) return null;
            return (signedId > 0 ? seriesPhoto(signedId) != null : owner(signedId) != null) ? signedId : null;
        }

        private Long signed(ProductPhotoEntity row) {
            return row == null || row.id == null ? null : existing(PhotoKey.ofRow(row).signedId());
        }

        private ProductFamilyPhotoEntity seriesPhoto(Long id) {
            return family == null || id == null ? null : family.photos.stream()
                    .filter(image -> id.equals(image.id)).findFirst().orElse(null);
        }

        /** The colour that owns an own photo, addressed by its negative id. */
        private ProductEntity owner(Long signedId) {
            return members.stream().filter(member -> member.photos.stream().anyMatch(
                            photo -> photo.familyPhotoId == null && photo.id != null && photo.id == -signedId))
                    .findFirst().orElse(null);
        }

        /** The colour a photo belongs to; null for a series photo of all colours. */
        private ProductEntity colourOf(Long signedId) {
            if (signedId == null) return null;
            if (signedId < 0) return owner(signedId);
            ProductFamilyPhotoEntity image = seriesPhoto(signedId);
            return image == null ? null : variants.resolve(image, members);
        }

        private ProductEntity member(Long productId) {
            return productId == null ? null : members.stream()
                    .filter(member -> productId.equals(member.id)).findFirst().orElse(null);
        }

        private void size(String storageKey, long sizeBytes) {
            if (storageKey != null) sizes.merge(storageKey, sizeBytes, Math::max);
        }

        /** Stands in for the renderer's decode: a stored key with bytes. No blob is read here. */
        private boolean usable(CataloguePhotoPlan.Ref ref) {
            return ref != null && ref.storageKey() != null && !ref.storageKey().isBlank()
                    && sizes.getOrDefault(ref.storageKey(), 0L) > 0;
        }

        private static CataloguePhotoPlan.GalleryPhoto manual(
                List<CataloguePhotoChoices.Choice> choices, Long id) {
            return id == null ? null : choices.stream().filter(choice -> id.equals(choice.id()))
                    .map(choice -> new CataloguePhotoPlan.GalleryPhoto(
                            choice.id(), choice.storageKey(), -1, choice.productId()))
                    .findFirst().orElse(null);
        }

        /** The photos in the order the catalogue export reads them: by position, ties as stored. */
        private static CataloguePhotoPlan.Variant variant(ProductEntity member) {
            return new CataloguePhotoPlan.Variant(member.id, member.photos.stream()
                    .sorted(Comparator.comparingInt((ProductPhotoEntity photo) -> photo.position))
                    .map(photo -> new CataloguePhotoPlan.VariantPhoto(
                            photo.id, photo.storageKey, photo.position, photo.familyPhotoId,
                            leads(photo, PhotoRole.CATALOGUE))).toList());
        }
    }

    /** Same original bytes are cheap to suspect: equal size, dimensions and type. */
    private String duplicateOf(ProductEntity product, ProductFamilyEntity family,
                               List<ProductEntity> members, ProductPhotoEntity photo) {
        if (family == null) return null;
        return family.photos.stream()
                .filter(image -> image.largeSizeBytes == photo.sizeBytes
                        && Objects.equals(image.largeWidthPx, photo.widthPx)
                        && Objects.equals(image.largeHeightPx, photo.heightPx)
                        && Objects.equals(image.largeContentType, photo.contentType))
                .min(Comparator.comparingInt((ProductFamilyPhotoEntity image) ->
                                variants.rank(image, product, members))
                        .thenComparingInt(image -> image.position))
                .map(image -> "F" + image.id).orElse(null);
    }

    private static VisibilityDto visibility(Map<CatalogChannel, Set<Long>> publicIds, long signedId) {
        return new VisibilityDto(
                publicIds.get(CatalogChannel.WEBSITE).contains(signedId),
                publicIds.get(CatalogChannel.CATALOGUE).contains(signedId),
                publicIds.get(CatalogChannel.ORDER_APP).contains(signedId));
    }

    private static List<Role> rolesOf(PhotoKey key, Map<Role, RoleChoiceDto> roles) {
        return roles.entrySet().stream()
                .filter(entry -> key.value().equals(entry.getValue().key()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static RoleChoiceDto storedChoice(Long stored, Set<Long> available) {
        return stored != null && available.contains(stored) ? choice(stored, true) : null;
    }

    private static RoleChoiceDto choice(Long signedId, boolean explicit) {
        PhotoKey key = PhotoKey.ofSigned(signedId);
        return key == null ? null : new RoleChoiceDto(key.value(), explicit);
    }

    private static void put(Map<Role, RoleChoiceDto> roles, Role role, RoleChoiceDto choice) {
        if (choice != null) roles.put(role, choice);
    }

    static ProductPhotoEntity leadRow(ProductEntity product, PhotoRole role) {
        return product.photos.stream().filter(photo -> leads(photo, role))
                .sorted(ROW_ORDER).findFirst().orElse(null);
    }

    static boolean leads(ProductPhotoEntity photo, PhotoRole role) {
        return photo.leadRoles != null && Arrays.stream(photo.leadRoles.split(","))
                .anyMatch(value -> role.name().equals(value.strip()));
    }

    /** "Rood · 5.5*6cm": the Dutch colour and size, else the SKU. */
    static String fullLabel(ProductEntity product) {
        String label = Stream.of(product.colour, product.variantSize)
                .filter(value -> value != null && !value.isBlank()).map(String::strip)
                .collect(Collectors.joining(" · "));
        if (!label.isEmpty()) return label;
        return product.sku == null || product.sku.isBlank() ? product.name : product.sku;
    }

    /** The colour alone while no other family member shares it ("Alleen Rood"). */
    static String shortLabel(ProductEntity product, List<ProductEntity> members) {
        String colour = product.colour == null ? null : product.colour.strip();
        if (colour == null || colour.isEmpty()) return fullLabel(product);
        Map<String, Long> colours = members.stream().filter(member -> member.colour != null)
                .collect(Collectors.groupingBy(member -> member.colour.strip().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new, Collectors.counting()));
        return colours.getOrDefault(colour.toLowerCase(Locale.ROOT), 0L) > 1 ? fullLabel(product) : colour;
    }
}
