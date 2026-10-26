package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The one rule for "who makes what". */
public final class ProductSupplierOffers {

    private ProductSupplierOffers() {
    }

    /**
     * productId -> offer of supplierId: each row of supplierId whose product is in {@code products} (active or
     * inactive), plus a legacy offer ({@link ProductSupplier#preferredOf}, id null) for every product whose own
     * supplierId equals supplierId and that has no row of supplierId. Rows of products not in {@code products}
     * (orphans) are ignored. In the order of {@code products}.
     */
    public static Map<Long, ProductSupplier> of(long supplierId, Collection<Product> products,
                                               List<ProductSupplier> rowsOfSupplier) {
        Map<Long, ProductSupplier> rowByProduct = new HashMap<>();
        if (rowsOfSupplier != null) {
            for (ProductSupplier row : rowsOfSupplier) {
                if (row != null && row.supplierId() == supplierId) rowByProduct.putIfAbsent(row.productId(), row);
            }
        }
        Map<Long, ProductSupplier> offers = new LinkedHashMap<>();
        if (products == null) return offers;
        for (Product product : products) {
            if (product == null || product.id() == null || offers.containsKey(product.id())) continue;
            ProductSupplier row = rowByProduct.get(product.id());
            if (row != null) {
                offers.put(product.id(), row);
            } else if (Objects.equals(product.supplierId(), supplierId)) {
                offers.put(product.id(), ProductSupplier.preferredOf(product));
            }
        }
        return offers;
    }
}
