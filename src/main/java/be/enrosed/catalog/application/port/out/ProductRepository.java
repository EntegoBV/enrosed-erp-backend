package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Product;

import java.util.List;
import java.util.Optional;

/** Outbound port to product storage. */
public interface ProductRepository {
    /** Business records that keep a product part of the operational history. */
    record ReferenceCounts(
            long purchaseOrderLines,
            long salesOrderLines,
            long salesPalletItems,
            long quoteRevisionLines) {

        public static ReferenceCounts none() {
            return new ReferenceCounts(0, 0, 0, 0);
        }

        public long total() {
            return purchaseOrderLines + salesOrderLines + salesPalletItems + quoteRevisionLines;
        }
    }

    List<Product> findAll();
    List<Product> findBySupplier(long supplierId);
    default List<Product> findByFamily(long familyId) {
        return findAll().stream()
                .filter(product -> product.familyId() != null && product.familyId() == familyId)
                .toList();
    }
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
    /**
     * Sets the count outright - a manual correction after a recount.
     *
     * @return the product as it was before, or empty when it does not exist
     */
    default Optional<Product> setStock(long productId, int quantity) {
        Optional<Product> current = findById(productId);
        current.ifPresent(product -> save(product.withStockQuantity(quantity)));
        return current;
    }

    default boolean adjustStock(long productId, int delta) {
        Optional<Product> current = findById(productId);
        if (current.isEmpty()) return false;
        Product product = current.get();
        save(product.withStockQuantity(product.stockQuantity() + delta));
        return true;
    }

    void deleteById(long id);

    /**
     * Counts operational references that must outlive a catalogue product.
     *
     * Pure domain adapters have no order graph, so their safe default is empty.
     * Persistent adapters must override this and inspect every business graph.
     */
    default ReferenceCounts referenceCounts(long productId) {
        return ReferenceCounts.none();
    }

    /** Active stock-bearing variants left on a canonical product family. */
    default long countActiveByFamily(long familyId) {
        return findAll().stream()
                .filter(Product::active)
                .filter(product -> product.familyId() != null && product.familyId() == familyId)
                .count();
    }

    long countByCategory(long categoryId);
    long countByHsCode(String hsCode);
    long countBySupplier(long supplierId);
}
