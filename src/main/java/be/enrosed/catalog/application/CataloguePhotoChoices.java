package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Administrator choices use the same signed photo identities as the public ERP projection. */
public final class CataloguePhotoChoices {
    private CataloguePhotoChoices() {}

    public record Choice(Long id, Long productId, String originalFilename, String source,
                         String storageKey, String contentType, String smallUrl, String largeUrl) {}

    public static List<Choice> available(ProductFamilyEntity family, List<ProductEntity> members,
                                         ObjectMapper json) {
        var policy = new FamilyPhotoPublicationPolicy(new FamilyPhotoVariantResolver(), json);
        List<ProductEntity> active = members == null ? List.of() : members.stream()
                .filter(p -> p.active && !p.demo && Objects.equals(p.familyId, family.id)).toList();
        List<Choice> result = new ArrayList<>();
        family.photos.stream().filter(p -> policy.isPublic(p, active, CatalogChannel.CATALOGUE))
                .sorted(Comparator.comparingInt(p -> p.position)).forEach(p -> {
                    String base = "/api/product-families/" + family.id + "/images/" + p.id;
                    result.add(new Choice(p.id, p.variantProduct == null ? null : p.variantProduct.id,
                            p.originalFilename, "FAMILY", p.largeStorageKey, p.largeContentType,
                            base + "/small", base + "/large"));
                });
        active.stream().sorted(Comparator.comparingInt((ProductEntity p) -> p.variantPosition)
                        .thenComparing(p -> p.id)).forEach(p -> p.photos.stream()
                .filter(photo -> photo.familyPhotoId == null && photo.id != null
                        && photo.id > 0 && photo.storageKey != null && photo.sizeBytes > 0
                        && photo.contentType != null && photo.contentType.startsWith("image/"))
                .sorted(Comparator.comparingInt(photo -> photo.position)).forEach(photo -> {
                    String base = "/api/products/" + p.id + "/photos/" + photo.id;
                    result.add(new Choice(-photo.id, p.id, photo.originalFilename, "PRODUCT",
                            photo.storageKey, photo.contentType, base + "/renditions/small", base));
                }));
        return List.copyOf(result);
    }
}
