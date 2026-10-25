package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.ProductSupplier;

import java.util.List;
import java.util.Optional;

/** Outbound port to the suppliers of a product (table {@code product_supplier}). */
public interface ProductSupplierRepository {
    /** Preferred first, then active, then id. */
    List<ProductSupplier> findByProduct(long productId);

    List<ProductSupplier> findBySupplier(long supplierId);

    Optional<ProductSupplier> findById(long id);

    Optional<ProductSupplier> find(long productId, long supplierId);

    /**
     * Insert when id null (createdAt = updatedAt = now), else update (updatedAt = now). Both instants are truncated
     * to MICROS before they are set, so what the client receives equals what Postgres stores.
     */
    ProductSupplier save(ProductSupplier row);

    /**
     * Bulk HQL "update ProductSupplierEntity set preferred = false where productId = :p and preferred = true",
     * executed at once; afterwards every managed ProductSupplierEntity of that product is refreshed (em.refresh),
     * so no stale managed copy can write preferred = true back.
     */
    void clearPreferred(long productId);

    void flush();

    void delete(long id);

    void deleteByProduct(long productId);
}
