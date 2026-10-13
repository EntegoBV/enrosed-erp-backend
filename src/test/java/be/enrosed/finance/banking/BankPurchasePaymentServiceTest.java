package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankPurchasePaymentServiceTest {
    @Inject BankPurchasePaymentService service;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    private static final Instant AT = Instant.parse("2026-09-08T23:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Test @TestTransaction
    void createsEveryPartInRequestOrderWithCanonicalBankDateAndEuroAmount() {
        var order = container();
        var line = imported("-281.46");
        var result = service.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(
                part("300", Currency.USD, "Goederen", PurchasePayment.Payee.SUPPLIER, "260.46"),
                part("11", Currency.EUR, "Vracht", PurchasePayment.Payee.LOGISTICS, null),
                part("10", Currency.EUR, "Inspectie", PurchasePayment.Payee.SEPARATE, null))));

        assertEquals(line, result.line().id);
        assertTrue(result.line().allocated);
        assertEquals("Inkoopbetaling", result.line().allocationLabel);
        assertEquals(List.of("Goederen", "Vracht", "Inspectie"), result.payments().stream().map(PurchasePayment::label).toList());
        assertEquals(new BigDecimal("260.46"), result.payments().getFirst().amountEur());
        assertEquals(new BigDecimal("281.46"), result.payments().stream().map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(result.payments().stream().allMatch(payment -> line.equals(payment.bankStatementLineId()) && DAY.equals(payment.paidOn())));
        assertEquals(3, purchases.payments(order.id()).size());
        assertEquals(1, bank.list().size(), "selecting a bank movement never creates another movement");
    }

    @Test @TestTransaction
    void retryAfterLostResponseReturnsSamePaymentIdsWithoutChangingNotesOrLabels() {
        var order = container();
        var line = imported("-100");
        var request = new BankPurchasePaymentService.Request(order.id(), List.of(eur("70"), eur("30")));
        var first = service.record(line, request);
        var notes = purchases.get(order.id()).notes();
        var retry = service.record(line, request);

        assertEquals(first.payments(), retry.payments());
        assertEquals(2, purchases.payments(order.id()).size());
        assertEquals(notes, purchases.get(order.id()).notes());
        assertTrue(retry.line().allocated);
    }

    @Test @TestTransaction
    void aDifferentDistributionCannotSilentlyCreateAnotherPayment() {
        var order = container();
        var line = imported("-100");
        service.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(eur("70"), eur("30"))));
        assertThrows(BusinessRuleException.class, () -> service.record(line,
                new BankPurchasePaymentService.Request(order.id(), List.of(eur("60"), eur("40")))));
        assertEquals(List.of(new BigDecimal("70.00"), new BigDecimal("30.00")),
                purchases.payments(order.id()).stream().map(PurchasePayment::amountEur).toList());
    }

    @Test @TestTransaction
    void aDifferentOrderCannotReuseAnAllocatedTransfer() {
        var firstOrder = container();
        var secondOrder = container();
        var line = imported("-100");
        var saved = service.record(line, new BankPurchasePaymentService.Request(firstOrder.id(), List.of(eur("100"))));
        assertThrows(BusinessRuleException.class, () -> service.record(line,
                new BankPurchasePaymentService.Request(secondOrder.id(), List.of(eur("100")))));
        assertTrue(purchases.payments(secondOrder.id()).isEmpty());
        assertEquals(saved.payments(), purchases.payments(firstOrder.id()));
    }

    @Test @TestTransaction
    void rejectsWrongTotalBeforeWritingAnyPayment() {
        var order = container();
        var line = imported("-100");
        assertThrows(BusinessRuleException.class, () -> service.record(line,
                new BankPurchasePaymentService.Request(order.id(), List.of(eur("60"), eur("39.97")))));
        assertTrue(purchases.payments(order.id()).isEmpty());
        assertFalse(bank.list().getFirst().allocated);
    }

    @Test @TestTransaction
    void rejectsIncomingAndSalesAllocatedMovements() {
        var order = container();
        var incoming = imported("100");
        var sales = imported("-100");
        em.find(BankStatementLineEntity.class, sales).salesPaymentId = 123456789L;
        var request = new BankPurchasePaymentService.Request(order.id(), List.of(eur("100")));
        assertThrows(BusinessRuleException.class, () -> service.record(incoming, request));
        assertThrows(BusinessRuleException.class, () -> service.record(sales, request));
        assertTrue(purchases.payments(order.id()).isEmpty());
    }

    @Test @TestTransaction
    void aggregateTwoCentDifferencePreservesEveryPartAndRetryReturnsSameIds() {
        var order=container();
        var line=imported("-100.00");
        var request=new BankPurchasePaymentService.Request(order.id(),List.of(eur("60"),eur("39.98")));
        var saved=service.record(line,request);
        assertEquals(new BigDecimal("-100.00"),saved.line().amountEur);
        assertEquals(new BigDecimal("-99.98"),saved.line().allocatedAmountEur);
        assertEquals(new BigDecimal("-0.02"),saved.line().allocationDifferenceEur);
        assertEquals(List.of(new BigDecimal("60.00"),new BigDecimal("39.98")),saved.payments().stream().map(PurchasePayment::amountEur).toList());
        var retry=service.record(line,request);
        assertEquals(saved.payments(),retry.payments());
        assertEquals(new BigDecimal("-0.02"),retry.line().allocationDifferenceEur);
        assertEquals(2,purchases.payments(order.id()).size());
    }

    @Test @TestTransaction
    void rejectsFourCentAggregateEvenWhenEachPartWouldBeTwoCentsShort() {
        var order=container();
        var line=imported("-100.00");
        assertThrows(BusinessRuleException.class,()->service.record(line,
                new BankPurchasePaymentService.Request(order.id(),List.of(eur("59.98"),eur("39.98")))));
        assertTrue(purchases.payments(order.id()).isEmpty());
        var untouched=bank.list().getFirst();
        assertFalse(untouched.allocated);
        assertNull(untouched.allocatedAmountEur);
        assertNull(untouched.allocationDifferenceEur);
    }

    @Test @TestTransaction
    void rejectsATransferAlreadyAllocatedToACost() {
        var order = container();
        var line = imported("-100");
        var payment = new CostPaymentEntity();
        payment.companyCostId = 123456789L;
        payment.amountEur = new BigDecimal("100.00");
        payment.paidOn = DAY;
        payment.bankStatementLineId = line;
        payment.recordedAt = AT;
        em.persist(payment);
        em.flush();
        assertThrows(BusinessRuleException.class, () -> service.record(line,
                new BankPurchasePaymentService.Request(order.id(), List.of(eur("100")))));
        assertTrue(purchases.payments(order.id()).isEmpty());
        assertEquals(line, payment.bankStatementLineId);
    }

    @Test @TestTransaction
    void rejectsMissingForeignEuroValueAndFractionalCentsBeforeWriting() {
        var order = container();
        var line = imported("-100");
        for (var invalid : List.of(
                part("100", Currency.USD, null, null, null),
                part("100.001", Currency.EUR, null, null, null),
                part("100", Currency.USD, null, null, "100.001"),
                part("100", Currency.EUR, null, null, "99.99"),
                part("0", Currency.EUR, null, null, null))) {
            assertThrows(BusinessRuleException.class, () -> service.record(line,
                    new BankPurchasePaymentService.Request(order.id(), List.of(invalid))));
        }
        assertTrue(purchases.payments(order.id()).isEmpty());
    }

    @Test @TestTransaction
    void rejectsChangedCurrencyPayeeSettlementAndMilestoneOnRetry() {
        var order = container();
        var line = imported("-100");
        var original = service.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(eur("100"))));
        for (var invalid : List.of(
                part("100", Currency.USD, null, PurchasePayment.Payee.SUPPLIER, "100"),
                part("100", Currency.EUR, null, PurchasePayment.Payee.LOGISTICS, "100"),
                new BankPurchasePaymentService.Payment(new BigDecimal("100"), Currency.EUR, null, null, true, null, null),
                new BankPurchasePaymentService.Payment(new BigDecimal("100"), Currency.EUR, null, null, false, PaymentTerms.Moment.ORDERED, null))) {
            assertThrows(BusinessRuleException.class, () -> service.record(line,
                    new BankPurchasePaymentService.Request(order.id(), List.of(invalid))));
        }
        assertEquals(original.payments(), purchases.payments(order.id()));
    }

    @Test @TestTransaction
    void acceptsOneToTenPartsOnly() {
        var order = container();
        var line = imported("-10");
        assertThrows(BusinessRuleException.class, () -> service.record(line, new BankPurchasePaymentService.Request(order.id(), List.of())));
        assertThrows(BusinessRuleException.class, () -> service.record(line, new BankPurchasePaymentService.Request(order.id(), Collections.nCopies(11, eur("1")))));
        assertEquals(10, service.record(line, new BankPurchasePaymentService.Request(order.id(), Collections.nCopies(10, eur("1")))).payments().size());
    }

    @Test
    void invalidLaterPartRollsBackEarlierPaymentAndOrderNotes() {
        // Committed fixtures let the service own the transaction, exactly as it does over HTTP.
        var fixture = QuarkusTransaction.requiringNew().call(() -> {
            var order = container();
            return new long[]{order.id(), order.supplierId(), imported("-100")};
        });
        try {
            String notes = purchases.get(fixture[0]).notes();
            var invalidMilestone = new BankPurchasePaymentService.Payment(new BigDecimal("30"), Currency.EUR,
                    "Invalid freight", PurchasePayment.Payee.LOGISTICS, false, PaymentTerms.Moment.ORDERED, null);
            assertThrows(BusinessRuleException.class, () -> service.record(fixture[2],
                    new BankPurchasePaymentService.Request(fixture[0], List.of(eur("70"), invalidMilestone))));
            assertTrue(purchases.payments(fixture[0]).isEmpty(), "a later validation failure rolls back every earlier part");
            assertEquals(notes, purchases.get(fixture[0]).notes());
            assertFalse(bank.list().stream().filter(line -> line.id == fixture[2]).findFirst().orElseThrow().allocated);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId=:id").setParameter("id", fixture[0]).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, fixture[2]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[0]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, fixture[1]));
            });
        }
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Atomic transfer supplier", "CN", "Yiwu", null,
                null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private Long imported(String amount) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), AT, "Europe/Brussels", "Inkoopbetaling", "Supplier", AT, false)).id();
    }

    private static BankPurchasePaymentService.Payment eur(String amount) {
        return part(amount, Currency.EUR, "Inkoopbetaling", PurchasePayment.Payee.SUPPLIER, null);
    }

    private static BankPurchasePaymentService.Payment part(String amount, Currency currency, String label,
                                                          PurchasePayment.Payee payee, String eur) {
        return new BankPurchasePaymentService.Payment(new BigDecimal(amount), currency, label, payee, false,
                null, eur == null ? null : new BigDecimal(eur));
    }
}
