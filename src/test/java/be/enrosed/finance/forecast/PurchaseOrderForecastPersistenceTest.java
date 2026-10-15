package be.enrosed.finance.forecast;

import be.enrosed.finance.forecast.PurchaseOrderForecastService.Write;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.trash.DeletedItemDtos;
import be.enrosed.shared.trash.DeletedItemsService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchaseOrderForecastPersistenceTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Brussels"));

    @Inject PurchaseOrderForecastService forecasts;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject DeletedItemsService trash;
    @Inject EntityManager em;

    @Test @TestTransaction
    void putGetAndDeleteRoundTrip() {
        var order = order(PurchaseOrderStatus.BESTELD);
        var saved = forecasts.save(order.id(), new Write("MARKUP_PCT", new BigDecimal("60"), null, "EXTERNAL", 14, 21,
                true, true, order.orderDate().plusDays(30), null));
        assertEquals(order.id(), saved.orderId());
        assertEquals("MARKUP_PCT", saved.priceMode());
        assertEquals(new BigDecimal("60.00"), saved.markupPct());
        assertEquals("EXTERNAL", saved.costBasis());
        assertEquals(14, saved.sellWeeks());
        assertEquals(21, saved.paymentDays());
        assertEquals(Boolean.TRUE, saved.seasonal());
        assertEquals(Boolean.TRUE, saved.included());
        assertEquals(order.orderDate().plusDays(30), saved.expectedShipOn());
        assertNull(saved.orderDateOverride());
        assertNotNull(saved.updatedAt());
        assertNotNull(saved.actor());
        em.flush(); em.clear();
        assertEquals(List.of(saved), forecasts.list().stream().filter(row -> row.orderId().equals(order.id())).toList());

        var per = forecasts.save(order.id(), new Write("EUR_PER_UNIT", null, new BigDecimal("1.25"), "PRICING", null, null,
                null, null, null, null));
        assertEquals(new BigDecimal("1.2500"), per.markupEurPerUnit());
        assertNull(per.markupPct(), "every PUT is the whole set: what it leaves out is cleared");

        forecasts.delete(order.id());
        assertTrue(forecasts.list().stream().noneMatch(row -> row.orderId().equals(order.id())));
        forecasts.delete(order.id());
        assertNull(em.find(PurchaseOrderForecastEntity.class, order.id()), "a second delete is a no-op");
    }

    @Test @TestTransaction
    void everyRefusalInItsOrder() {
        var concept = order(PurchaseOrderStatus.CONCEPT);
        var ordered = order(PurchaseOrderStatus.BESTELD);
        var partner = order(PurchaseOrderStatus.BESTELD);
        entity(partner.id()).partnerCustomerId = 424242L;
        var shipped = order(PurchaseOrderStatus.ONDERWEG);
        em.flush();

        assertThrows(NotFoundException.class, () -> forecasts.save(987654321L, empty()));
        refused(concept.id(), new Write("GUESS", null, null, null, null, null, null, null, null, null),
                "Kies hoe de verkoopprijs bepaald wordt");
        for (String markup : List.of("-1", "1000.01", "12.345")) {
            refused(concept.id(), new Write(null, new BigDecimal(markup), null, null, null, null, null, null, null, null),
                    "Geef een opslag tussen 0 en 1000 %");
        }
        for (String perUnit : List.of("-0.01", "100000.0001", "1.23456")) {
            refused(concept.id(), new Write(null, null, new BigDecimal(perUnit), null, null, null, null, null, null, null),
                    "Geef een opslag per stuk van 0 of meer (maximaal vier decimalen)");
        }
        refused(concept.id(), new Write(null, null, null, "LIST", null, null, null, null, null, null),
                "Kies de kostbasis: extern of met Enrosed-kost");
        for (int weeks : new int[] {0, 105}) {
            refused(concept.id(), new Write(null, null, null, null, weeks, null, null, null, null, null),
                    "Verkocht in moet tussen 1 en 104 weken liggen");
        }
        for (int days : new int[] {-1, 366}) {
            refused(concept.id(), new Write(null, null, null, null, null, days, null, null, null, null),
                    "De betaaltermijn moet tussen 0 en 365 dagen liggen");
        }
        refused(partner.id(), new Write(null, null, null, null, null, null, null, true, null, null),
                "Een partnercontainer volgt het voorschotschema; kies geen prijs of tempo");
        refused(shipped.id(), new Write(null, null, null, null, null, null, null, null, TODAY.plusDays(3), null),
                "Deze container is al vertrokken; de vertrekdatum staat op de order");
        refused(ordered.id(), new Write(null, null, null, null, null, null, null, null, ordered.orderDate().minusDays(1), null),
                "De verwachte vertrekdatum ligt vóór de besteldatum");
        refused(ordered.id(), new Write(null, null, null, null, null, null, null, null, null, TODAY.plusDays(7)),
                "Alleen een concept heeft een geplande besteldatum");
        refused(concept.id(), new Write(null, null, null, null, null, null, null, null, null, TODAY.minusDays(1)),
                "Kies een besteldatum vanaf vandaag");

        /* The first rule that fails wins: an invalid price mode is named before an invalid week count. */
        refused(concept.id(), new Write("GUESS", null, null, null, 0, null, null, null, null, null),
                "Kies hoe de verkoopprijs bepaald wordt");
        assertNull(em.find(PurchaseOrderForecastEntity.class, concept.id()), "a refusal stores nothing");
    }

    @Test @TestTransaction
    void anAllNullBodyRemovesTheRow() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        forecasts.save(order.id(), new Write(null, null, null, null, 12, null, null, true, null, TODAY));
        assertNotNull(em.find(PurchaseOrderForecastEntity.class, order.id()));
        var cleared = forecasts.save(order.id(), empty());
        assertEquals(order.id(), cleared.orderId());
        assertNull(cleared.updatedAt());
        assertNull(cleared.sellWeeks());
        assertNull(em.find(PurchaseOrderForecastEntity.class, order.id()));
        assertNull(forecasts.save(order.id(), null).updatedAt(), "no body at all is the same empty set");
    }

    @Test @TestTransaction
    void aPartnerContainerTakesOnlyTheShipDate() {
        var partner = order(PurchaseOrderStatus.BESTELD);
        entity(partner.id()).partnerCustomerId = 424242L;
        em.flush();
        var saved = forecasts.save(partner.id(), new Write(null, null, null, null, null, null, null, null,
                partner.orderDate().plusDays(20), null));
        assertEquals(partner.orderDate().plusDays(20), saved.expectedShipOn());
        refused(partner.id(), new Write(null, new BigDecimal("50"), null, null, null, null, null, null,
                partner.orderDate().plusDays(20), null), "Een partnercontainer volgt het voorschotschema; kies geen prijs of tempo");
    }

    @Test @TestTransaction
    void storedValuesRepeatUnchangedAfterTheOrderMovedOn() {
        var order = order(PurchaseOrderStatus.BESTELD);
        LocalDate ship = order.orderDate().plusDays(30);
        forecasts.save(order.id(), new Write(null, new BigDecimal("40"), null, null, null, null, null, null, ship, null));
        entity(order.id()).status = PurchaseOrderStatus.ONDERWEG;
        em.flush();
        var saved = forecasts.save(order.id(), new Write(null, new BigDecimal("55"), null, null, null, null, null, null, ship, null));
        assertEquals(new BigDecimal("55.00"), saved.markupPct(), "only the markup changed: accepted");
        refused(order.id(), new Write(null, new BigDecimal("55"), null, null, null, null, null, null, ship.plusDays(1), null),
                "Deze container is al vertrokken; de vertrekdatum staat op de order");

        var concept = order(PurchaseOrderStatus.CONCEPT);
        forecasts.save(concept.id(), new Write(null, null, null, null, null, null, null, true, null, TODAY));
        LocalDate past = TODAY.minusDays(10);
        em.find(PurchaseOrderForecastEntity.class, concept.id()).orderDateOverride = past;
        em.flush();
        assertEquals(past, forecasts.save(concept.id(), new Write(null, null, null, null, 8, null, null, true, null, past))
                .orderDateOverride(), "a stored planned day that passed is repeated as is");
        entity(concept.id()).status = PurchaseOrderStatus.BESTELD;
        em.flush();
        assertEquals(9, forecasts.save(concept.id(), new Write(null, null, null, null, 9, null, null, true, null, past))
                .sellWeeks(), "the concept was ordered since: its stored planned day still repeats");

        var partner = order(PurchaseOrderStatus.BESTELD);
        forecasts.save(partner.id(), new Write(null, null, null, null, 10, null, null, null, null, null));
        entity(partner.id()).partnerCustomerId = 424242L;
        em.flush();
        assertEquals(partner.orderDate().plusDays(5), forecasts.save(partner.id(), new Write(null, null, null, null, 10,
                null, null, null, partner.orderDate().plusDays(5), null)).expectedShipOn(),
                "a choice stored before the container became a partner's repeats unchanged");
    }

    @Test @TestTransaction
    void aTrashedOrderKeepsItsChoicesForTheRestore() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        forecasts.save(order.id(), new Write(null, null, null, null, 12, null, null, true, null, null));
        purchases.delete(order.id());
        em.flush();
        assertTrue(forecasts.list().stream().noneMatch(row -> row.orderId().equals(order.id())), "GET skips a trashed order");
        assertThrows(NotFoundException.class, () -> forecasts.save(order.id(), new Write(null, null, null, null, 13, null,
                null, true, null, null)));
        assertNotNull(em.find(PurchaseOrderForecastEntity.class, order.id()), "deleting the order keeps the row");
        var item = trash.list().items().stream()
                .filter(row -> row.sourceId() == order.id() && row.type() == DeletedItemDtos.Type.PURCHASE_ORDER)
                .findFirst().orElseThrow();
        trash.restore(item.id());
        var back = forecasts.list().stream().filter(row -> row.orderId().equals(order.id())).findFirst().orElseThrow();
        assertEquals(12, back.sellWeeks(), "the restored order gets its choices back");
    }

    @Test
    void twoFirstChoicesArrivingTogetherBothSave() throws Exception {
        /* Two controls changed before the first answer: no stored row yet, so the order row serialises them. */
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            var order = order(PurchaseOrderStatus.BESTELD);
            return new long[] {order.id(), order.supplierId()};
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            java.util.function.IntFunction<Callable<Integer>> save = weeks -> () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return forecasts.save(ids[0], new Write(null, null, null, null, weeks, null, null, true, null, null)).sellWeeks();
            };
            var first = threads.submit(save.apply(12));
            var second = threads.submit(save.apply(14));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(12, first.get(20, TimeUnit.SECONDS), "neither PUT fails on the first insert");
            assertEquals(14, second.get(20, TimeUnit.SECONDS));
            int stored = QuarkusTransaction.requiringNew().call(() -> em.find(PurchaseOrderForecastEntity.class, ids[0]).sellWeeks);
            assertTrue(stored == 12 || stored == 14, "one row, holding the write that came last");
        } finally {
            start.countDown();
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("delete from purchase_order_forecast where order_id = :id").setParameter("id", ids[0]).executeUpdate();
                em.createNativeQuery("delete from purchase_order_line where order_id = :id").setParameter("id", ids[0]).executeUpdate();
                em.createNativeQuery("delete from purchase_order where id = :id").setParameter("id", ids[0]).executeUpdate();
                em.createNativeQuery("delete from supplier where id = :id").setParameter("id", ids[1]).executeUpdate();
            });
        }
    }

    @Test
    void anonymousCannotReadOrWrite() {
        given().get("/api/purchase-order-forecasts").then().statusCode(401);
        given().contentType("application/json").body("{}").put("/api/purchase-order-forecasts/1").then().statusCode(401);
    }

    @Test @TestSecurity(user = "staff-fixture", roles = "staff")
    void staffWithoutAdminIsRefused() {
        given().get("/api/purchase-order-forecasts").then().statusCode(403);
        given().delete("/api/purchase-order-forecasts/1").then().statusCode(403);
    }

    private void refused(long orderId, Write write, String message) {
        var refusal = assertThrows(BusinessRuleException.class, () -> forecasts.save(orderId, write));
        assertEquals(message, refusal.getMessage());
    }

    private static Write empty() {
        return new Write(null, null, null, null, null, null, null, null, null, null);
    }

    private SourcingEntities.PurchaseOrderEntity entity(long id) {
        return em.find(SourcingEntities.PurchaseOrderEntity.class, id);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        var supplier = suppliers.save(new Supplier(null, "Forecast supplier", "CN", "Yiwu", null,
                null, null, Currency.USD, "FOB", "Ningbo", 35, null));
        var order = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var row = entity(order.id());
        row.status = status;
        if (row.orderDate == null) row.orderDate = TODAY.minusDays(5);
        em.flush();
        return purchases.get(order.id());
    }
}
