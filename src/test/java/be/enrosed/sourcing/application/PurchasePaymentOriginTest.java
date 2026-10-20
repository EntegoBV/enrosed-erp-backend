package be.enrosed.sourcing.application;

import be.enrosed.finance.banking.BankStatementService;
import be.enrosed.finance.banking.PurchasePaymentSplitService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.PurchasePaymentOriginEntity;
import be.enrosed.sourcing.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchasePaymentOriginTest {
    @Inject PurchasePaymentOriginService origins;
    @Inject PurchaseOrderService orders;
    @Inject SupplierService suppliers;
    @Inject BankStatementService bank;
    @Inject PurchasePaymentSplitService splits;
    @Inject ObjectMapper json;
    @Inject EntityManager em;

    @Test @TestTransaction
    void sourceIsAuditedWithoutChangingMoneyAndSurvivesAnOrdinaryEdit() {
        var payment = payment();
        var before = json.valueToTree(payment);
        long bankCount = count("select count(b) from BankStatementLineEntity b");
        long activities = count("select count(a) from ActivityLogEntity a");
        var source = new PurchasePaymentOrigin(PurchasePaymentOrigin.Source.OTHER_COMPANY, " Entego ", " oude bankrekening ");
        var saved = origins.update(payment.id(), source);
        assertEquals("Betaald door Entego · oude bankrekening", saved.label());
        assertEquals(saved, origins.update(payment.id(), source));
        assertEquals(activities + 1, count("select count(a) from ActivityLogEntity a"));
        var read = orders.payments(payment.orderId()).getFirst();
        var after = json.valueToTree(read);
        ((com.fasterxml.jackson.databind.node.ObjectNode) after).set("origin", before.get("origin"));
        assertEquals(before, after, "all recorded financial and identity fields stay unchanged");
        assertEquals(saved, orders.paymentsSince(null).stream().filter(p -> p.id().equals(payment.id())).findFirst().orElseThrow().origin());
        var corrected = orders.updatePaymentByHand(payment.orderId(), payment.id(), payment.paidOn(), payment.amount(), payment.currency(),
                "Aanbetaling leverancier", payment.payee(), payment.settles(), payment.instalmentDue(), true, null, null, false, false);
        assertEquals(saved, corrected.origin());
        assertEquals(bankCount, count("select count(b) from BankStatementLineEntity b"));
        orders.deletePayment(payment.orderId(), payment.id());
        em.clear();
        assertNull(em.find(PurchasePaymentOriginEntity.class, payment.id()));
    }

    @Test @TestTransaction
    void anotherCompanyCannotBeMatchedUntilExplicitlyReturnedToOwnBank() {
        var payment = payment();
        var source = new PurchasePaymentOrigin(PurchasePaymentOrigin.Source.OTHER_COMPANY, "Entego", null);
        origins.update(payment.id(), source);
        long line = line(payment.amountEur().negate());
        assertThrows(BusinessRuleException.class, () -> bank.linkExisting(line,
                new BankStatementService.OutgoingLinks(List.of(payment.id()), null, null)));
        assertThrows(BusinessRuleException.class, () -> splits.linkPart(payment.id(), new PurchasePaymentSplitService.PartRequest(line, null)));
        assertThrows(BusinessRuleException.class, () -> splits.linkLines(payment.id(),
                new PurchasePaymentSplitService.LinesRequest(List.of(new PurchasePaymentSplitService.LineRef(line, null)))));
        assertNull(orders.payments(payment.orderId()).getFirst().bankStatementLineId());
        origins.update(payment.id(), null);
        bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(payment.id()), null, null));
        assertEquals(line, orders.payments(payment.orderId()).getFirst().bankStatementLineId());
        assertThrows(BusinessRuleException.class, () -> origins.update(payment.id(), source));
        bank.unlinkExisting(line);
        assertEquals(source, origins.update(payment.id(), source));
    }

    @Test @TestTransaction
    void externalSourceAlsoRefusesAManualMovementAndValidatesItsIdentity() {
        var payment = payment();
        assertThrows(BusinessRuleException.class, () -> origins.update(payment.id(),
                new PurchasePaymentOrigin(PurchasePaymentOrigin.Source.OTHER_COMPANY, " ", null)));
        assertThrows(BusinessRuleException.class, () -> origins.update(payment.id(),
                new PurchasePaymentOrigin(null, null, null)));
        var old = origins.update(payment.id(), new PurchasePaymentOrigin(PurchasePaymentOrigin.Source.OLD_BANK_ACCOUNT, "ignored", "Rekening afgesloten"));
        assertNull(old.payerName());
        assertThrows(BusinessRuleException.class, () -> bank.create(new BankStatementService.ManualRequest(
                "Origin test " + UUID.randomUUID(), payment.amountEur(), BankStatementService.Direction.OUTGOING,
                Instant.parse("2026-01-23T12:00:00Z"), "Europe/Brussels", "Origin test", "Supplier", UUID.randomUUID().toString(),
                List.of(payment.id()), null, null)));
        // The refused create is transactional; no payment is linked and provenance still reads.
        assertNull(orders.payments(payment.orderId()).getFirst().bankStatementLineId());
        assertEquals(old, orders.payments(payment.orderId()).getFirst().origin());
    }

    @Test @TestTransaction
    void inspectionCannotBeSplitOffAnExternalSourceThroughEitherEntryPoint() {
        for (var source : PurchasePaymentOrigin.Source.values()) {
            var payment = payment();
            origins.update(payment.id(), new PurchasePaymentOrigin(source, "Entego", "oude bankrekening"));
            var before = orders.payments(payment.orderId());
            long activities = count("select count(a) from ActivityLogEntity a");
            var amount = new BigDecimal("100.00");
            assertTrue(assertThrows(BusinessRuleException.class, () -> splits.splitInspection(payment.id(),
                    new PurchasePaymentSplitService.InspectionRequest(amount, amount, "Inspectie", payment.amountEur())))
                    .getMessage().contains("betaalbron"));
            assertTrue(assertThrows(BusinessRuleException.class, () -> orders.splitInspection(payment.orderId(),
                    payment.id(), amount, amount, "Inspectie")).getMessage().contains("betaalbron"));
            assertEquals(before, orders.payments(payment.orderId()), "no amount or origin changes and no inspection payment");
            assertEquals(activities, count("select count(a) from ActivityLogEntity a"));
        }
    }

    @Test
    void concurrentBankMatchWaitsForTheSourceChangeAndThenRefusesIt() throws Exception {
        var payment = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(this::payment);
        long line = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> line(payment.amountEur().negate()));
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var bankStarted = new java.util.concurrent.CountDownLatch(1);
        var source = new PurchasePaymentOrigin(PurchasePaymentOrigin.Source.OTHER_COMPANY, "Entego", "oude bankrekening");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var sourceWrite = executor.submit(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                orders.getForUpdate(payment.orderId());
                locked.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                origins.update(payment.id(), source);
            }));
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var bankWrite = executor.submit(() -> {
                bankStarted.countDown();
                return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                        bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(payment.id()), null, null)));
            });
            assertTrue(bankStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            sourceWrite.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> bankWrite.get(10, java.util.concurrent.TimeUnit.SECONDS));
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(BusinessRuleException.class, cause);
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                var stored = orders.payments(payment.orderId()).getFirst();
                assertNull(stored.bankStatementLineId());
                assertEquals(source, stored.origin());
                assertEquals(payment.amountEur(), stored.amountEur());
                assertEquals(source, LotCostCalculator.normalised(orders.get(payment.orderId()), List.of(stored)).getFirst().origin());
            });
        } finally {
            release.countDown();
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                orders.deletePayment(payment.orderId(), payment.id());
                em.remove(em.find(be.enrosed.finance.banking.BankStatementLineEntity.class, line));
            });
        }
    }

    private long count(String query) { return em.createQuery(query, Long.class).getSingleResult(); }
    private PurchasePayment payment() {
        var supplier = suppliers.save(new Supplier(null, "Payment origin supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        return orders.addPayment(order.id(), LocalDate.of(2026, 1, 23), new BigDecimal("9375.69"), Currency.EUR,
                "30% bij bestelling", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
    }
    private long line(BigDecimal amount) {
        var at = Instant.parse("2026-01-23T12:00:00Z");
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", amount, at, "Europe/Brussels", "Goederen", "Supplier", at, false)).id();
    }
}
