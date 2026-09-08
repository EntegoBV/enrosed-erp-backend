package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.adapter.out.persistence.SalesPaymentEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class IncomingPaymentPersistenceTest {
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject EntityManager em;
    @Inject IncomingPaymentBackfill backfillRunner;

    @Test @TestTransaction
    void instalmentsAndCorrectionsUseRealCashTimesAndPreserveRecordingAudit() {
        SalesOrder invoice = invoice("100", SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION, QuoteStatus.UITGEREIKT, null);
        Instant firstAt = Instant.parse("2026-08-12T22:30:00Z");
        incoming.add(invoice.id(), request("40.334", firstAt));
        var first = incoming.forOrder(invoice.id()).getFirst();
        assertEquals(new BigDecimal("40.33"), first.amountEur());
        var partial = summary(invoice.id());
        assertEquals(new BigDecimal("121.00"), partial.invoiceTotalEur());
        assertEquals(new BigDecimal("40.33"), partial.instalments().getFirst().expectedEur());
        assertEquals(new BigDecimal("80.67"), partial.instalments().get(1).expectedEur());
        assertEquals(SalesPaymentSummary.Status.PARTIAL, partial.status());
        assertNull(sales.get(invoice.id()).paidAt());
        assertTrue(incoming.list(LocalDate.of(2026, 8, 13)).stream().anyMatch(row -> row.id().equals(first.id())), "filter uses receipt zone day");
        Instant secondAt = Instant.parse("2026-08-16T09:17:00Z");
        incoming.add(invoice.id(), request("80.67", secondAt));
        assertEquals(QuoteStatus.BETAALD, sales.get(invoice.id()).status());
        assertEquals(secondAt, sales.get(invoice.id()).paidAt());
        incoming.markPaid(invoice.id());
        assertEquals(2, incoming.forOrder(invoice.id()).size(), "mark paid is idempotent");
        incoming.update(invoice.id(), first.id(), new IncomingPaymentService.Request(new BigDecimal("30.33"), firstAt.minusSeconds(60), "Europe/Amsterdam", "Corrected bank receipt"));
        var corrected = incoming.forOrder(invoice.id()).getFirst();
        assertEquals(first.recordedAt(), corrected.recordedAt());
        assertEquals(first.actor(), corrected.actor());
        assertEquals(new BigDecimal("10.00"), summary(invoice.id()).remainingEur());
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(invoice.id()).status(), "editing a receipt must not invent customer mail");
        incoming.delete(invoice.id(), first.id());
        assertNotNull(em.find(SalesPaymentEntity.class, first.id()).voidedAt);
        assertEquals(new BigDecimal("40.33"), summary(invoice.id()).remainingEur());
        assertTrue(events.findByOrder(invoice.id()).stream().anyMatch(event -> event.summary().contains("ingetrokken")));
        sales.archive(invoice.id());
        assertTrue(incoming.list(null).stream().anyMatch(row -> row.salesOrderId() == invoice.id()));
    }

    @Test @TestTransaction
    void legacyPaidMarkerIsMigratedOnceAndVoidingNeverResurrectsIt() {
        Instant historical = Instant.parse("2025-06-03T10:42:00Z");
        var invoice = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.BETAALD, historical);
        incoming.backfill(invoice);
        incoming.backfill(invoice);
        var receipt = incoming.forOrder(invoice.id()).getFirst();
        assertEquals(1, incoming.forOrder(invoice.id()).size());
        assertTrue(receipt.legacy());
        assertEquals(historical, receipt.receivedAt());
        assertEquals(new BigDecimal("121.00"), receipt.amountEur());
        incoming.add(invoice.id(), request("5", Instant.parse("2026-08-01T10:00:00Z")));
        assertEquals(new BigDecimal("5.00"), summary(invoice.id()).overpaidEur(), "new receipts add to historical cash");
        incoming.delete(invoice.id(), receipt.id());
        incoming.backfill(invoice);
        assertEquals(1, incoming.forOrder(invoice.id()).size());
        assertEquals(new BigDecimal("116.00"), summary(invoice.id()).remainingEur());
    }

    @Test @TestTransaction
    void fullyPaidMomentFollowsChronologicalReceiptsAndIgnoresLaterOverpayments() {
        var invoice = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        Instant firstAt = Instant.parse("2026-08-11T10:00:00Z");
        Instant completedAt = Instant.parse("2026-08-12T10:00:00Z");
        incoming.add(invoice.id(), request("50", firstAt));
        incoming.add(invoice.id(), request("71", completedAt));
        assertEquals(completedAt, sales.get(invoice.id()).paidAt());
        incoming.add(invoice.id(), request("10", completedAt.plusSeconds(86400)));
        assertEquals(completedAt, sales.get(invoice.id()).paidAt(), "overpayment does not change full-payment date");

        Instant earlierAt = firstAt.minusSeconds(86400);
        incoming.add(invoice.id(), request("80", earlierAt));
        assertEquals(firstAt, sales.get(invoice.id()).paidAt(), "a backdated receipt changes when the cumulative amount reached the invoice total");
        var earlier = incoming.forOrder(invoice.id()).getFirst();
        incoming.delete(invoice.id(), earlier.id());
        assertEquals(completedAt, sales.get(invoice.id()).paidAt(), "voiding a receipt recomputes the full-payment date");
    }

    @Test @TestTransaction
    void voidedReceiptHistoryStillProtectsItsInvoiceFromDeletion() {
        var invoice = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        incoming.add(invoice.id(), request("10", Instant.parse("2026-08-12T10:00:00Z")));
        incoming.delete(invoice.id(), incoming.forOrder(invoice.id()).getFirst().id());
        assertTrue(incoming.forOrder(invoice.id()).isEmpty());
        assertTrue(incoming.hasHistory(invoice.id()));
        // Older data can contain a document returned to draft after a payment correction.
        em.find(SalesOrderEntity.class, invoice.id()).status = QuoteStatus.CONCEPT;
        em.flush(); em.clear();
        assertThrows(BusinessRuleException.class, () -> sales.delete(invoice.id()));
        assertNotNull(sales.get(invoice.id()));
    }

    @Test
    void repeatedStartupBackfillUsesIndependentTransactionsAndNeverRecreatesVoidedCash() {
        Instant historical = Instant.parse("2025-06-03T10:42:00Z");
        var invoice = QuarkusTransaction.requiringNew().call(() -> invoice("100", SalesPaymentPlan.FULL, QuoteStatus.BETAALD, historical));
        try {
            backfillRunner.onStart(null);
            backfillRunner.onStart(null);
            var receipts = incoming.forOrder(invoice.id());
            assertEquals(1, receipts.size());
            assertTrue(receipts.getFirst().legacy());
            assertEquals(historical, receipts.getFirst().receivedAt());
            incoming.delete(invoice.id(), receipts.getFirst().id());
            backfillRunner.onStart(null);
            assertTrue(incoming.forOrder(invoice.id()).isEmpty());
            assertTrue(incoming.hasHistory(invoice.id()));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from SalesPaymentEntity p where p.salesOrderId=:id").setParameter("id", invoice.id()).executeUpdate();
                events.deleteByOrder(invoice.id());
                em.remove(em.find(SalesOrderEntity.class, invoice.id()));
                em.remove(em.find(CustomerEntity.class, invoice.customerId()));
            });
        }
    }

    @Test @TestTransaction
    void invalidReceiptsAndCrossInvoiceEditsAreRejectedAndCreditsStayCredits() {
        var a = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        var b = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        Instant at = Instant.parse("2026-08-12T12:00:00Z");
        incoming.add(a.id(), request("1", at));
        long paymentId = incoming.forOrder(a.id()).getFirst().id();
        assertThrows(be.enrosed.shared.NotFoundException.class, () -> incoming.update(b.id(), paymentId, request("4", at)));
        assertThrows(BusinessRuleException.class, () -> incoming.add(a.id(), request("0.004", at)));
        assertThrows(BusinessRuleException.class, () -> incoming.add(a.id(), request("-1", at)));
        assertThrows(BusinessRuleException.class, () -> incoming.add(a.id(), request("1", Instant.now().plusSeconds(3600))));
        assertThrows(BusinessRuleException.class, () -> incoming.add(a.id(), new IncomingPaymentService.Request(BigDecimal.ONE, at, "invalid/zone", null)));
        var credit = invoice("-100", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        assertEquals(SalesPaymentSummary.Status.CREDIT, summary(credit.id()).status());
        assertEquals(new BigDecimal("121.00"), summary(credit.id()).creditEur());
        assertThrows(BusinessRuleException.class, () -> incoming.markPaid(credit.id()));
        var zero = invoice("0", SalesPaymentPlan.FULL, QuoteStatus.UITGEREIKT, null);
        assertEquals(SalesPaymentSummary.Status.PAID, summary(zero.id()).status());
        var cancelled = invoice("100", SalesPaymentPlan.FULL, QuoteStatus.GEANNULEERD, null);
        assertThrows(BusinessRuleException.class, () -> incoming.add(cancelled.id(), request("1", at)));
    }

    private SalesPaymentSummary summary(long id) { var order = sales.get(id); return incoming.summary(order, sales.price(order)); }
    private IncomingPaymentService.Request request(String amount, Instant at) { return new IncomingPaymentService.Request(new BigDecimal(amount), at, "Europe/Brussels", "Bank"); }
    private SalesOrder invoice(String amount, SalesPaymentPlan plan, QuoteStatus status, Instant paidAt) {
        var customer = customers.create(new Customer(null, "Cash test", "Fin", null, null, "BE0000000000", "BE", Language.NL,
                "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var created = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        SalesOrderEntity stored = em.find(SalesOrderEntity.class, created.id());
        stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":" + amount + "}]";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        stored.paymentPlan = plan; stored.status = status; stored.paidAt = paidAt;
        em.flush(); em.clear();
        return sales.get(created.id());
    }
}
