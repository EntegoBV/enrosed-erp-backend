package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchaseOrderServiceTest {

    @Test
    void lifecycleOnlyMovesForwardIncludingLegacyUnderwayPath() {
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.BESTELD));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.ONDERWEG));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.BESTELD, PurchaseOrderStatus.ONTVANGEN));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.ONDERWEG, PurchaseOrderStatus.ONTVANGEN));

        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.ONTVANGEN));
        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.BESTELD, PurchaseOrderStatus.CONCEPT));
        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.ONTVANGEN, PurchaseOrderStatus.ONDERWEG));
    }

    @Test
    void receivingCountsTheContainerAndBooksTheUsablePiecesOnce() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        RecordingProducts products = new RecordingProducts();
        PurchaseOrderService service = service(orders, products);

        /* Five arrived of the six ordered, one of them broken; book at once. */
        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(1L, 5, 1)), true,
                new java.math.BigDecimal("1234.56"), java.time.LocalDate.of(2026, 8, 23), "Doos 3 was nat."));

        assertEquals(PurchaseOrderStatus.ONTVANGEN, received.status());
        assertEquals(4, products.stockDelta, "arrived minus broken goes on the shelf");
        assertEquals(5, received.lines().getFirst().quantity());
        assertEquals(6, received.lines().getFirst().orderedQuantity(), "the order remembers what was ordered");
        assertEquals(1, received.lines().getFirst().damaged());
        assertEquals(new java.math.BigDecimal("1234.56"), received.paidTotalEur());
        assertTrue(received.isStockBooked());
        assertTrue(received.notes().contains("Ontvangst 23/08/2026:"), received.notes());
        assertTrue(received.notes().contains("besteld 6, ontvangen 5, 1 beschadigd"), received.notes());
        assertTrue(received.notes().contains("Doos 3 was nat."), received.notes());

        assertThrows(BusinessRuleException.class, () -> service.bookStock(10L), "never twice");
        assertEquals(4, products.stockDelta);
    }

    @Test
    void receivingWithoutBookingLeavesStockForLater() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.ONDERWEG, 6, 6));
        RecordingProducts products = new RecordingProducts();
        PurchaseOrderService service = service(orders, products);

        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(), false, null, null, null));
        assertEquals(0, products.stockDelta, "received, not booked");
        assertFalse(received.isStockBooked());
        assertTrue(received.notes().contains("alles volgens bestelling"), received.notes());

        PurchaseOrder booked = service.bookStock(10L);
        assertEquals(6, products.stockDelta);
        assertTrue(booked.isStockBooked());
    }

    @Test
    void storedOrderedQuantityWinsOverClientValueAfterPlacement() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        PurchaseOrder saved = service.update(10L,
                order(PurchaseOrderStatus.BESTELD, 9, 999)).order();

        assertEquals(9, saved.lines().getFirst().quantity());
        assertEquals(6, saved.lines().getFirst().orderedQuantity());
    }

    @Test
    void receivedLinesCannotChangeAndReceivedOrderCannotBeDeleted() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.ONTVANGEN, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        assertThrows(BusinessRuleException.class,
                () -> service.update(10L, order(PurchaseOrderStatus.ONTVANGEN, 7, 6)));
        assertThrows(BusinessRuleException.class, () -> service.delete(10L));
        assertFalse(orders.deleted);
    }

    @Test
    void invalidReferencesAndNegativeCommercialValuesAreBusinessRules() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.CONCEPT, 6, null));
        PurchaseOrderService missingSupplier = new PurchaseOrderService(
                orders, new FixedSuppliers(false), new RecordingProducts(), null);

        assertThrows(BusinessRuleException.class, () -> missingSupplier.update(
                10L, order(PurchaseOrderStatus.CONCEPT, 6, null)));

        PurchaseOrder base = order(PurchaseOrderStatus.CONCEPT, 6, null);
        PurchaseOrder negativeFreight = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                new BigDecimal("-1"), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.notes(), base.lines());
        assertThrows(BusinessRuleException.class,
                () -> service(orders, new RecordingProducts()).update(10L, negativeFreight));
    }

    @Test
    void createUsesOneUsdRateAndNewPurchaseDefaults() {
        InMemoryOrders orders = new InMemoryOrders(null);
        PurchaseOrder created = service(orders, new RecordingProducts()).create(
                7L, new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("5"));

        assertEquals(new BigDecimal("0.91"), created.usdToEurGoods());
        assertEquals(created.usdToEurGoods(), created.usdToEurTransport());
        assertEquals(new BigDecimal("2000"), created.extraRevenueEur());
        assertEquals("Ningbo", created.departurePort());
        assertEquals("Rotterdam", created.destinationPort());
    }

    @Test
    void placingOrderSnapshotsQuantityAndUnifiesLegacyRates() {
        PurchaseOrder concept = withRates(
                order(PurchaseOrderStatus.CONCEPT, 6, null), "0.82", "0.94");
        InMemoryOrders orders = new InMemoryOrders(concept);

        PurchaseOrder placed = service(orders, new RecordingProducts())
                .update(10L, withStatus(concept, PurchaseOrderStatus.BESTELD)).order();

        assertEquals(PurchaseOrderStatus.BESTELD, placed.status());
        assertEquals(6, placed.lines().getFirst().orderedQuantity());
        assertEquals(new BigDecimal("0.82"), placed.usdToEurGoods());
        assertEquals(placed.usdToEurGoods(), placed.usdToEurTransport());
        assertEquals("Shanghai", placed.departurePort());
    }

    @Test
    void duplicateOfHistoricalOrderBecomesSingleRateDraft() {
        PurchaseOrder historical = withRates(
                order(PurchaseOrderStatus.BESTELD, 6, 6), "0.80", "0.93");
        InMemoryOrders orders = new InMemoryOrders(historical);

        PurchaseOrder copy = service(orders, new RecordingProducts()).duplicate(10L);

        assertEquals(PurchaseOrderStatus.CONCEPT, copy.status());
        assertEquals(new BigDecimal("0.80"), copy.usdToEurGoods());
        assertEquals(copy.usdToEurGoods(), copy.usdToEurTransport());
        assertEquals(historical.departurePort(), copy.departurePort());
    }

    private static PurchaseOrderService service(InMemoryOrders orders, RecordingProducts products) {
        return new PurchaseOrderService(orders, new FixedSuppliers(true), products, null);
    }

    private static PurchaseOrder order(PurchaseOrderStatus status, int quantity,
                                       Integer orderedQuantity) {
        return new PurchaseOrder(10L, "PO-TEST", null, 7L, LocalDate.now(), status,
                ContainerType.FORTY_HQ, new BigDecimal("0.14"), new BigDecimal("0.90"),
                new BigDecimal("0.90"), BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD,
                BigDecimal.ZERO, new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Shanghai", "Rotterdam", null,
                List.of(new PurchaseOrderLine(100L, 1L, quantity,
                        new BigDecimal("4"), Currency.USD, BigDecimal.ZERO, orderedQuantity)));
    }

    private static PurchaseOrder withRates(PurchaseOrder source, String goods, String transport) {
        return new PurchaseOrder(source.id(), source.number(), source.alias(), source.supplierId(),
                source.orderDate(), source.status(), source.containerType(), source.cnyToUsd(),
                new BigDecimal(goods), new BigDecimal(transport), source.freightUsd(),
                source.originCosts(), source.originCurrency(), source.destinationCostsEur(),
                source.defaultDutyRatePct(), source.extraRevenueEur(), source.allocFreight(),
                source.allocOrigin(), source.allocDestination(), source.allocExtra(),
                source.departurePort(), source.destinationPort(), source.notes(), source.lines());
    }

    private static PurchaseOrder withStatus(PurchaseOrder source, PurchaseOrderStatus status) {
        return new PurchaseOrder(source.id(), source.number(), source.alias(), source.supplierId(),
                source.orderDate(), status, source.containerType(), source.cnyToUsd(),
                source.usdToEurGoods(), source.usdToEurTransport(), source.freightUsd(),
                source.originCosts(), source.originCurrency(), source.destinationCostsEur(),
                source.defaultDutyRatePct(), source.extraRevenueEur(), source.allocFreight(),
                source.allocOrigin(), source.allocDestination(), source.allocExtra(),
                source.departurePort(), source.destinationPort(), source.notes(), source.lines());
    }

    private static Product product() {
        return new Product(1L, "SKU-1", "Testproduct", Dimensions.empty(), null, null,
                1L, 7L, true, Barcodes.none(), null,
                new Carton(Dimensions.empty(), 6, BigDecimal.ONE),
                new BigDecimal("4"), Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());
    }

    private static final class RecordingProducts extends ProductService {
        private int stockDelta;

        private RecordingProducts() {
            super(null, null, null);
        }

        @Override
        public List<Product> list() {
            return List.of(product());
        }

        @Override
        public void receiveStock(long productId, int delta, String reference, Long locationId) {
            assertEquals(1L, productId);
            assertTrue(reference != null && !reference.isBlank(),
                    "a receipt books stock under its purchase order number");
            stockDelta += delta;
        }
    }

    private static final class FixedSuppliers implements SourcingRepositories.Suppliers {
        private final boolean exists;

        private FixedSuppliers(boolean exists) {
            this.exists = exists;
        }

        @Override
        public List<Supplier> findAll() {
            return exists ? List.of(supplier()) : List.of();
        }

        @Override
        public Optional<Supplier> findById(long id) {
            return exists && id == 7L ? Optional.of(supplier()) : Optional.empty();
        }

        @Override
        public Supplier save(Supplier supplier) {
            return supplier;
        }

        @Override
        public void deleteById(long id) {}

        private static Supplier supplier() {
            return new Supplier(7L, "Leverancier", "CN", null, null, null, null,
                    Currency.USD, "FOB", "Shanghai", 30, null);
        }
    }

    private static final class InMemoryOrders implements SourcingRepositories.PurchaseOrders {
        private PurchaseOrder current;
        private boolean deleted;

        private InMemoryOrders(PurchaseOrder current) {
            this.current = current;
        }

        @Override
        public List<PurchaseOrder> findAll() {
            return current == null ? List.of() : List.of(current);
        }

        @Override
        public Optional<PurchaseOrder> findById(long id) {
            return current != null && current.id() == id ? Optional.of(current) : Optional.empty();
        }

        @Override
        public PurchaseOrder save(PurchaseOrder order) {
            current = order;
            return order;
        }

        @Override
        public void deleteById(long id) {
            deleted = true;
            current = null;
        }
    }
}
