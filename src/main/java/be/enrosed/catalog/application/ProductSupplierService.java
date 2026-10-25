package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The suppliers ("versies") of a product: one row per supplier with its own EXW price, reference, version label,
 * MOQ, lead time and agreement note. The preferred row is mirrored onto the product's own purchase columns.
 * Never imports {@code be.enrosed.sourcing} (catalog stays below sourcing).
 */
@ApplicationScoped
public class ProductSupplierService {

    public record SupplierUsage(long products, long inactive) {}

    private final ProductSupplierRepository rows;
    private final ProductService products;
    private final ProductSupplierAgreementPhotoService agreementPhotos;

    @Inject
    Instance<ActivityLogService> activity;

    @Inject
    public ProductSupplierService(ProductSupplierRepository rows, ProductService products,
                                  ProductSupplierAgreementPhotoService agreementPhotos) {
        this.rows = rows;
        this.products = products;
        this.agreementPhotos = agreementPhotos;
    }

    /** Read only, never heals. Preferred first, then active, then id. */
    public List<ProductSupplier> rows(long productId) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    public Optional<ProductSupplier> row(long productId, long supplierId) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    public List<ProductSupplier> rowsOfSupplier(long supplierId) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    /** From products.offeredBy(supplierId): rows whose product exists (active or inactive) plus legacy products. */
    public SupplierUsage usage(long supplierId) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    /** findBySupplier + ProductSupplierOffers.of: one indexed query. */
    public Map<Long, ProductSupplier> offersFor(long supplierId, Map<Long, Product> productsById) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    @Transactional
    public ProductSupplier add(long productId, ProductSupplierWrite write, String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    @Transactional
    public ProductSupplier update(long productId, long rowId, ProductSupplierWrite write, String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    @Transactional
    public ProductSupplier updateNote(long productId, long rowId, String supplierNote, Instant expectedUpdatedAt,
                                      String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    @Transactional
    public ProductSupplier setPreferred(long productId, long rowId, String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    @Transactional
    public ProductSupplier setActive(long productId, long rowId, boolean active, String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    /** The caller checked purchase order usage. */
    @Transactional
    public void delete(long productId, long rowId, String supplierName) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }

    public PurchaseMirror mirror(long productId) {
        throw new UnsupportedOperationException("BE-S1 step 2");
    }
}
