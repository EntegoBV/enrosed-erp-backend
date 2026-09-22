package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The one photo the website's quote page shows for a family.
 *
 * Uses the signed identities of the public WEBSITE gallery, like the catalogue choices. A
 * stored choice only counts while the website still receives that image; otherwise the
 * automatic pick applies, so the resolved id is always one of the family's public images.
 */
public final class WebsiteQuotePhotoChoice {
    private WebsiteQuotePhotoChoice() {}

    public static Long resolve(ProductFamilyEntity family, List<ProductEntity> members,
                               PublicFamilyPhotoProjection publicPhotos) {
        return resolve(family, members, publicPhotos,
                publicPhotos.images(family, members, CatalogChannel.WEBSITE));
    }

    /** Callers that already projected the WEBSITE gallery pass it to avoid a second projection. */
    public static Long resolve(ProductFamilyEntity family, List<ProductEntity> members,
                               PublicFamilyPhotoProjection publicPhotos,
                               List<ProductFamilyPhotoEntity> websiteImages) {
        if (family == null || websiteImages == null || websiteImages.isEmpty()) return null;
        Long explicit = family.websiteQuotePhotoId;
        if (explicit != null && isAvailable(websiteImages, explicit)) return explicit;
        return automatic(family, members, publicPhotos, websiteImages);
    }

    /** The first sold colour's website primary, else the first gallery image. */
    public static Long automatic(ProductFamilyEntity family, List<ProductEntity> members,
                                 PublicFamilyPhotoProjection publicPhotos,
                                 List<ProductFamilyPhotoEntity> websiteImages) {
        if (family == null || websiteImages == null || websiteImages.isEmpty()) return null;
        List<ProductEntity> familyMembers = members == null ? List.of() : members;
        return familyMembers.stream()
                .filter(product -> product.active && !product.demo
                        && Objects.equals(product.familyId, family.id))
                .sorted(Comparator.comparingInt((ProductEntity product) -> product.variantPosition)
                        .thenComparing(product -> product.id, Comparator.nullsLast(Long::compareTo)))
                .map(product -> publicPhotos.primary(
                        family, product, familyMembers, CatalogChannel.WEBSITE))
                .filter(Objects::nonNull)
                .map(image -> image.id)
                .findFirst()
                .orElse(websiteImages.getFirst().id);
    }

    public static boolean isAvailable(List<ProductFamilyPhotoEntity> websiteImages, Long id) {
        return id != null && websiteImages != null
                && websiteImages.stream().anyMatch(image -> Objects.equals(image.id, id));
    }
}
