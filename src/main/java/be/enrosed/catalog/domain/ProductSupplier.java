package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One supplier ("versie") of a product: that supplier's own EXW price, reference, short version label, MOQ, lead time
 * and agreement note. One row per (product, supplier). The preferred row (Voorkeur) is what the product's own supplier
 * and purchase columns mirror; exactly one per product while it has rows, and it is always active.
 *
 * <p>The version label and the supplier's reference are purchasing-only: never on the website, catalogue, quotes or
 * invoices.</p>
 */
public record ProductSupplier(
        Long id, long productId, long supplierId, boolean preferred, boolean active,
        String versionLabel, String supplierReference,
        BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost,
        Integer moq, Integer leadTimeDays, String supplierNote,
        Instant createdAt, Instant updatedAt) {

    /** The row a product's own columns describe: preferred, active, id null until saved. */
    public static ProductSupplier preferredOf(Product product) {
        Objects.requireNonNull(product, "product");
        if (product.supplierId() == null) {
            throw new IllegalArgumentException("Product " + product.id() + " heeft geen leverancier");
        }
        return new ProductSupplier(null, product.id() == null ? 0L : product.id(), product.supplierId(),
                true, true, null, null,
                product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                null, null, product.supplierNote(), null, null);
    }

    /** An offer computed from the product itself (no row of that supplier exists yet): id null. */
    public boolean legacy() {
        return id == null;
    }

    public ProductSupplier withId(Long newId) {
        return new ProductSupplier(newId, productId, supplierId, preferred, active, versionLabel,
                supplierReference, exwPrice, exwCurrency, extraUnitCost, moq, leadTimeDays, supplierNote,
                createdAt, updatedAt);
    }

    public ProductSupplier withPreferred(boolean value) {
        return new ProductSupplier(id, productId, supplierId, value, active, versionLabel,
                supplierReference, exwPrice, exwCurrency, extraUnitCost, moq, leadTimeDays, supplierNote,
                createdAt, updatedAt);
    }

    public ProductSupplier withActive(boolean value) {
        return new ProductSupplier(id, productId, supplierId, preferred, value, versionLabel,
                supplierReference, exwPrice, exwCurrency, extraUnitCost, moq, leadTimeDays, supplierNote,
                createdAt, updatedAt);
    }

    public ProductSupplier withPurchase(BigDecimal newExwPrice, Currency newExwCurrency, BigDecimal newExtraUnitCost,
                                        String newSupplierNote) {
        return new ProductSupplier(id, productId, supplierId, preferred, active, versionLabel,
                supplierReference, newExwPrice, newExwCurrency, newExtraUnitCost, moq, leadTimeDays,
                newSupplierNote, createdAt, updatedAt);
    }

    /** Every field a row sheet edits (supplier, flags and instants stay). */
    public ProductSupplier withDetails(String newVersionLabel, String newSupplierReference,
                                       BigDecimal newExwPrice, Currency newExwCurrency, BigDecimal newExtraUnitCost,
                                       Integer newMoq, Integer newLeadTimeDays, String newSupplierNote) {
        return new ProductSupplier(id, productId, supplierId, preferred, active, newVersionLabel,
                newSupplierReference, newExwPrice, newExwCurrency, newExtraUnitCost, newMoq, newLeadTimeDays,
                newSupplierNote, createdAt, updatedAt);
    }
}
