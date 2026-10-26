package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PurchaseMirror;

import java.util.Collection;
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

    /** The products with these ids that exist; empty ids give an empty list. */
    default List<Product> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return findAll().stream().filter(product -> product.id() != null && ids.contains(product.id())).toList();
    }

    /**
     * Writes only the five supplier and purchase columns (the copy of the preferred supplier row). Persistent
     * adapters override this with one targeted update, so no other column, translation or photo is rewritten.
     */
    default void updatePurchaseMirror(long productId, PurchaseMirror mirror) {
        Product current = findById(productId)
                .orElseThrow(() -> new be.enrosed.shared.NotFoundException("Product", productId));
        save(current.withPurchaseMirror(mirror));
    }
    Optional<Product> findBySku(String sku);
    Optional<Product> findByPublicHandle(String publicHandle);
    Product save(Product product);

    /** Persists only the photo series; product master data and translations stay untouched. */
    default Product savePhotos(Product product) {
        Product current = findById(product.id())
                .orElseThrow(() -> new be.enrosed.shared.NotFoundException("Product", product.id()));
        return save(current.withPhotos(product.photos()));
    }

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
