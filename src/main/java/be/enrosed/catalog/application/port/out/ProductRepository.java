package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Product;

import java.util.List;
import java.util.Optional;

/** Outbound port to product storage. */
public interface ProductRepository {
    List<Product> findAll();
    List<Product> findBySupplier(long supplierId);
    Optional<Product> findById(long id);
    Optional<Product> findBySku(String sku);
    Optional<Product> findByPublicHandle(String publicHandle);
    Product save(Product product);

    /**
     * Changes stock as one storage operation.
     *
     * The default keeps simple in-memory adapters useful. Persistent adapters
     * must override this with an atomic update so receipts from two different
     * purchase orders cannot overwrite each other.
     *
     * @return whether the product existed
     */
    default boolean adjustStock(long productId, int delta) {
        Optional<Product> current = findById(productId);
        if (current.isEmpty()) return false;
        Product product = current.get();
        save(product.withStockQuantity(product.stockQuantity() + delta));
        return true;
    }

    void deleteById(long id);
    long countByCategory(long categoryId);
    long countByHsCode(String hsCode);
    long countBySupplier(long supplierId);
}
