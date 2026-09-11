package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.adapter.out.persistence.SalesSplitGroupEntity;
import be.enrosed.sales.domain.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class SalesSplitTransactionsTest {
    @Inject SalesSplits splits;
    @InjectSpy SalesOrderService sales;
    @Inject EntityManager em;

    @Test
    void failureAfterSavingFirstPortionRollsBackAllQuantitiesAndSameRequestCanRetry() {
        Fixture fixture = fixture();
        try {
            var request = request(fixture.orderId());
            doThrow(new IllegalStateException("Cannot create second delivery")).when(sales).numberSplitDraft(any(SalesOrder.class));
            assertThrows(IllegalStateException.class, () -> splits.split(fixture.orderId(), request));
            doCallRealMethod().when(sales).numberSplitDraft(any(SalesOrder.class));
            QuarkusTransaction.requiringNew().run(() -> {
                assertEquals(48, sales.get(fixture.orderId()).lines().getFirst().quantity());
                assertNull(splits.fulfillment(sales.get(fixture.orderId())));
                assertEquals(0L, em.createQuery("select count(g) from SalesSplitGroupEntity g where g.rootOrderId=:id", Long.class)
                        .setParameter("id", fixture.orderId()).getSingleResult());
            });
            var result = splits.split(fixture.orderId(), request);
            assertEquals(24, result.current().lines().getFirst().quantity());
            assertEquals(24, result.later().lines().getFirst().quantity());
            assertEquals(result.groupId(), splits.split(fixture.orderId(), request).groupId());
        } finally {
            doCallRealMethod().when(sales).numberSplitDraft(any(SalesOrder.class));
            cleanup(fixture);
        }
    }

    @Test
    void concurrentConfirmationCreatesOneGroupAndOneSecondDelivery() throws Exception {
        Fixture fixture = fixture();
        try {
            var request = request(fixture.orderId());
            var start = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                Callable<SalesSplits.Result> confirm = () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return splits.split(fixture.orderId(), request);
                };
                var first = threads.submit(confirm); var second = threads.submit(confirm); start.countDown();
                var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
                assertEquals(a.groupId(), b.groupId()); assertEquals(a.later().id(), b.later().id());
            }
            QuarkusTransaction.requiringNew().run(() -> assertEquals(1L,
                    em.createQuery("select count(g) from SalesSplitGroupEntity g where g.rootOrderId=:id", Long.class)
                            .setParameter("id", fixture.orderId()).getSingleResult()));
        } finally { cleanup(fixture); }
    }

    private SalesSplits.Request request(long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var source = sales.get(id);
            var selection = List.of(new SalesSplits.Choice(source.lines().getFirst().id(), 24));
            var preview = splits.preview(id, new SalesSplits.Request(selection, null, null, null));
            return new SalesSplits.Request(selection, null, preview.previewToken(), UUID.randomUUID().toString());
        });
    }

    private record Fixture(long orderId, long productId) {}
    private Fixture fixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var product = new ProductEntity(); product.sku = "SPLIT-TX-" + UUID.randomUUID(); product.name = "Atomic test";
            product.active = true; product.piecesPerCarton = 12; product.inventoryKnown = true; product.stockQuantity = 100;
            product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN; product.cartonHeightCm = BigDecimal.TEN;
            product.cartonWeightKg = BigDecimal.ONE; product.landedCostEur = BigDecimal.ONE; product.fixedSalesPriceEur = BigDecimal.TEN;
            em.persist(product); em.flush();
            var order = new SalesOrderEntity(); order.number = "SPLIT-TX-" + UUID.randomUUID(); order.countryCode = "BE";
            order.docType = DocumentType.FACTUUR; order.freight = FreightState.BEREKEND;
            order.freightPricingStrategy = FreightPricingStrategy.FIXED; order.manualFreightEur = BigDecimal.ZERO;
            var line = new SalesOrderLineEntity(); line.order = order; line.productId = product.id; line.quantity = 48;
            line.unitPriceEur = BigDecimal.TEN; line.unitCostEur = BigDecimal.ONE;
            order.lines.add(line); em.persist(order); em.flush();
            return new Fixture(order.id, product.id);
        });
    }

    private void cleanup(Fixture fixture) {
        QuarkusTransaction.requiringNew().run(() -> {
            var groups = em.createQuery("from SalesSplitGroupEntity g where g.rootOrderId=:id", SalesSplitGroupEntity.class)
                    .setParameter("id", fixture.orderId()).getResultList();
            var ids = new java.util.ArrayList<Long>(); ids.add(fixture.orderId());
            for (var group : groups) {
                ids.add(group.laterOrderId);
                em.createNativeQuery("delete from sales_split_part where group_id=:id").setParameter("id", group.id).executeUpdate();
                em.remove(group);
            }
            em.flush();
            for (long id : ids) {
                em.createNativeQuery("delete from quote_event where salesOrderId=:id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("delete from activity_log where entity_type='SALES_ORDER' and entity_id=:id").setParameter("id", Long.toString(id)).executeUpdate();
                var order = em.find(SalesOrderEntity.class, id); if (order != null) em.remove(order);
            }
            em.flush(); em.remove(em.find(ProductEntity.class, fixture.productId()));
        });
    }
}
