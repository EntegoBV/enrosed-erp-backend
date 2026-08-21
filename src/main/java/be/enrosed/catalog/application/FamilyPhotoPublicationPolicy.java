package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Keeps the public image list, variant primary image and featured choices on one contract. */
@ApplicationScoped
public class FamilyPhotoPublicationPolicy {
    private final FamilyPhotoVariantResolver variants;
    private final ObjectMapper json;

    public FamilyPhotoPublicationPolicy(FamilyPhotoVariantResolver variants, ObjectMapper json) {
        this.variants = variants;
        this.json = json;
    }

    /**
     * An image is public only when both renditions and a usable alt text exist and its
     * optional variant selector resolves to an active family member. Incomplete uploads
     * remain editable in the administrator API without breaking the live catalogue.
     */
    public boolean isPublic(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        if (!hasPublicMetadata(image, json)) return false;
        ProductEntity resolved = variants.resolve(image, familyMembers);
        if (resolved != null) return resolved.active;
        return variants.isFamilyWide(image);
    }

    /** Exact variant images and genuinely family-wide images are valid merchandising choices. */
    public boolean isUsableBy(
            ProductFamilyPhotoEntity image, ProductEntity product,
            List<ProductEntity> familyMembers) {
        return isPublic(image, familyMembers)
                && variants.rank(image, product, familyMembers) < 2;
    }

    public static boolean hasPublicMetadata(
            ProductFamilyPhotoEntity image, ObjectMapper json) {
        if (image == null || blank(image.sourceKey)
                || blank(image.smallStorageKey) || blank(image.largeStorageKey)
                || !positive(image.smallWidthPx) || !positive(image.smallHeightPx)
                || !positive(image.largeWidthPx) || !positive(image.largeHeightPx)
                || blank(image.altTextsJson)) {
            return false;
        }
        try {
            JsonNode values = json.readTree(image.altTextsJson);
            if (values == null || !values.isArray()) return false;
            for (JsonNode value : values) {
                if (!blank(value.path("alt").asText(null))) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
