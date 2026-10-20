package be.enrosed.finance.odoo;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OdooBankSyncSchedulePersistenceTest {
    @Inject OdooSyncStateStore store;
    @Inject EntityManager em;
    @Inject OdooTicaDocumentStore tracked;

    @Test void changedTicaSourceKeepsAcceptedFingerprintAndVisiblePendingReason() {
        String namespace=UUID.randomUUID().toString();
        var document=new OdooDocumentService.Document(91,"BILL/91","267653273","TICA","2026-10-12","in_refund","draft","EUR",
                new java.math.BigDecimal("95.58"),null,null,java.util.List.of());
        var source=new OdooDocumentService.PurchaseSnapshot(namespace,7,document,3,"BE0428038927","BE",
                new java.math.BigDecimal("-78.99"),new java.math.BigDecimal("-16.59"),new java.math.BigDecimal("-95.58"),"2026-10-12 10:00:00","a".repeat(64));
        assertNull(tracked.observe(source).acceptedHash());
        String key=OdooTicaCreditnoteImportService.sourceKey(namespace,91);
        tracked.mark(key,"IMPORTED",81L,true);
        var changed=new OdooDocumentService.PurchaseSnapshot(namespace,7,document,3,"BE0428038927","BE",source.netEur(),
                source.vatEur(),source.totalEur(),"2026-10-13 10:00:00","b".repeat(64));
        assertEquals("a".repeat(64),tracked.observe(changed).acceptedHash());
        tracked.mark(key,"SOURCE_CHANGED",81L,false);
        assertEquals(java.util.Map.of("SOURCE_CHANGED",1L),tracked.pendingReasons(namespace));
        assertEquals(81L,tracked.pending(namespace).getFirst().settlementId());
        assertEquals("267653273",tracked.pending(namespace).getFirst().reference());
        assertEquals("a".repeat(64),tracked.observe(changed).acceptedHash(),"Review flags never overwrite the imported source fingerprint");
        assertEquals("draft",tracked.observe(changed).acceptedSourceState(),"Retrying a changed source retains the accepted lifecycle state");
    }

    @Test void successPersistsTwelveHourDeadlineAndFailedManualRetryPreservesLastSuccess() {
        String key="bank-auto-"+UUID.randomUUID(),owner=UUID.randomUUID().toString();
        assertTrue(store.acquireBank(key,owner,true));
        assertFalse(store.acquireBank(key,"other-auto",true));
        assertEquals("BUSY",assertThrows(OdooFailure.class,()->store.acquireBank(key,"manual",false)).code);
        var active=store.bankSchedule(key);
        assertEquals("RUNNING",active.lastCode());assertNotNull(active.lastAttemptAt());assertNull(active.lastSuccessAt());
        store.finishBank(key,owner,"{\"complete\":true}",true,"READY");
        var success=store.bankSchedule(key);
        assertNotNull(success.lastSuccessAt());assertEquals("READY",success.lastCode());
        assertEquals(Duration.ofHours(12),Duration.between(success.lastSuccessAt(),success.nextRunAt()));
        assertFalse(store.acquireBank(key,"not-due",true));
        assertTrue(store.acquireBank(key,"manual",false));
        store.finishBank(key,"manual","{\"complete\":false}",false,"RATE_LIMITED");
        var failed=store.bankSchedule(key);
        assertEquals(success.lastSuccessAt(),failed.lastSuccessAt(),"A failed attempt never advances last success");
        assertEquals("RATE_LIMITED",failed.lastCode());
        assertTrue(Duration.between(Instant.now(),failed.nextRunAt()).toSeconds()<=1800);
        assertTrue(Duration.between(Instant.now(),failed.nextRunAt()).toSeconds()>1790);
        assertFalse(store.acquireBank(key,"restart-before-retry",true));
    }

    @Test void expiredProcessLeaseRecoversOnlyAfterDurableRetryTimeAndCannotOverrideNewOwner() {
        String key="bank-auto-"+UUID.randomUUID();
        assertTrue(store.acquireBank(key,"crashed",true));
        QuarkusTransaction.requiringNew().run(()->{
            var row=em.find(OdooSyncStateEntity.class,key);row.leaseUntil=Instant.now().minusSeconds(5);
        });
        assertFalse(store.acquireBank(key,"restart-too-soon",true));
        QuarkusTransaction.requiringNew().run(()->{
            var row=em.find(OdooSyncStateEntity.class,key);row.bankNextAutoAt=Instant.now().minusSeconds(1);
        });
        assertTrue(store.acquireBank(key,"recovered",true));
        assertEquals("LEASE_LOST",assertThrows(OdooFailure.class,()->store.finishBank(key,"crashed","{}",true,"READY")).code);
        store.finishBank(key,"recovered","{\"complete\":true}",true,"READY");
    }

    @Test void concurrentInstancesShareOneDueClaimAndAnExistingManualLeaseBlocksAutomaticImport() throws Exception {
        String key="bank-auto-"+UUID.randomUUID();store.checked(key,"READY");
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->{start.await();return store.acquireBank(key,"one",true);});
            var second=executor.submit(()->{start.await();return store.acquireBank(key,"two",true);});
            start.countDown();assertNotEquals(first.get(),second.get(),"Exactly one application instance owns the due run");
        }
        String manualKey="bank-manual-"+UUID.randomUUID();store.acquire(manualKey,"manual");
        assertFalse(store.acquireBank(manualKey,"automatic",true));
        store.finish(manualKey,"manual","{}");
        assertTrue(store.acquireBank(manualKey,"automatic",true));
    }
}
