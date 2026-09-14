package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Keeps existing ProductDto/sales/purchasing photo consumers backed by the canonical family gallery. */
@ApplicationScoped
public class FamilyPhotoCompatibilityService {
    private final CatalogDaos.Products products;
    private final EntityManager entityManager;
    private final FamilyPhotoVariantResolver variantResolver;

    public FamilyPhotoCompatibilityService(
            CatalogDaos.Products products,
            EntityManager entityManager,
            FamilyPhotoVariantResolver variantResolver) {
        this.products = products;
        this.entityManager = entityManager;
        this.variantResolver = variantResolver;
    }

    public void sync(ProductFamilyEntity family) {
        if (family == null || family.id == null) return;
        List<ProductEntity> members = products.list("familyId", family.id);
        variantResolver.backfill(family, members);
        for (ProductEntity product : members) {
            List<ProductPhotoEntity> inherited = product.photos.stream()
                    .filter(photo -> photo.familyPhotoId != null).toList();
            int nextPosition = product.photos.stream()
                    .filter(photo -> photo.familyPhotoId == null)
                    .mapToInt(photo -> photo.position).max().orElse(-1) + 1;
            List<ProductFamilyPhotoEntity> ordered = family.photos.stream()
                    .filter(image -> variantResolver.rank(image, product, members) < 2)
                    .sorted(Comparator
                            .comparingInt((ProductFamilyPhotoEntity image) ->
                                    variantResolver.rank(image, product, members))
                            .thenComparingInt(image -> image.position))
                    .toList();
            Set<ProductPhotoEntity> retained = new HashSet<>();
            for (ProductFamilyPhotoEntity source : ordered) {
                /* Keep the projection's identity and channel leads across gallery edits.
                   Recreating every row broke existing photo URLs and silently cleared
                   WEBSITE/CATALOGUE choices when another image was uploaded. */
                ProductPhotoEntity photo = inherited.stream()
                        .filter(existing -> Objects.equals(existing.familyPhotoId, source.id))
                        .findFirst().orElse(null);
                boolean added = photo == null;
                if (added) photo = new ProductPhotoEntity();
                photo.product = product;
                photo.familyPhotoId = source.id;
                photo.storageKey = source.largeStorageKey;
                photo.originalFilename = source.originalFilename;
                photo.contentType = source.largeContentType;
                photo.sizeBytes = source.largeSizeBytes;
                photo.widthPx = source.largeWidthPx;
                photo.heightPx = source.largeHeightPx;
                photo.position = nextPosition++;
                if (added) {
                    entityManager.persist(photo);
                    product.photos.add(photo);
                }
                retained.add(photo);
            }
            /* Removed or reassigned images lose their projection only for affected
               variants. Never transfer a deleted image's leads to a different image. */
            inherited.stream().filter(photo -> !retained.contains(photo)).forEach(photo -> {
                product.photos.remove(photo);
                entityManager.remove(photo);
            });
        }
        entityManager.flush();
    }

    /** Removes inherited family projections when a product is explicitly unlinked from a family. */
    public void clearInherited(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) return;
        List<ProductPhotoEntity> inherited = product.photos.stream()
                .filter(photo -> photo.familyPhotoId != null).toList();
        inherited.forEach(photo -> {
            product.photos.remove(photo);
            entityManager.remove(photo);
        });
        entityManager.flush();
    }
}
