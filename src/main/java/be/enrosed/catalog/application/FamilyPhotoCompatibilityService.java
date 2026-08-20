package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Keeps existing ProductDto/sales/purchasing photo consumers backed by the canonical family gallery. */
@ApplicationScoped
public class FamilyPhotoCompatibilityService {
    private final CatalogDaos.Products products;
    private final EntityManager entityManager;

    public FamilyPhotoCompatibilityService(CatalogDaos.Products products, EntityManager entityManager) {
        this.products = products;
        this.entityManager = entityManager;
    }

    public void sync(ProductFamilyEntity family) {
        if (family == null || family.id == null) return;
        for (ProductEntity product : products.list("familyId", family.id)) {
            List<ProductPhotoEntity> inherited = product.photos.stream()
                    .filter(photo -> photo.familyPhotoId != null).toList();
            inherited.forEach(photo -> {
                product.photos.remove(photo);
                entityManager.remove(photo);
            });

            int nextPosition = product.photos.stream().mapToInt(photo -> photo.position).max().orElse(-1) + 1;
            List<ProductFamilyPhotoEntity> ordered = family.photos.stream()
                    .sorted(Comparator
                            .comparingInt((ProductFamilyPhotoEntity image) -> rank(image, product))
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

    private static int rank(ProductFamilyPhotoEntity image, ProductEntity product) {
        if (Objects.equals(image.variantExternalId, product.canonicalVariantKey)) return 0;
        if (image.variantExternalId == null) return 1;
        return 2;
    }
}
