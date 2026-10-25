package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** The suppliers of a product in {@code product_supplier}; currency stored as plain text. */
@ApplicationScoped
public class PanacheProductSupplierRepository implements ProductSupplierRepository {

    private final EntityManager entities;

    public PanacheProductSupplierRepository(EntityManager entities) {
        this.entities = entities;
    }

    @Override
    public List<ProductSupplier> findByProduct(long productId) {
        return entities.createQuery("from ProductSupplierEntity s where s.productId = :productId "
                        + "order by s.preferred desc, s.active desc, s.id", ProductSupplierEntity.class)
                .setParameter("productId", productId)
                .getResultList().stream().map(PanacheProductSupplierRepository::toDomain).toList();
    }

    @Override
    public List<ProductSupplier> findBySupplier(long supplierId) {
        return entities.createQuery("from ProductSupplierEntity s where s.supplierId = :supplierId "
                        + "order by s.productId, s.id", ProductSupplierEntity.class)
                .setParameter("supplierId", supplierId)
                .getResultList().stream().map(PanacheProductSupplierRepository::toDomain).toList();
    }

    @Override
    public Optional<ProductSupplier> findById(long id) {
        return Optional.ofNullable(entities.find(ProductSupplierEntity.class, id))
                .map(PanacheProductSupplierRepository::toDomain);
    }

    @Override
    public Optional<ProductSupplier> find(long productId, long supplierId) {
        return entities.createQuery("from ProductSupplierEntity s where s.productId = :productId "
                        + "and s.supplierId = :supplierId", ProductSupplierEntity.class)
                .setParameter("productId", productId)
                .setParameter("supplierId", supplierId)
                .getResultStream().findFirst().map(PanacheProductSupplierRepository::toDomain);
    }

    @Override
    public ProductSupplier save(ProductSupplier row) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ProductSupplierEntity entity;
        if (row.id() == null) {
            entity = new ProductSupplierEntity();
            entity.createdAt = now;
        } else {
            entity = entities.find(ProductSupplierEntity.class, row.id());
            if (entity == null) throw new NotFoundException("Leverancier van product", row.id());
        }
        entity.productId = row.productId();
        entity.supplierId = row.supplierId();
        entity.preferred = row.preferred();
        entity.active = row.active();
        entity.versionLabel = row.versionLabel();
        entity.supplierReference = row.supplierReference();
        entity.exwPrice = row.exwPrice();
        entity.exwCurrency = row.exwCurrency() == null ? null : row.exwCurrency().name();
        entity.extraUnitCost = row.extraUnitCost();
        entity.moq = row.moq();
        entity.leadTimeDays = row.leadTimeDays();
        entity.supplierNote = row.supplierNote();
        entity.updatedAt = now;
        if (entity.id == null) entities.persist(entity);
        return toDomain(entity);
    }

    @Override
    public void clearPreferred(long productId) {
        /* Pending row changes go out first, so the refresh below cannot drop them. */
        entities.flush();
        entities.createQuery("update ProductSupplierEntity set preferred = false "
                        + "where productId = :productId and preferred = true")
                .setParameter("productId", productId)
                .executeUpdate();
        for (ProductSupplierEntity managed : entities.createQuery(
                        "from ProductSupplierEntity s where s.productId = :productId", ProductSupplierEntity.class)
                .setParameter("productId", productId).getResultList()) {
            entities.refresh(managed);
        }
    }

    @Override
    public void flush() {
        entities.flush();
    }

    @Override
    public void delete(long id) {
        ProductSupplierEntity entity = entities.find(ProductSupplierEntity.class, id);
        if (entity != null) {
            entities.remove(entity);
            entities.flush();
        }
    }

    @Override
    public void deleteByProduct(long productId) {
        entities.flush();
        entities.createQuery("delete from ProductSupplierEntity s where s.productId = :productId")
                .setParameter("productId", productId)
                .executeUpdate();
    }

    static ProductSupplier toDomain(ProductSupplierEntity entity) {
        return new ProductSupplier(entity.id, entity.productId, entity.supplierId, entity.preferred, entity.active,
                entity.versionLabel, entity.supplierReference, entity.exwPrice, currency(entity.exwCurrency),
                entity.extraUnitCost, entity.moq, entity.leadTimeDays, entity.supplierNote,
                entity.createdAt, entity.updatedAt);
    }

    private static Currency currency(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Currency.valueOf(value.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
