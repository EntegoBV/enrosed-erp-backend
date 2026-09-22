package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Kind;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PhotoDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        List<ProductFamilyPhotoEntity> websiteImages = family == null
                ? List.of() : publicPhotos.images(family, members, CatalogChannel.WEBSITE);
        for (CatalogChannel channel : CatalogChannel.values()) {
            List<ProductFamilyPhotoEntity> images = family == null ? List.of()
                    : channel == CatalogChannel.WEBSITE ? websiteImages
                    : publicPhotos.images(family, members, channel);
            publicIds.put(channel, images.stream().map(image -> image.id).collect(Collectors.toSet()));
        }

        Map<Role, RoleChoiceDto> roles = new EnumMap<>(Role.class);
        put(roles, Role.MAIN, main(product));
        if (family != null) {
            if (family.active) {
                Long quote = WebsiteQuotePhotoChoice.resolve(family, members, publicPhotos, websiteImages);
                put(roles, Role.QUOTE, choice(quote, Objects.equals(family.websiteQuotePhotoId, quote)));
            }
            Set<Long> catalogueChoices = CataloguePhotoChoices.available(family, members, json).stream()
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
                family == null ? null : "LARGE".equals(family.catalogueDetailSize) ? "LARGE" : "STANDARD");
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
                duplicateOf(product, family, members, photo), null, photo.position, null);
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
                publication.publishedChannels(image));
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
        String label = java.util.stream.Stream.of(product.colour, product.variantSize)
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
