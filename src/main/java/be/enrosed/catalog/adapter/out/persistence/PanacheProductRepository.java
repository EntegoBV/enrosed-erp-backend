package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheProductRepository implements ProductRepository {

    private final CatalogDaos.Products dao;

    public PanacheProductRepository(CatalogDaos.Products dao) {
        this.dao = dao;
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
        return CatalogMapper.toDomain(entity);
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
        dao.deleteById(id);
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
