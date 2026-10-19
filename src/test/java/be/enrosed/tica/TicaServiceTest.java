package be.enrosed.tica;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.tica.TicaEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static be.enrosed.tica.TicaCsvParserTest.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class TicaServiceTest {
    @Inject TicaService service;
    @Inject TicaCsvParser parser;
    @Inject TicaNotificationWorker notifications;
    @Inject EntityManager entities;
    @InjectMock TicaPushSender sender;
    private static final Instant BEFORE = Instant.parse("2026-10-19T18:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-19T19:00:00Z");

    @BeforeEach void resetState() {
        TicaService actual = io.quarkus.arc.ClientProxy.unwrap(service);
        actual.enabled = true; actual.username = "TT_2132"; actual.password = Optional.of("test-only-placeholder");
        TicaNotificationWorker worker = io.quarkus.arc.ClientProxy.unwrap(notifications);
        worker.enabled = true; worker.pushEnabled = true;
        reset(sender);
        QuarkusTransaction.requiringNew().run(() -> {
            TicaSettlementEntity.deleteAll(); Delivery.deleteAll(); Notice.deleteAll(); Sale.deleteAll(); Settings.deleteAll(); PushSubscriptionEntity.deleteAll();
        });
        service.ensureSettings();
    }

    @Test void defaultDailyAtNineAndReadEndpointsRequireAuthentication() {
        assertEquals(NotificationMode.DAILY, service.settings().notificationMode());
        assertEquals("21:00", service.settings().summaryTime());
        assertEquals("Europe/Brussels", service.settings().timezone());
        for (String path : List.of("status", "sales", "settings")) given().get("/api/tica/" + path).then().statusCode(401);
        given().contentType("application/json").body("{}").post("/api/tica/sync").then().statusCode(401);
        given().contentType("application/json").body("{\"notificationMode\":\"NONE\",\"summaryTime\":\"20:00\"}")
                .put("/api/tica/settings").then().statusCode(401);
        given().auth().preemptive().basic("emre", "named-auth-test-password").get("/api/tica/settings")
                .then().statusCode(200);
    }

    @Test void importIsIdempotentAndCorrectionsUpdateSameRowWithExactSkuOnly() {
        Long productId = QuarkusTransaction.requiringNew().call(() -> {
            List<ProductEntity> existing = entities.createQuery("from ProductEntity where sku = :sku", ProductEntity.class)
                    .setParameter("sku", "TICA-TEST-SKU").getResultList();
            if (!existing.isEmpty()) return existing.getFirst().id;
            ProductEntity product = new ProductEntity(); product.sku = "TICA-TEST-SKU"; product.name = "Synthetic TICA test product";
            entities.persist(product); entities.flush(); return product.id;
        });
        importAt(BEFORE, line("1", "2", "39,90"));
        var first = service.sales(DATE, DATE, 0, 100).lines().getFirst();
        assertEquals(productId, first.productId());
        assertEquals(0, noticeCount());
        importAt(BEFORE.plusSeconds(901), line("1", "2", "39,90"));
        var again = service.sales(DATE, DATE, 0, 100).lines().getFirst();
        assertEquals(first.id(), again.id()); assertEquals(first.updatedAt(), again.updatedAt());
        importAt(BEFORE.plusSeconds(1802), line("1", "3", "59,85"));
        var corrected = service.sales(DATE, DATE, 0, 100);
        assertEquals(1, corrected.totalLines()); assertEquals(first.id(), corrected.lines().getFirst().id());
        assertEquals("59.85", corrected.netTotal().toPlainString());
        importAt(BEFORE.plusSeconds(2703), line("1", "3", "59,85") + line("2", "1", "5,00").replace("TICA-TEST-SKU", "unknown"));
        assertEquals(1, service.sales(DATE, DATE, 0, 100).unmatchedLineCount());
    }

    @Test void onlyFreshCompleteAfterCutoffCreatesOneDailyDigest() {
        var before = service.claim(CUTOFF.minusSeconds(5));
        service.complete(before, parse(line("1", "2", "39,90")), CUTOFF.plusSeconds(5));
        assertEquals(0, noticeCount()); // finishing after cutoff is insufficient when export started earlier
        var fresh = service.claim(CUTOFF.plusSeconds(10));
        assertNotNull(fresh);
        service.complete(fresh, parse(line("1", "2", "39,90")), CUTOFF.plusSeconds(20));
        assertEquals(1, noticeCount());
        importAt(CUTOFF.plusSeconds(1000), line("1", "2", "39,90"));
        assertEquals(1, noticeCount());
    }

    @Test void initialHistoryAndEmptyDaysNeverSendSaleOrEmptyDigest() {
        service.updateSettings(new TicaService.SettingsDto(NotificationMode.SALES_AND_DAILY, "21:00", null));
        importAt(BEFORE, line("1", "2", "39,90"));
        assertEquals(0, noticeCount());
        importAt(CUTOFF, "");
        assertEquals(0, noticeCount());
    }

    @Test void crossInstanceLeasePreventsConcurrentImportsAndPreservesLastSuccessOnFailure() {
        var first = service.claim(BEFORE);
        assertNull(service.claim(BEFORE.plusSeconds(10)));
        service.complete(first, parse(line("1", "2", "39,90")), BEFORE.plusSeconds(20));
        var failed = service.claim(BEFORE.plusSeconds(901));
        service.failed(failed, "Synthetic safe failure");
        assertEquals(BEFORE.plusSeconds(20), service.status().lastSuccessAt());
        assertEquals("ERROR", service.status().syncState());
        assertEquals(1, service.sales(DATE, DATE, 0, 100).totalLines());
    }

    @Test void absentSubscribersNeverBecomeAcceptedAndRetryDoesNotResendAcceptedDevices() {
        importAt(CUTOFF, line("1", "2", "39,90"));
        makeNoticesDue(); notifications.work();
        assertEquals("WAITING_FOR_DEVICES", latestNotice().status);
        assertNull(latestNotice().acceptedAt); verifyNoInteractions(sender);
        List<Long> devices = QuarkusTransaction.requiringNew().call(() -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                PushSubscriptionEntity sub = new PushSubscriptionEntity(); sub.endpoint = "https://push.example.invalid/" + i;
                sub.p256dh = "test-public"; sub.auth = "test-auth"; sub.persistAndFlush(); ids.add(sub.id);
            } return ids;
        });
        when(sender.send(anyLong(), eq(devices.get(0)), anyString(), anyString(), anyInt())).thenReturn(201);
        when(sender.send(anyLong(), eq(devices.get(1)), anyString(), anyString(), anyInt())).thenReturn(503, 201);
        makeNoticesDue(); notifications.work(); assertEquals("PARTIAL", latestNotice().status);
        makeNoticesDue(); notifications.work(); assertEquals("ACCEPTED", latestNotice().status);
        verify(sender, times(1)).send(anyLong(), eq(devices.get(0)), anyString(), anyString(), anyInt());
        verify(sender, times(2)).send(anyLong(), eq(devices.get(1)), anyString(), anyString(), anyInt());
    }

    @Test void badSettingsAndDisabledConfigurationNeverQueueBrowserWork() {
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> service.updateSettings(
                new TicaService.SettingsDto(NotificationMode.DAILY, "25:00", null)));
        ((TicaService) io.quarkus.arc.ClientProxy.unwrap(service)).password = Optional.empty();
        assertNull(service.claim(BEFORE));
        assertThrows(be.enrosed.shared.BusinessRuleException.class, service::requestSync);
        assertFalse(service.status().credentialsReady());
    }

    @Test void batchingNeverExhaustsTheRetryBudgetOfUnattemptedDevices() {
        importAt(CUTOFF, line("1", "2", "39,90"));
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < 26; i++) {
                var sub = new PushSubscriptionEntity(); sub.endpoint = "https://push.example.invalid/batch-" + i;
                sub.p256dh = "test-public"; sub.auth = "test-auth"; sub.persist();
            }
        });
        when(sender.send(anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(201);
        for (int batch = 0; batch < 6; batch++) { makeNoticesDue(); notifications.work(); }
        assertEquals("ACCEPTED", latestNotice().status);
        verify(sender, times(26)).send(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        assertEquals(26, QuarkusTransaction.requiringNew().call(() -> Delivery.count("acceptedAt is not null and attemptCount = 1")));
    }

    private List<TicaCsvParser.Row> parse(String lines) { return parser.parse(HEADER + lines, "2132", DATE, DATE); }
    private void importAt(Instant now, String lines) {
        var claim = service.claim(now); assertNotNull(claim); service.complete(claim, parse(lines), now.plusSeconds(1));
    }
    private static long noticeCount() { return QuarkusTransaction.requiringNew().call(() -> Notice.count()); }
    private static Notice latestNotice() { return QuarkusTransaction.requiringNew().call(() -> Notice.<Notice>find("order by id desc").firstResult()); }
    private static void makeNoticesDue() {
        QuarkusTransaction.requiringNew().run(() -> { for (Notice row : Notice.<Notice>listAll()) {
            row.nextAttemptAt = Instant.EPOCH; row.expiresAt = Instant.now().plusSeconds(3600);
        }});
    }
}
