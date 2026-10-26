package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.util.Objects;

/** The product's own supplier and purchase columns: a server-kept copy of the preferred row. */
public record PurchaseMirror(Long supplierId, String supplierNote, BigDecimal exwPrice, Currency exwCurrency,
                             BigDecimal extraUnitCost) {

    public static PurchaseMirror of(Product product) {
        return new PurchaseMirror(product.supplierId(), product.supplierNote(), product.exwPrice(),
                product.exwCurrency(), product.extraUnitCost());
    }

    /** The preferred row as the product card shows it; a null currency stays null. */
    public static PurchaseMirror of(ProductSupplier preferred) {
        return new PurchaseMirror(preferred.supplierId(), preferred.supplierNote(), preferred.exwPrice(),
                preferred.exwCurrency(), preferred.extraUnitCost());
    }

    /** Same supplier, currency and note (equals) and same amounts by compareTo (2.35 == 2.350000; null == null). */
    public boolean sameAs(PurchaseMirror other) {
        if (other == null) return false;
        return Objects.equals(supplierId, other.supplierId)
                && Objects.equals(supplierNote, other.supplierNote)
                && exwCurrency == other.exwCurrency
                && sameAmount(exwPrice, other.exwPrice)
                && sameAmount(extraUnitCost, other.extraUnitCost);
    }

    /** Two amounts by value: null equals only null, 2.35 equals 2.350000. */
    public static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }
}
