package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductSupplierOffers;
import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one price rule for purchasing (one product, several suppliers, 2026-10-25): a line's own price first, then
 * what the order's supplier asks for the product (its row), then the product card. Replaces the six inline
 * fallbacks of the calculation, the lot cost, the receipt values, the cost history and the PDFs.
 *
 * <p>Cost per calculation: one indexed {@code product_supplier} query (the order supplier's rows); no cache.</p>
 */
@ApplicationScoped
public class PurchasePriceResolver {

    @Inject
    Instance<ProductSupplierService> rows;

    /** For pure tests and absent beans: no rows, every product offered by its own supplier. */
    public static PurchasePriceResolver legacy() {
        return new PurchasePriceResolver();
    }

    /** productId -> what supplierId offers it at (its row, or a legacy offer from the product); one query. */
    public Map<Long, ProductSupplier> offersFor(Long supplierId, Map<Long, Product> productsById) {
        if (supplierId == null || productsById == null || productsById.isEmpty()) return Map.of();
        if (rows != null && rows.isResolvable()) return rows.get().offersFor(supplierId, productsById);
        return ProductSupplierOffers.of(supplierId, productsById.values(), List.of());
    }

    /**
     * The products as the order's supplier sells them: exwPrice/exwCurrency/extraUnitCost replaced by the order
     * supplier's row (active or inactive; a row's null values stay null). Products without such a row unchanged.
     */
    public Map<Long, Product> forOrder(PurchaseOrder order, Map<Long, Product> productsById) {
        if (order == null || order.supplierId() == null || productsById == null || productsById.isEmpty()) {
            return productsById;
        }
        return priced(offersFor(order.supplierId(), productsById), productsById);
    }

    /**
     * The products with these offers' prices (from {@link #offersFor}); a legacy offer (no row) leaves its product
     * as it is. For callers that need the offers too, so the rows are read once.
     */
    public static Map<Long, Product> priced(Map<Long, ProductSupplier> offers, Map<Long, Product> productsById) {
        Map<Long, Product> priced = null;
        for (Map.Entry<Long, ProductSupplier> entry : offers.entrySet()) {
            ProductSupplier offer = entry.getValue();
            if (offer == null || offer.legacy()) continue;
            Product product = productsById.get(entry.getKey());
            if (product == null) continue;
            if (priced == null) priced = new HashMap<>(productsById);
            priced.put(entry.getKey(), product.withPurchasePrice(offer.exwPrice(), offer.exwCurrency(),
                    offer.extraUnitCost()));
        }
        return priced == null ? productsById : priced;
    }

    /**
     * The one line-over-product rule, per field: amount line ?? product; currency line ?? product ?? USD;
     * extra line ?? product. Extra is read in that currency (as today). A missing line or product reads as empty.
     */
    public static PurchasePrice price(PurchaseOrderLine line, Product pricedProduct) {
        java.math.BigDecimal amount = line != null && line.exwPrice() != null ? line.exwPrice()
                : pricedProduct == null ? null : pricedProduct.exwPrice();
        Currency currency = line != null && line.exwCurrency() != null ? line.exwCurrency()
                : pricedProduct == null ? null : pricedProduct.exwCurrency();
        java.math.BigDecimal extra = line != null && line.extraUnitCost() != null ? line.extraUnitCost()
                : pricedProduct == null ? null : pricedProduct.extraUnitCost();
        return new PurchasePrice(amount, currency == null ? Currency.USD : currency, extra);
    }
}
