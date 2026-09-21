package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only public gallery: current ERP product photos plus published ERP family images. */
@ApplicationScoped
public class PublicFamilyPhotoProjection {
    private final FamilyPhotoPublicationPolicy publication;
    private final FamilyPhotoVariantResolver variants;
    private final PublicProductNameResolver names;
    private final ObjectMapper json;

    public PublicFamilyPhotoProjection(FamilyPhotoPublicationPolicy publication,
                                      FamilyPhotoVariantResolver variants,
                                      PublicProductNameResolver names, ObjectMapper json) {
        this.publication = publication;
        this.variants = variants;
        this.names = names;
        this.json = json;
    }

    /** Includes selected incomplete images so publication preflight can report missing alts. */
    public List<ProductFamilyPhotoEntity> selected(
            ProductFamilyEntity family, List<ProductEntity> members, CatalogChannel channel) {
        List<ProductFamilyPhotoEntity> result = new ArrayList<>();
        members.stream().filter(product -> product.active && !product.demo
                        && Objects.equals(product.familyId, family.id))
                .sorted(Comparator.comparingInt((ProductEntity product) -> product.variantPosition)
                        .thenComparing(product -> product.id, Comparator.nullsLast(Long::compareTo)))
                .forEach(product -> {
                    ProductPhotoEntity lead = channelPhoto(product, channel);
                    if (lead != null) result.add(project(family, product, lead));
                });
        family.photos.stream()
                .filter(image -> publication.isSelectedFor(image, channel))
                // Historical imports remain in ERP; they are no longer public publication inputs.
                .filter(image -> !importedFromShopify(image))
                .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) -> image.position)
                        .thenComparing(image -> image.id, Comparator.nullsLast(Long::compareTo)))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public List<ProductFamilyPhotoEntity> images(
            ProductFamilyEntity family, List<ProductEntity> members, CatalogChannel channel) {
        return selected(family, members, channel).stream()
                .filter(image -> publication.isPublic(image, members, channel))
                .filter(image -> {
                    ProductEntity product = variants.resolve(image, members);
                    return product == null || !product.demo;
                }).toList();
    }

    public ProductFamilyPhotoEntity primary(ProductFamilyEntity family, ProductEntity product,
                                            List<ProductEntity> members, CatalogChannel channel) {
        return images(family, members, channel).stream()
                .filter(image -> publication.isUsableBy(image, product, members, channel))
                .min(Comparator.comparingInt((ProductFamilyPhotoEntity image) ->
                                explicitlyLeads(image, product, channel) ? 0 : 1)
                        .thenComparingInt(image -> isProductProjection(image) ? 0 : 1)
                        .thenComparingInt(image -> variants.rank(image, product, members))
                        .thenComparingInt(image -> image.position))
                .orElse(null);
    }

    public static boolean isProductProjection(ProductFamilyPhotoEntity image) {
        return image != null && image.id != null && image.id < 0
                && image.sourceKey != null && image.sourceKey.startsWith("erp-photo-");
    }

    static boolean importedFromShopify(ProductFamilyPhotoEntity image) {
        return containsShopify(image.sourceUrl) || containsShopify(image.sourceKey)
                || containsShopify(image.sourceAssetId) || containsShopify(image.altTextSource);
    }

    private static boolean containsShopify(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("shopify");
    }

    private static ProductPhotoEntity channelPhoto(ProductEntity product, CatalogChannel channel) {
        // leadRoles selects an opening photo, not visibility. The existing ERP contract falls
        // back to the first own photo; family publication is the public access boundary.
        return product.photos.stream().filter(photo -> photo.familyPhotoId == null)
                .filter(photo -> photo.id != null && photo.id > 0 && !blank(photo.storageKey)
                        && photo.sizeBytes > 0 && positive(photo.widthPx) && positive(photo.heightPx)
                        && photo.contentType != null && photo.contentType.startsWith("image/"))
                .min(Comparator.comparingInt((ProductPhotoEntity photo) -> channelLead(photo, channel) ? 0 : 1)
                        .thenComparingInt(photo -> photo.position)
                        .thenComparing(photo -> photo.id)).orElse(null);
    }

    private static boolean channelLead(ProductPhotoEntity photo, CatalogChannel channel) {
        if (channel == CatalogChannel.ORDER_APP) return false;
        String selectedRole = channel == CatalogChannel.CATALOGUE ? "CATALOGUE" : "WEBSITE";
        return photo.leadRoles != null && Arrays.stream(photo.leadRoles.split(","))
                .anyMatch(role -> selectedRole.equals(role.strip()));
    }

    private static boolean explicitlyLeads(ProductFamilyPhotoEntity image, ProductEntity product,
                                           CatalogChannel channel) {
        return product.photos.stream().filter(photo -> channelLead(photo, channel))
                .anyMatch(photo -> photo.familyPhotoId == null
                        ? photo.id != null && Objects.equals(image.id, -photo.id)
                        : Objects.equals(image.id, photo.familyPhotoId));
    }

    private ProductFamilyPhotoEntity project(
            ProductFamilyEntity family, ProductEntity product, ProductPhotoEntity photo) {
        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        // Negative IDs separate transient ERP product photos from persisted family-photo IDs.
        image.id = -photo.id;
        image.family = family;
        image.sourceKey = "erp-photo-" + photo.id + "-" + storageVersion(photo);
        image.originalFilename = photo.originalFilename;
        image.originalWidthPx = photo.widthPx;
        image.originalHeightPx = photo.heightPx;
        // Deliver the authoritative upload unchanged. Both URLs report its real dimensions.
        image.smallStorageKey = photo.storageKey;
        image.largeStorageKey = photo.storageKey;
        image.smallContentType = photo.contentType;
        image.largeContentType = photo.contentType;
        image.smallSizeBytes = photo.sizeBytes;
        image.largeSizeBytes = photo.sizeBytes;
        image.smallWidthPx = photo.widthPx;
        image.largeWidthPx = photo.widthPx;
        image.smallHeightPx = photo.heightPx;
        image.largeHeightPx = photo.heightPx;
        image.position = product.variantPosition;
        image.variantProduct = product;
        image.publishedChannelsJson = "[\"WEBSITE\",\"ORDER_APP\",\"CATALOGUE\"]";
        List<ProductFamilyDto.AltTextDto> alts = new ArrayList<>();
        for (Language language : Language.values()) {
            var name = names.resolve(product, language);
            // Never label a fallback as a translation; strict catalogue validation remains honest.
            if (name.sourceLanguage() == language && !blank(name.value())) {
                alts.add(new ProductFamilyDto.AltTextDto(language, name.value()));
            }
        }
        try {
            image.altTextsJson = json.writeValueAsString(alts);
        } catch (Exception exception) {
            throw new IllegalStateException("ERP-foto alt-teksten konden niet worden samengesteld", exception);
        }
        return image;
    }

    private static String storageVersion(ProductPhotoEntity photo) {
        try {
            // Upload storage keys are immutable; this opaque version does not expose the private key.
            String identity = photo.storageKey + "|" + photo.sizeBytes + "|" + photo.widthPx
                    + "|" + photo.heightPx + "|" + photo.contentType;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("ERP-fotoversie kon niet worden samengesteld", exception);
        }
    }

    private static boolean positive(Integer value) { return value != null && value > 0; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
