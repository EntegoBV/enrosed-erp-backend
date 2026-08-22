package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.application.CategoryPublicKey;
import be.enrosed.catalog.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class PanacheProductRepository implements ProductRepository {

    private final CatalogDaos.Products dao;
    private final EntityManager entityManager;

    public PanacheProductRepository(CatalogDaos.Products dao, EntityManager entityManager) {
        this.dao = dao;
        this.entityManager = entityManager;
    }

    @Override
    public List<Product> findAll() {
        return dao.listAll().stream().map(CatalogMapper::toDomain).toList();
    }

    @Override
    public List<Product> findBySupplier(long supplierId) {
        return dao.list("supplierId", supplierId).stream().map(CatalogMapper::toDomain).toList();
    }

    @Override
    public List<Product> findByFamily(long familyId) {
        return dao.list("familyId = ?1 order by variantPosition, id", familyId).stream()
                .map(CatalogMapper::toDomain).toList();
    }

    @Override
    public Optional<Product> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(CatalogMapper::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return dao.find("sku", sku).firstResultOptional().map(CatalogMapper::toDomain);
    }

    @Override
    public Optional<Product> findByPublicHandle(String publicHandle) {
        return dao.find("publicHandle", publicHandle).firstResultOptional().map(CatalogMapper::toDomain);
    }

    @Override
    public Product save(Product product) {
        ProductEntity entity = product.id() == null ? null : dao.findById(product.id());
        if (entity == null) entity = new ProductEntity();

        CatalogMapper.apply(product, entity);
        CatalogMapper.applyPhotos(product, entity);
        CatalogMapper.applyTexts(product, entity);

        if (entity.id == null) dao.persist(entity);
        dao.flush();
        clearInvalidFeaturedReferences(entity);
        entityManager.flush();
        return CatalogMapper.toDomain(entity);
    }

    @Override
    public Optional<Product> setStock(long productId, int quantity) {
        ProductEntity entity = dao.findById(productId);
        if (entity == null) return Optional.empty();
        Product before = CatalogMapper.toDomain(entity);
        dao.update("stockQuantity = ?1, inventoryKnown = true where id = ?2", quantity, productId);
        dao.getEntityManager().refresh(entity);
        return Optional.of(before);
    }

    /** A single SQL update avoids lost stock when separate orders arrive together. */
    @Override
    public boolean adjustStock(long productId, int delta) {
        ProductEntity entity = dao.findById(productId);
        if (entity == null) return false;
        boolean updated = dao.update(
                "stockQuantity = stockQuantity + ?1, inventoryKnown = true where id = ?2",
                delta, productId) == 1;
        if (updated) dao.getEntityManager().refresh(entity);
        return updated;
    }

    @Override
    public void deleteById(long id) {
        clearFeaturedReferences(id);
        /* Family images are merchandising content and outlive an individual SKU. */
        List<ProductFamilyPhotoEntity> linkedImages = entityManager.createQuery(
                        "from ProductFamilyPhotoEntity image where image.variantProduct.id = :productId",
                        ProductFamilyPhotoEntity.class)
                .setParameter("productId", id)
                .getResultList();
        linkedImages.forEach(image -> image.variantProduct = null);
        entityManager.flush();
        deleteProductOwnedMetadata(id);
        dao.deleteById(id);
    }

    private void clearFeaturedReferences(long productId) {
        entityManager.createQuery(
                        "from ProductFamilyEntity item where item.cardFeaturedProductId = :productId",
                        ProductFamilyEntity.class)
                .setParameter("productId", productId).getResultList()
                .forEach(item -> item.cardFeaturedProductId = null);
        entityManager.createQuery(
                        "from ProductCollectionEntity item where item.featuredProductId = :productId",
                        ProductCollectionEntity.class)
                .setParameter("productId", productId).getResultList()
                .forEach(item -> item.featuredProductId = null);
        entityManager.createQuery(
                        "from CategoryEntity item where item.featuredProductId = :productId",
                        CategoryEntity.class)
                .setParameter("productId", productId).getResultList()
                .forEach(item -> item.featuredProductId = null);
    }

    /** Product edits may inactivate or move a selected member; stale cards must not survive. */
    private void clearInvalidFeaturedReferences(ProductEntity product) {
        if (product.id == null) return;
        entityManager.createQuery(
                        "from ProductFamilyPhotoEntity image where image.variantProduct.id = :productId",
                        ProductFamilyPhotoEntity.class)
                .setParameter("productId", product.id).getResultList().stream()
                .filter(image -> image.family == null
                        || !Objects.equals(image.family.id, product.familyId))
                .forEach(image -> image.variantProduct = null);
        entityManager.createQuery(
                        "from ProductFamilyEntity item where item.cardFeaturedProductId = :productId",
                        ProductFamilyEntity.class)
                .setParameter("productId", product.id).getResultList().stream()
                .filter(family -> !product.active || !Objects.equals(family.id, product.familyId))
                .forEach(family -> family.cardFeaturedProductId = null);

        entityManager.createQuery(
                        "from ProductCollectionEntity item where item.featuredProductId = :productId",
                        ProductCollectionEntity.class)
                .setParameter("productId", product.id).getResultList().stream()
                .filter(collection -> !isCollectionMember(product, collection.id))
                .forEach(collection -> collection.featuredProductId = null);

        ProductFamilyEntity family = product.familyId == null
                ? null : entityManager.find(ProductFamilyEntity.class, product.familyId);
        entityManager.createQuery(
                        "from CategoryEntity item where item.featuredProductId = :productId",
                        CategoryEntity.class)
                .setParameter("productId", product.id).getResultList().stream()
                .filter(category -> !isCategoryMember(product, family, category))
                .forEach(category -> category.featuredProductId = null);
    }

    private boolean isCollectionMember(ProductEntity product, Long collectionId) {
        if (!product.active || product.familyId == null || collectionId == null) return false;
        return entityManager.createQuery(
                        "select count(item) from ProductFamilyCollectionEntity item "
                                + "where item.family.id = :familyId and item.collection.id = :collectionId",
                        Long.class)
                .setParameter("familyId", product.familyId)
                .setParameter("collectionId", collectionId)
                .getSingleResult() > 0;
    }

    private static boolean isCategoryMember(
            ProductEntity product, ProductFamilyEntity family, CategoryEntity category) {
        if (!product.active) return false;
        if (family == null) return Objects.equals(product.categoryId, category.id);
        if (!family.active) return false;
        if (Objects.equals(family.categoryId, category.id)) {
            return true;
        }
        return category.code != null && !category.code.isBlank()
                && Objects.equals(family.categoryKey, CategoryPublicKey.from(category.code));
    }

    private void deleteProductOwnedMetadata(long productId) {
        deleteByProductId("ProductExternalIdentifierEntity", productId);
        deleteByProductId("ProductPriceObservationEntity", productId);
        deleteByProductId("ProductProvenanceEntity", productId);
        deleteByProductId("ProductDimensionObservationEntity", productId);
        deleteByProductId("ProductPackageEntity", productId);
    }

    private void deleteByProductId(String entityName, long productId) {
        entityManager.createQuery("delete from " + entityName + " item where item.productId = :productId")
                .setParameter("productId", productId)
                .executeUpdate();
    }

    @Override
    public ReferenceCounts referenceCounts(long productId) {
        return new ReferenceCounts(
                count("select count(l) from PurchaseOrderLineEntity l where l.productId = :productId",
                        productId),
                count("select count(l) from SalesOrderLineEntity l where l.productId = :productId",
                        productId),
                count("select count(i) from SalesPalletItemEntity i where i.productId = :productId",
                        productId),
                count("select count(l) from QuoteRevisionLineEntity l where l.productId = :productId",
                        productId));
    }

    private long count(String query, long productId) {
        return entityManager.createQuery(query, Long.class)
                .setParameter("productId", productId)
                .getSingleResult();
    }

    @Override
    public long countActiveByFamily(long familyId) {
        return dao.count("familyId = ?1 and active = true", familyId);
    }

    @Override
    public long countByCategory(long categoryId) {
        return dao.count("categoryId", categoryId);
    }

    @Override
    public long countByHsCode(String hsCode) {
        return dao.count("hsCode", hsCode);
    }

    @Override
    public long countBySupplier(long supplierId) {
        return dao.count("supplierId", supplierId);
    }
}
