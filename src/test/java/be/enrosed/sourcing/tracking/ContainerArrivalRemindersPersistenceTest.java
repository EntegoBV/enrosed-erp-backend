package be.enrosed.sourcing.tracking;

import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.tracking.ContainerArrivalEntities.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class ContainerArrivalRemindersPersistenceTest {
    @Inject ContainerArrivalReminders reminders;
    @Inject EntityManager entities;
    @Inject be.enrosed.sourcing.application.PurchaseOrderService purchases;
    @Inject be.enrosed.sourcing.application.SupplierService suppliers;
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    long purchase(int days) {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Arrival test supplier", "CN", "Ningbo",
                null, null, null, be.enrosed.shared.Currency.USD, "FOB", "Ningbo", 30, null));
        long id = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.9"), BigDecimal.ZERO).id();
        PurchaseOrderEntity order = entities.find(PurchaseOrderEntity.class, id);
        order.status = PurchaseOrderStatus.ONDERWEG;
        order.trackingReference = "arrival-reference-" + id;
        order.expectedArrival = LocalDate.of(2026, 10, 26).plusDays(days);
        entities.flush(); entities.clear(); return id;
    }

    void eta(long id, int days) {
        entities.find(PurchaseOrderEntity.class, id).expectedArrival = LocalDate.of(2026, 10, 26).plusDays(days);
        entities.flush(); entities.clear();
    }

    @Test @TestTransaction
    void restartAndEtaCorrectionsDoNotDuplicateThresholdAndStaleNoticeDisappears() {
        long id = purchase(10);
        reminders.reconcile(id, NOW);
        entities.flush(); entities.clear();
        reminders.reconcile(id, NOW.plusSeconds(300));
        assertEquals(1, Notice.count("orderId", id));
        var first = Notice.<Notice>find("orderId", id).firstResult();
        assertEquals(10, first.thresholdDays);
        String identity = first.id;
        assertEquals(identity, reminders.feed(NOW).stream().filter(row -> row.orderId() == id).findFirst().orElseThrow().notificationId());
        eta(id, 9);
        reminders.reconcile(id, NOW.plusSeconds(600));
        assertEquals(identity, Notice.<Notice>find("orderId", id).firstResult().id);
        assertTrue(reminders.feed(NOW).stream().filter(row -> row.orderId() == id).findFirst().orElseThrow().detail().contains("04/11/2026"));
        eta(id, 20);
        reminders.reconcile(id, NOW.plusSeconds(900));
        assertTrue(reminders.feed(NOW).stream().noneMatch(row -> row.orderId() == id));
        assertEquals("SUSPENDED", Notice.<Notice>findById(identity).status);
        eta(id, 3);
        reminders.reconcile(id, NOW.plusSeconds(1200));
        assertEquals(2, Notice.count("orderId", id));
        assertEquals(1, reminders.feed(NOW).stream().filter(row -> row.orderId() == id).count());
        assertEquals(1, Notice.count("orderId = ?1 and thresholdDays = 3", id));
        assertNotEquals(identity, reminders.feed(NOW).stream().filter(row -> row.orderId() == id).findFirst().orElseThrow().notificationId(),
                "Dismissing the ten-day reminder must not hide the three-day reminder");
    }

    @Test @TestTransaction
    void firstSeenNearArrivalProducesOnlyThreeDayNoticeAndNeverOlderTenDayAlert() {
        long id = purchase(2);
        assertTrue(reminders.preview(NOW).stream().anyMatch(row -> row.orderId() == id));
        assertEquals(0, Notice.count("orderId", id), "preview must never enqueue or send");
        reminders.reconcile(id, NOW);
        assertEquals(1, Notice.count("orderId", id));
        assertEquals(3, Notice.<Notice>find("orderId", id).firstResult().thresholdDays);
        eta(id, 8);
        reminders.reconcile(id, NOW.plusSeconds(300));
        assertEquals(1, Notice.count("orderId", id));
        entities.find(PurchaseOrderEntity.class, id).status = PurchaseOrderStatus.ONTVANGEN;
        entities.flush(); entities.clear();
        reminders.reconcile(id, NOW.plusSeconds(600));
        assertTrue(reminders.feed(NOW).stream().noneMatch(row -> row.orderId() == id));
    }

    @Test @TestTransaction
    void beforeNineAndDisabledFeatureDoNotCreateHistoricalReminders() {
        long id = purchase(3);
        reminders.reconcile(id, Instant.parse("2026-10-26T07:59:59Z"));
        assertEquals(0, Notice.count("orderId", id));
        reminders.reconcile(id, NOW);
        entities.flush(); entities.clear();
        assertEquals(1, Notice.count("orderId", id));
        var instance = io.quarkus.arc.ClientProxy.unwrap(reminders);
        boolean enabled = instance.enabled;
        try {
            instance.enabled = false;
            assertTrue(reminders.preview(NOW).isEmpty());
            assertTrue(reminders.feed(NOW).isEmpty());
            assertNull(reminders.claim(NOW));
        } finally { instance.enabled = enabled; }
    }

    @Test @TestTransaction
    void deviceAcceptanceSurvivesRetryLeaseAndChangedEtaInvalidatesAnOldClaim() {
        // Each test rolls back; no real push endpoint is contacted.
        Notice.deleteAll(); Delivery.deleteAll();
        long id = purchase(10);
        PushSubscriptionEntity device = new PushSubscriptionEntity();
        device.endpoint = "https://push.example.invalid/arrival-test"; device.p256dh = "test"; device.auth = "test"; device.persist();
        reminders.reconcile(id, NOW);
        entities.flush(); entities.clear();
        var claim = reminders.claim(NOW);
        assertNotNull(claim);
        assertNull(reminders.claim(NOW.plusSeconds(1)), "durable lease blocks a second worker");
        assertTrue(reminders.current(claim, NOW));
        eta(id, 9);
        assertFalse(reminders.current(claim, NOW), "never dispatch an old ETA after a correction");
        reminders.reconcile(id, NOW.plusSeconds(2));
        var corrected = reminders.claim(NOW.plusSeconds(3));
        assertNotNull(corrected);
        reminders.record(corrected, device.id, 201, NOW.plusSeconds(4));
        reminders.finish(corrected, NOW.plusSeconds(5));
        entities.flush(); entities.clear();
        assertNull(reminders.claim(NOW.plusSeconds(600)), "accepted device is not sent the same threshold again");
        assertNotNull(Delivery.<Delivery>find("noticeId", corrected.id()).firstResult().acceptedAt);
        assertEquals("ACCEPTED", Notice.<Notice>findById(corrected.id()).status);
    }

    @Test void previewIsAuthenticatedAndDoesNotNeedPushCredentials() {
        given().get("/api/container-arrival-reminders/preview").then().statusCode(401);
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .get("/api/container-arrival-reminders/preview").then().statusCode(200);
    }
}
