package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.Supplier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec example (b): € 10.300,00 to the supplier on 03/09, of which € 300,00 is the inspection. */
@QuarkusTest
class PurchaseInspectionSplitPersistenceTest {
    @Inject PurchasePaymentSplitService splits;
    @Inject BankPurchasePaymentService transfers;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    private static final Instant SEP_3 = Instant.parse("2026-09-03T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 3);

    @Test
    @TestTransaction
    void aLinkedGoodsPaymentGivesUpItsInspectionOnTheSameLine() {
        PurchaseOrder order = container();
        long line = line("-10300.00");
        PurchasePayment goods = transfers.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(
                new BankPurchasePaymentService.Payment(new BigDecimal("10300"), Currency.EUR, "Aanbetaling 30%",
                        PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, null)))).payments().getFirst();

        var result = splits.splitInspection(goods.id(), inspection("300.00", "10300.00"));

        assertEquals(2, result.payments().size());
        PurchasePayment lowered = result.payments().get(0);
        PurchasePayment inspection = result.payments().get(1);
        assertEquals(goods.id(), lowered.id());
        assertEquals(new BigDecimal("10000.00"), lowered.amountEur());
        assertEquals(new BigDecimal("10000.00"), lowered.amount());
        assertEquals(line, lowered.bankStatementLineId());
        assertEquals(PaymentTerms.Moment.ORDERED, lowered.instalmentDue());
        assertEquals(PurchasePayment.Payee.SEPARATE, inspection.payee());
        assertEquals(new BigDecimal("300.00"), inspection.amountEur());
        assertEquals("Aanbetaling 30% · inspectie", inspection.label());
        assertEquals(line, inspection.bankStatementLineId());
        assertEquals(DAY, inspection.paidOn());
        assertNull(inspection.instalmentDue());
        assertFalse(inspection.settles());
        var described = result.lines().getFirst();
        assertEquals(new BigDecimal("-10300.00"), described.allocatedAmountEur, "the line total is unchanged");
        assertEquals(0, described.allocationDifferenceEur.signum());
        assertTrue(purchases.get(order.id()).inspectionPaidViaSupplier());
        String notes = purchases.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 03/09/2026: € 10.000,00 aan de leverancier · Aanbetaling 30% · termijn bij bestelling."), notes);
        assertTrue(notes.contains("Betaald 03/09/2026: € 300,00 aan inspectie & andere kosten · Aanbetaling 30% · inspectie."), notes);
        assertFalse(notes.contains("€ 10.300,00"), notes);
    }

    @Test
    @TestTransaction
    void anUnlinkedGoodsPaymentSplitsTooAndAForeignOneProRata() {
        PurchaseOrder order = container();
        PurchasePayment goods = purchases.addPayment(order.id(), DAY, new BigDecimal("10300"), Currency.EUR,
                null, PurchasePayment.Payee.SUPPLIER);
        var result = splits.splitInspection(goods.id(), inspection("300", "10300"));
        assertNull(result.payments().get(1).bankStatementLineId());
        assertEquals("Betaling · inspectie", result.payments().get(1).label());
        assertTrue(result.lines().isEmpty());

        PurchasePayment usd = purchases.addPayment(order.id(), DAY, new BigDecimal("12000"), Currency.USD,
                "Goederen", PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("10300.00"));
        var foreign = splits.splitInspection(usd.id(), new PurchasePaymentSplitService.InspectionRequest(
                new BigDecimal("300.00"), null, "Inspectie QIMA", new BigDecimal("10300.00")));
        assertEquals(new BigDecimal("349.51"), foreign.payments().get(1).amount(), "12000 x 300 / 10300");
        assertEquals(new BigDecimal("11650.49"), foreign.payments().get(0).amount());
        assertEquals("Inspectie QIMA", foreign.payments().get(1).label());
    }

    @Test
    @TestTransaction
    void theSameBodySentTwiceSplitsOnce() {
        PurchaseOrder order = container();
        long line = line("-10300.00");
        PurchasePayment goods = transfers.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(
                new BankPurchasePaymentService.Payment(new BigDecimal("10300"), Currency.EUR, "Aanbetaling 30%",
                        PurchasePayment.Payee.SUPPLIER, false, null, null)))).payments().getFirst();
        var first = splits.splitInspection(goods.id(), inspection("300.00", "10300.00"));
        String notes = purchases.get(order.id()).notes();

        var again = splits.splitInspection(goods.id(), inspection("300.00", "10300.00"));

        assertEquals(first.payments(), again.payments());
        assertEquals(2, purchases.payments(order.id()).size());
        assertEquals(notes, purchases.get(order.id()).notes());
        assertRefused("De betaling is intussen gewijzigd; vernieuw en probeer opnieuw",
                () -> splits.splitInspection(goods.id(), inspection("200.00", "10300.00")));
    }

    @Test
    @TestTransaction
    void onlyAGoodsPaymentToTheSupplierGivesUpAnInspection() {
        PurchaseOrder order = container();
        PurchasePayment separate = purchases.addPayment(order.id(), DAY, new BigDecimal("300"), Currency.EUR,
                "Inspectie", PurchasePayment.Payee.SEPARATE);
        PurchasePayment goods = purchases.addPayment(order.id(), DAY, new BigDecimal("10300"), Currency.EUR,
                "Goederen", PurchasePayment.Payee.SUPPLIER);

        assertRefused("Splits de inspectie af van een goederenbetaling aan de leverancier",
                () -> splits.splitInspection(separate.id(), inspection("100", "300")));
        assertRefused("Geef een inspectiebedrag groter dan nul op",
                () -> splits.splitInspection(goods.id(), inspection("0", "10300")));
        assertRefused("Het inspectiedeel moet kleiner zijn dan de betaling (€ 10.300,00)",
                () -> splits.splitInspection(goods.id(), inspection("10300", "10300")));
        assertRefused("Vernieuw de betaling en probeer opnieuw",
                () -> splits.splitInspection(goods.id(), new PurchasePaymentSplitService.InspectionRequest(
                        new BigDecimal("300"), null, null, null)));
        assertRefused("De betaling is intussen gewijzigd; vernieuw en probeer opnieuw",
                () -> splits.splitInspection(goods.id(), inspection("300", "10000")));

        PurchasePayment offset = purchases.addPayment(order.id(), DAY, new BigDecimal("1000"), Currency.EUR,
                "Verrekend tegoed", PurchasePayment.Payee.SUPPLIER);
        var credit = new SourcingEntities.PurchaseSupplierCreditEntity();
        credit.orderId = order.id();
        credit.notedOn = DAY;
        credit.amount = new BigDecimal("1000.00");
        credit.currency = Currency.EUR;
        credit.amountEur = new BigDecimal("1000.00");
        credit.reason = PurchaseSupplierCredit.Reason.PRICE;
        credit.status = PurchaseSupplierCredit.Status.OFFSET;
        credit.offsetOrderId = order.id();
        credit.offsetPaymentId = offset.id();
        credit.recordedAt = Instant.now();
        em.persist(credit);
        em.flush();
        assertRefused("Splits de inspectie af van een goederenbetaling aan de leverancier",
                () -> splits.splitInspection(offset.id(), inspection("100", "1000")));
        assertEquals(3, purchases.payments(order.id()).size(), "nothing was split");
        assertFalse(purchases.get(order.id()).inspectionPaidViaSupplier());
    }

    @Test
    @TestTransaction
    void theCifFreightTermIsNotAGoodsPayment() {
        PurchaseOrder order = container();
        var entity = em.find(SourcingEntities.PurchaseOrderEntity.class, order.id());
        entity.freightViaSupplier = true;
        em.flush();
        PurchasePayment freight = purchases.addPayment(order.id(), DAY, new BigDecimal("2000"), Currency.EUR,
                "Zeevracht", PurchasePayment.Payee.SUPPLIER);
        /* Tie it to the freight term directly: the term itself needs a costed CIF container. */
        em.find(SourcingEntities.PurchasePaymentEntity.class, freight.id()).instalmentDue = PaymentTerms.Moment.FREIGHT;
        em.flush();
        assertRefused("Splits de inspectie af van een goederenbetaling aan de leverancier",
                () -> splits.splitInspection(freight.id(), inspection("100", "2000")));
    }

    private static PurchasePaymentSplitService.InspectionRequest inspection(String amountEur, String expected) {
        return new PurchasePaymentSplitService.InspectionRequest(new BigDecimal(amountEur), null, null, new BigDecimal(expected));
    }

    private static void assertRefused(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Inspection supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO,
                be.enrosed.sourcing.domain.ContainerType.FORTY_HQ, false);
    }

    private long line(String amount) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), SEP_3, "Europe/Brussels", "Goederen", "Supplier", SEP_3, false)).id();
    }
}
