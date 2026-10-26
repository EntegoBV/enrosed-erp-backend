package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one purchase price rule: the line, then the order supplier's row, then the product card. */
class PurchasePriceResolverTest {

    private static final long A = 7L;
    private static final long B = 8L;

    @Test
    void anOrderOfBTakesBsRowAlsoWhenItIsInactiveAndLeavesProductsWithoutOneAlone() {
        Product dome = product(1L, A, "2.35", Currency.USD, "0.10");
        Product vase = product(2L, A, "4.00", Currency.USD, "0.20");
        Product other = product(3L, B, "9.00", Currency.CNY, null);
        Product bowl = product(4L, A, "3.00", Currency.USD, null);
        PurchasePriceResolver resolver = resolver(List.of(
                row(11L, 1L, B, true, "2.10", Currency.USD, "0.15"),
                row(12L, 2L, B, false, null, null, null)));
        Map<Long, Product> catalog = Map.of(1L, dome, 2L, vase, 3L, other, 4L, bowl);

        Map<Long, Product> priced = resolver.forOrder(order(B), catalog);

        assertEquals(0, new BigDecimal("2.10").compareTo(priced.get(1L).exwPrice()));
        assertEquals(0, new BigDecimal("0.15").compareTo(priced.get(1L).extraUnitCost()));
        assertEquals(A, priced.get(1L).supplierId(), "the product keeps its own Voorkeur");
        assertNull(priced.get(2L).exwPrice(), "an inactive row prices too; its empty values stay empty");
        assertNull(priced.get(2L).exwCurrency());
        assertNull(priced.get(2L).extraUnitCost());
        assertSame(other, priced.get(3L), "B's own product without a row: its card is B's offer");
        assertSame(bowl, priced.get(4L), "a product B does not make stays as it is");
        assertEquals("Glass dome 1", priced.get(1L).name(), "only the three purchase fields change");
    }

    @Test
    void anOrderOfTheVoorkeurOrWithoutSupplierChangesNothing() {
        Product dome = product(1L, A, "2.35", Currency.USD, "0.10");
        Map<Long, Product> catalog = Map.of(1L, dome);
        PurchasePriceResolver resolver = resolver(List.of());

        assertSame(catalog, resolver.forOrder(order(A), catalog), "a legacy offer is the card itself");
        assertSame(catalog, resolver.forOrder(order(null), catalog));
        assertEquals(Map.of(), resolver.offersFor(null, catalog));
    }

    @Test
    void theLineWinsPerFieldAndTheCurrencyFallsBackToUsd() {
        Product card = product(1L, A, "2.35", Currency.CNY, "0.10");

        PurchasePrice lineOnly = PurchasePriceResolver.price(line("1.90", Currency.EUR, "0.05"), card);
        assertEquals(new PurchasePrice(new BigDecimal("1.90"), Currency.EUR, new BigDecimal("0.05")), lineOnly);

        PurchasePrice inherited = PurchasePriceResolver.price(line(null, null, null), card);
        assertEquals(new PurchasePrice(new BigDecimal("2.35"), Currency.CNY, new BigDecimal("0.10")), inherited);

        PurchasePrice mixed = PurchasePriceResolver.price(line("1.90", null, "0"), card);
        assertEquals(0, new BigDecimal("1.90").compareTo(mixed.exwPrice()));
        assertEquals(Currency.CNY, mixed.exwCurrency(), "per field, as the calculation always read it");
        assertEquals(0, BigDecimal.ZERO.compareTo(mixed.extraUnitCost()), "a frozen 0 is not empty");

        PurchasePrice bare = PurchasePriceResolver.price(line(null, null, null),
                product(1L, A, null, null, null));
        assertNull(bare.exwPrice());
        assertEquals(Currency.USD, bare.exwCurrency(), "no currency anywhere: USD");
        assertNull(bare.extraUnitCost());

        assertEquals(inherited, PurchasePriceResolver.price(null, card), "no line: the product's own");
        assertEquals(new PurchasePrice(null, Currency.USD, null), PurchasePriceResolver.price(null, null));
    }

    @Test
    void theLegacyResolverPricesExactlyAsBeforeRowsExisted() {
        Product ofA = product(1L, A, "2.35", Currency.USD, "0.10");
        Product ofB = product(2L, B, "9.00", Currency.CNY, null);
        Map<Long, Product> catalog = Map.of(1L, ofA, 2L, ofB);
        PurchasePriceResolver legacy = PurchasePriceResolver.legacy();

        assertSame(catalog, legacy.forOrder(order(B), catalog));
        assertSame(catalog, legacy.forOrder(order(A), catalog));
        assertEquals(List.of(2L), List.copyOf(legacy.offersFor(B, catalog).keySet()),
                "without rows, a supplier offers the products whose own supplier it is");
        assertNull(legacy.offersFor(B, catalog).get(2L).id());
    }

    /* ---------------------------------------------------------- helpers */

    @SuppressWarnings("unchecked")
    static PurchasePriceResolver resolver(List<ProductSupplier> rows) {
        ProductSupplierService service = new ProductSupplierService(new Rows(rows), null, null);
        Instance<ProductSupplierService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        PurchasePriceResolver resolver = new PurchasePriceResolver();
        resolver.rows = instance;
        return resolver;
    }

    static ProductSupplier row(Long id, long productId, long supplierId, boolean active, String exw, Currency currency,
                               String extra) {
        return new ProductSupplier(id, productId, supplierId, false, active, null, null,
                exw == null ? null : new BigDecimal(exw), currency, extra == null ? null : new BigDecimal(extra),
                null, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    static Product product(long id, Long supplierId, String exw, Currency currency, String extra) {
        return new Product(id, "SKU-" + id, "Glass dome " + id, Dimensions.empty(), null, null,
                1L, supplierId, true, Barcodes.none(), null,
                new Carton(Dimensions.empty(), 6, BigDecimal.ONE),
                exw == null ? null : new BigDecimal(exw), currency, extra == null ? null : new BigDecimal(extra),
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());
    }

    static PurchaseOrder order(Long supplierId) {
        return new PurchaseOrder(10L, "PO-TEST", null, supplierId, LocalDate.of(2026, 8, 1),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ, new BigDecimal("0.14"),
                new BigDecimal("0.90"), new BigDecimal("0.90"), BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD,
                BigDecimal.ZERO, new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
    }

    private static PurchaseOrderLine line(String exw, Currency currency, String extra) {
        return new PurchaseOrderLine(100L, 1L, 10, exw == null ? null : new BigDecimal(exw), currency,
                extra == null ? null : new BigDecimal(extra), 10);
    }

    /** The supplier rows in memory: only the reads the resolver needs. */
    static final class Rows implements ProductSupplierRepository {
        private final List<ProductSupplier> rows;

        Rows(List<ProductSupplier> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override public List<ProductSupplier> findByProduct(long productId) {
            return rows.stream().filter(row -> row.productId() == productId).toList();
        }
        @Override public List<ProductSupplier> findBySupplier(long supplierId) {
            return rows.stream().filter(row -> row.supplierId() == supplierId).toList();
        }
        @Override public Optional<ProductSupplier> findById(long id) {
            return rows.stream().filter(row -> row.id() != null && row.id() == id).findFirst();
        }
        @Override public Optional<ProductSupplier> find(long productId, long supplierId) {
            return rows.stream().filter(row -> row.productId() == productId && row.supplierId() == supplierId)
                    .findFirst();
        }
        @Override public ProductSupplier save(ProductSupplier row) {
            throw new UnsupportedOperationException("read only");
        }
        @Override public void clearPreferred(long productId) {
            throw new UnsupportedOperationException("read only");
        }
        @Override public void flush() {
        }
        @Override public void delete(long id) {
            throw new UnsupportedOperationException("read only");
        }
        @Override public void deleteByProduct(long productId) {
            throw new UnsupportedOperationException("read only");
        }
    }
}
