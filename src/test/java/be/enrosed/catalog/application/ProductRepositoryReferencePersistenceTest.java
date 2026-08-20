package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogImportConflictEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductDimensionObservationEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductExternalIdentifierEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPackageEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPriceObservationEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductProvenanceEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.PublicationState;
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

        persistCanonicalMetadata(family, product.id);
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
        entityManager.persist(deleted);
        entityManager.persist(retained);
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
        return product;
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
