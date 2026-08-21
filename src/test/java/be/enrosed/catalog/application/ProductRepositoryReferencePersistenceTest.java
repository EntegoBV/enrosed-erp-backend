package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogImportConflictEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductDimensionObservationEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductExternalIdentifierEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPackageEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPriceObservationEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductProvenanceEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProductRepositoryReferencePersistenceTest {

    @Inject ProductRepository products;
    @Inject ProductService productService;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void countsEveryBusinessGraphThatBlocksProductDeletion() {
        long productId = 987_654L;
        persistPurchaseLines(productId, 1);
        persistSalesLines(productId, 2);
        persistPalletItems(productId, 3);
        persistRevisionLines(productId, 4);
        entityManager.flush();

        assertEquals(new ProductRepository.ReferenceCounts(1, 2, 3, 4),
                products.referenceCounts(productId));
        assertEquals(ProductRepository.ReferenceCounts.none(),
                products.referenceCounts(productId + 1));
    }

    @Test
    @TestTransaction
    void deletingLastVariantDraftsFamilyAndDeletesOnlyProductOwnedMetadata() {
        ProductFamilyEntity family = publishedFamily();
        entityManager.persist(family);
        entityManager.flush();

        ProductEntity product = product(family, "DELETE-ME");
        entityManager.persist(product);
        entityManager.flush();

        family.cardFeaturedProductId = product.id;
        ProductCollectionEntity collection = new ProductCollectionEntity();
        collection.collectionKey = "delete-collection";
        collection.name = "Delete collection";
        collection.featuredProductId = product.id;
        entityManager.persist(collection);
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.family = family;
        membership.collection = collection;
        membership.primaryCollection = true;
        family.collections.add(membership);
        entityManager.persist(membership);
        CategoryEntity category = new CategoryEntity();
        category.code = collection.collectionKey;
        category.name = collection.name;
        category.featuredProductId = product.id;
        entityManager.persist(category);

        persistCanonicalMetadata(family, product.id);
        ProductFamilyPhotoEntity retainedPhoto = familyPhoto(family, product);
        entityManager.persist(retainedPhoto);
        entityManager.flush();

        productService.delete(product.id);
        entityManager.flush();
        entityManager.clear();

        assertNull(entityManager.find(ProductEntity.class, product.id));
        ProductFamilyEntity retained = entityManager.find(ProductFamilyEntity.class, family.id);
        assertNotNull(retained);
        assertTrue(retained.active);
        assertEquals("Family content must stay", retained.description);
        assertEquals(PublicationState.DRAFT, retained.websiteStatus);
        assertEquals(PublicationState.DRAFT, retained.orderAppStatus);
        assertEquals(PublicationState.DRAFT, retained.catalogueStatus);
        assertNotNull(retained.updatedAt);
        assertNull(retained.cardFeaturedProductId);
        assertNull(entityManager.find(ProductCollectionEntity.class, collection.id).featuredProductId);
        assertNull(entityManager.find(CategoryEntity.class, category.id).featuredProductId);
        ProductFamilyPhotoEntity reloadedPhoto = entityManager.find(
                ProductFamilyPhotoEntity.class, retainedPhoto.id);
        assertNotNull(reloadedPhoto, "family image must outlive its deleted SKU");
        assertNull(reloadedPhoto.variantProduct, "delete must release the nullable image FK");
        assertEquals(product.canonicalVariantKey, reloadedPhoto.variantExternalId,
                "legacy import evidence remains available");

        assertOnlyFamilyOwnedMetadataRemains("ProductExternalIdentifierEntity", product.id);
        assertOnlyFamilyOwnedMetadataRemains("ProductPriceObservationEntity", product.id);
        assertOnlyFamilyOwnedMetadataRemains("ProductProvenanceEntity", product.id);
        assertOnlyFamilyOwnedMetadataRemains("ProductDimensionObservationEntity", product.id);
        assertOnlyFamilyOwnedMetadataRemains("ProductPackageEntity", product.id);
        assertEquals(1L, count("select count(item) from CatalogImportConflictEntity item "
                + "where item.familyKey = :familyKey", "familyKey", family.familyKey));
    }

    @Test
    @TestTransaction
    void deletingOneVariantKeepsFamilyPublishedWhileAnotherActiveVariantRemains() {
        ProductFamilyEntity family = publishedFamily();
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity deleted = product(family, "DELETE-ONE");
        ProductEntity retained = product(family, "KEEP-ONE");
        deleted.colour = "Red";
        deleted.colourHex = "#A91F32";
        retained.colour = "Blue";
        retained.colourHex = "#6C8FC4";
        retained.variantPosition = 1;
        entityManager.persist(deleted);
        entityManager.persist(retained);
        completePublishedFamily(family);
        entityManager.flush();

        productService.delete(deleted.id);
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity reloaded = entityManager.find(ProductFamilyEntity.class, family.id);
        assertEquals(PublicationState.PUBLISHED, reloaded.websiteStatus);
        assertEquals(PublicationState.PUBLISHED, reloaded.orderAppStatus);
        assertEquals(PublicationState.PUBLISHED, reloaded.catalogueStatus);
        assertEquals(1L, products.countActiveByFamily(family.id));
    }

    @Test
    @TestTransaction
    void movingVariantDetachesStablePhotoFkButKeepsLegacyEvidence() {
        ProductFamilyEntity original = publishedFamily();
        original.familyKey = "original-family";
        original.publicHandle = "original-family";
        ProductFamilyEntity target = publishedFamily();
        target.familyKey = "target-family";
        target.publicHandle = "target-family";
        entityManager.persist(original);
        entityManager.persist(target);
        entityManager.flush();
        ProductEntity product = product(original, "MOVE-ME");
        entityManager.persist(product);
        ProductFamilyPhotoEntity photo = familyPhoto(original, product);
        entityManager.persist(photo);
        entityManager.flush();

        products.save(products.findById(product.id).orElseThrow().withCanonicalIdentity(
                target.id, product.canonicalVariantKey, null, 0, true));
        entityManager.flush();
        entityManager.clear();

        ProductFamilyPhotoEntity retained = entityManager.find(
                ProductFamilyPhotoEntity.class, photo.id);
        assertNull(retained.variantProduct);
        assertEquals(product.canonicalVariantKey, retained.variantExternalId);
    }

    private ProductFamilyEntity publishedFamily() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "delete-family";
        family.publicHandle = "delete-family";
        family.active = true;
        family.name = "Family name";
        family.description = "Family content must stay";
        family.websiteStatus = PublicationState.PUBLISHED;
        family.orderAppStatus = PublicationState.PUBLISHED;
        family.catalogueStatus = PublicationState.PUBLISHED;
        return family;
    }

    private ProductEntity product(ProductFamilyEntity family, String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Variant to delete";
        product.active = true;
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.canonicalVariantKey = sku;
        product.piecesPerCarton = 1;
        for (Language language : Language.values()) {
            ProductTextEntity text = new ProductTextEntity();
            text.product = product;
            text.language = language;
            text.name = "Variant " + language.code();
            text.colour = "Colour " + language.code();
            product.texts.add(text);
        }
        return product;
    }

    private void completePublishedFamily(ProductFamilyEntity family) {
        family.summary = "Published family summary";
        family.seoTitle = "Published family title";
        family.seoDescription = "Published family SEO description";
        for (Language language : Language.values()) {
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = language;
            text.name = "Family " + language.code();
            text.summary = "Summary " + language.code();
            text.description = "Description " + language.code();
            text.seoTitle = "SEO title " + language.code();
            text.seoDescription = "SEO description " + language.code();
            text.highlightsJson = "[]";
            family.texts.add(text);
        }
        CategoryEntity category = new CategoryEntity();
        category.code = family.familyKey;
        category.name = "Published category";
        category.eyebrow = "Published";
        category.description = "Published category description";
        for (Language language : Language.values()) {
            CategoryTextEntity text = new CategoryTextEntity();
            text.category = category;
            text.language = language;
            text.name = "Published category " + language.code();
            text.eyebrow = "Published " + language.code();
            text.description = "Published category description " + language.code();
            category.texts.add(text);
        }
        entityManager.persist(category);
        ProductCollectionEntity collection = new ProductCollectionEntity();
        collection.collectionKey = family.familyKey;
        collection.name = category.name;
        collection.eyebrow = category.eyebrow;
        collection.description = category.description;
        entityManager.persist(collection);
        entityManager.flush();
        family.categoryId = category.id;
        family.categoryKey = category.code;
        family.categoryName = category.name;
        family.collectionKey = collection.collectionKey;
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.family = family;
        membership.collection = collection;
        membership.primaryCollection = true;
        family.collections.add(membership);
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = "global-delete-family-photo";
        photo.smallStorageKey = "global-delete-family-small";
        photo.largeStorageKey = "global-delete-family-large";
        photo.smallWidthPx = 320;
        photo.smallHeightPx = 320;
        photo.largeWidthPx = 960;
        photo.largeHeightPx = 960;
        photo.altTextsJson = java.util.Arrays.stream(Language.values())
                .map(language -> "{\"language\":\"" + language.name()
                        + "\",\"alt\":\"Published family " + language.code() + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        family.photos.add(photo);
    }

    private ProductFamilyPhotoEntity familyPhoto(
            ProductFamilyEntity family, ProductEntity variant) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = "photo-" + variant.sku;
        photo.smallStorageKey = "small-" + variant.sku;
        photo.largeStorageKey = "large-" + variant.sku;
        photo.variantProduct = variant;
        photo.variantExternalId = variant.canonicalVariantKey;
        photo.variantColor = variant.colour;
        photo.altTextsJson = "[]";
        family.photos.add(photo);
        return photo;
    }

    private void persistCanonicalMetadata(ProductFamilyEntity family, long productId) {
        ProductExternalIdentifierEntity productIdentifier = identifier(
                family, productId, "VARIANT", "DELETE-VARIANT", "product-value");
        entityManager.persist(productIdentifier);
        entityManager.persist(identifier(family, null, "FAMILY", family.familyKey, "family-value"));

        ProductPriceObservationEntity productPrice = price(
                family, productId, "VARIANT", "DELETE-VARIANT", "product-price");
        entityManager.persist(productPrice);
        entityManager.persist(price(family, null, "FAMILY", family.familyKey, "family-price"));

        ProductProvenanceEntity productProvenance = provenance(
                family, productId, "VARIANT", "DELETE-VARIANT");
        entityManager.persist(productProvenance);
        entityManager.persist(provenance(family, null, "FAMILY", family.familyKey));

        ProductDimensionObservationEntity productDimension = dimension(
                family, productId, "product-dimension");
        entityManager.persist(productDimension);
        entityManager.persist(dimension(family, null, "family-dimension"));

        ProductPackageEntity productPackage = productPackage(family, productId, "product-package");
        entityManager.persist(productPackage);
        entityManager.persist(productPackage(family, null, "family-package"));

        CatalogImportConflictEntity conflict = new CatalogImportConflictEntity();
        conflict.familyKey = family.familyKey;
        conflict.canonicalVariantKey = "DELETE-VARIANT";
        conflict.fieldName = "colour";
        conflict.reason = "Must remain as import evidence";
        entityManager.persist(conflict);
    }

    private ProductExternalIdentifierEntity identifier(
            ProductFamilyEntity family, Long productId, String ownerType,
            String ownerKey, String externalValue) {
        ProductExternalIdentifierEntity item = new ProductExternalIdentifierEntity();
        item.ownerType = ownerType;
        item.ownerKey = ownerKey;
        item.familyId = family.id;
        item.productId = productId;
        item.source = "TEST";
        item.identifierType = "SKU";
        item.externalValue = externalValue;
        return item;
    }

    private ProductPriceObservationEntity price(
            ProductFamilyEntity family, Long productId, String ownerType,
            String ownerKey, String sourceKey) {
        ProductPriceObservationEntity item = new ProductPriceObservationEntity();
        item.familyId = family.id;
        item.productId = productId;
        item.ownerType = ownerType;
        item.ownerKey = ownerKey;
        item.sourceKey = sourceKey;
        return item;
    }

    private ProductProvenanceEntity provenance(
            ProductFamilyEntity family, Long productId, String ownerType, String ownerKey) {
        ProductProvenanceEntity item = new ProductProvenanceEntity();
        item.ownerType = ownerType;
        item.ownerKey = ownerKey;
        item.familyId = family.id;
        item.productId = productId;
        item.fieldName = "name";
        return item;
    }

    private ProductDimensionObservationEntity dimension(
            ProductFamilyEntity family, Long productId, String sourceKey) {
        ProductDimensionObservationEntity item = new ProductDimensionObservationEntity();
        item.familyId = family.id;
        item.productId = productId;
        item.sourceKey = sourceKey;
        return item;
    }

    private ProductPackageEntity productPackage(
            ProductFamilyEntity family, Long productId, String sourceKey) {
        ProductPackageEntity item = new ProductPackageEntity();
        item.family = family;
        item.productId = productId;
        item.sourceKey = sourceKey;
        return item;
    }

    private void assertOnlyFamilyOwnedMetadataRemains(String entityName, long deletedProductId) {
        assertEquals(0L, count("select count(item) from " + entityName
                + " item where item.productId = :productId", "productId", deletedProductId));
        assertEquals(1L, entityManager.createQuery("select count(item) from " + entityName
                + " item where item.productId is null", Long.class).getSingleResult());
    }

    private long count(String query, String parameter, Object value) {
        return entityManager.createQuery(query, Long.class)
                .setParameter(parameter, value)
                .getSingleResult();
    }

    private void persistPurchaseLines(long productId, int count) {
        for (int index = 0; index < count; index++) {
            SourcingEntities.PurchaseOrderLineEntity line =
                    new SourcingEntities.PurchaseOrderLineEntity();
            line.productId = productId;
            entityManager.persist(line);
        }
    }

    private void persistSalesLines(long productId, int count) {
        for (int index = 0; index < count; index++) {
            SalesEntities.SalesOrderLineEntity line = new SalesEntities.SalesOrderLineEntity();
            line.productId = productId;
            entityManager.persist(line);
        }
    }

    private void persistPalletItems(long productId, int count) {
        for (int index = 0; index < count; index++) {
            SalesEntities.SalesPalletItemEntity item = new SalesEntities.SalesPalletItemEntity();
            item.productId = productId;
            entityManager.persist(item);
        }
    }

    private void persistRevisionLines(long productId, int count) {
        for (int index = 0; index < count; index++) {
            SalesEntities.QuoteRevisionLineEntity line = new SalesEntities.QuoteRevisionLineEntity();
            line.productId = productId;
            entityManager.persist(line);
        }
    }
}
