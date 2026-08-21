package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Comparator;
import java.util.List;

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
            inherited.forEach(photo -> {
                product.photos.remove(photo);
                entityManager.remove(photo);
            });

            int nextPosition = product.photos.stream().mapToInt(photo -> photo.position).max().orElse(-1) + 1;
            List<ProductFamilyPhotoEntity> ordered = family.photos.stream()
                    .filter(image -> variantResolver.rank(image, product, members) < 2)
                    .sorted(Comparator
                            .comparingInt((ProductFamilyPhotoEntity image) ->
                                    variantResolver.rank(image, product, members))
                            .thenComparingInt(image -> image.position))
                    .toList();
            for (ProductFamilyPhotoEntity source : ordered) {
                ProductPhotoEntity photo = new ProductPhotoEntity();
                photo.product = product;
                photo.familyPhotoId = source.id;
                photo.storageKey = source.largeStorageKey;
                photo.originalFilename = source.originalFilename;
                photo.contentType = source.largeContentType;
                photo.sizeBytes = source.largeSizeBytes;
                photo.widthPx = source.largeWidthPx;
                photo.heightPx = source.largeHeightPx;
                photo.position = nextPosition++;
                entityManager.persist(photo);
                product.photos.add(photo);
            }
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
