package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves old text-linked gallery rows to their canonical product variant.
 *
 * Product ids are authoritative. The external key and colour fallbacks only
 * exist so rows created before {@code variant_product_id} keep working. A
 * colour is never used when more than one family member has that colour,
 * because colour ceases to identify a SKU as soon as size becomes an option.
 */
@ApplicationScoped
public class FamilyPhotoVariantResolver {

    public ProductEntity resolve(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        return resolvePhoto(image, familyMembers);
    }

    public static ProductEntity resolvePhoto(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        if (image == null) return null;
        List<ProductEntity> members = familyMembers == null ? List.of() : familyMembers;

        if (image.variantProduct != null && image.variantProduct.id != null) {
            ProductEntity byId = members.stream()
                    .filter(item -> Objects.equals(item.id, image.variantProduct.id))
                    .findFirst().orElse(null);
            if (byId != null) return byId;
        }

        String externalId = normalized(image.variantExternalId);
        if (externalId != null) {
            ProductEntity byCanonicalKey = members.stream()
                    .filter(item -> Objects.equals(externalId, normalized(item.canonicalVariantKey)))
                    .findFirst().orElse(null);
            if (byCanonicalKey != null) return byCanonicalKey;
            /* A stale explicit identity must not silently retarget by colour. */
            return null;
        }

        String colour = normalized(image.variantColor);
        if (colour == null) return null;
        List<ProductEntity> byColour = members.stream()
                .filter(item -> Objects.equals(colour, normalized(item.colour)))
                .toList();
        return byColour.size() == 1 ? byColour.get(0) : null;
    }

    /** Backfills only a missing canonical link; legacy evidence is preserved. */
    public boolean backfill(ProductFamilyEntity family, List<ProductEntity> familyMembers) {
        boolean changed = false;
        for (ProductFamilyPhotoEntity image : family.photos) {
            if (image.variantProduct != null) continue;
            ProductEntity resolved = resolve(image, familyMembers);
            if (resolved != null) {
                image.variantProduct = resolved;
                changed = true;
            }
        }
        return changed;
    }

    public boolean matches(ProductFamilyPhotoEntity image, ProductEntity product,
                           List<ProductEntity> familyMembers) {
        ProductEntity resolved = resolve(image, familyMembers);
        return resolved != null && Objects.equals(resolved.id, product.id);
    }

    public int rank(ProductFamilyPhotoEntity image, ProductEntity product,
                    List<ProductEntity> familyMembers) {
        if (matches(image, product, familyMembers)) return 0;
        if (isFamilyWide(image)) return 1;
        return 2;
    }

    public boolean isFamilyWide(ProductFamilyPhotoEntity image) {
        return familyWide(image);
    }

    public static boolean familyWide(ProductFamilyPhotoEntity image) {
        return image != null && image.variantProduct == null
                && normalized(image.variantExternalId) == null
                && normalized(image.variantColor) == null;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
