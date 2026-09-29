package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.CreditChange;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.CreditRequest;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.OffsetRequest;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Status;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchaseSupplierCreditPersistenceTest {
    @Inject PurchaseOrderService orders;
    @Inject PurchaseSupplierCreditService credits;
    @Inject SupplierService suppliers;
    @Inject EntityManager entityManager;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Test
    @TestTransaction
    void aNotedCreditLowersTheExpectedCostWithinTheAgreementAndCanBeChangedOrRemovedWhileOpen() {
        var supplier = supplier("Credit Co");
        long product = product(supplier, "CREDIT-A");
        PurchaseOrder order = placed(supplier, product);
        /* 100 pieces at US$ 10 at 0,90: the supplier Afspraak is € 900, paid in full. */
        orders.addPayment(order.id(), DAY, new BigDecimal("900"), Currency.EUR, "Alles");

        assertRule("Geef een bedrag groter dan nul op", () -> credits.add(order.id(),
                new CreditRequest(DAY, BigDecimal.ZERO, Currency.EUR, null, Reason.SHORTAGE, null)));
        assertRule("Kies de reden van het tegoed", () -> credits.add(order.id(),
                new CreditRequest(DAY, BigDecimal.TEN, Currency.EUR, null, null, null)));
        var dollars = credits.add(order.id(),
                new CreditRequest(DAY, new BigDecimal("100"), Currency.USD, null, Reason.SHORTAGE, " 10 dozen te weinig "));
        assertEquals(new BigDecimal("90.00"), dollars.amountEur(), "euro at the order's own rate, like a payment");
        assertEquals(Status.OPEN, dollars.status());
        assertEquals("10 dozen te weinig", dollars.note());
        var bank = credits.add(order.id(),
                new CreditRequest(DAY, new BigDecimal("100"), Currency.USD, new BigDecimal("88"), Reason.DAMAGE, null));
        assertEquals(new BigDecimal("88.00"), bank.amountEur(), "an explicit euro amount is the actual one");
        assertRule("Het tegoed kan niet hoger zijn dan de afspraak met de leverancier (€ 900,00)",
                () -> credits.add(order.id(), new CreditRequest(DAY, new BigDecimal("722.01"), Currency.EUR,
                        null, Reason.PRICE, null)));
        assertEquals(2, orders.supplierCredits(order.id()).size(), "a refused credit leaves nothing behind");

        var reconciliation = orders.reconciliation(order.id());
        var supplierStream = stream(reconciliation);
        assertEquals(new BigDecimal("900.00"), supplierStream.paidEur(), "the bank movements stay as they were");
        assertEquals(new BigDecimal("0.00"), supplierStream.remainingEur());
        assertEquals(new BigDecimal("178.00"), supplierStream.creditEur());
        assertEquals(new BigDecimal("722.00"), supplierStream.forecastEur());
        assertEquals(new BigDecimal("-178.00"), supplierStream.varianceEur());
        assertEquals(new BigDecimal("178.00"), reconciliation.totals().supplierCreditEur());
        assertEquals(new BigDecimal("178.00"), reconciliation.totals().supplierCreditOpenEur());
        assertEquals(new BigDecimal("722.00"), reconciliation.totals().forecastExternalEur());
        assertTrue(reconciliation.notes().contains(
                "Tegoed van de leverancier € 178,00 verlaagt de eindkost; € 178,00 is nog te ontvangen."));
        assertTrue(orders.get(order.id()).notes()
                .contains("Tegoed leverancier genoteerd 01/09/2026: US$ 100,00 (≈ € 90,00) · tekort."));

        var changed = credits.update(order.id(), dollars.id(),
                new CreditChange(new BigDecimal("120"), null, null, null, "", null, null));
        assertEquals(new BigDecimal("108.00"), changed.amountEur(), "a new amount is valued at the order rate again");
        assertNull(changed.note(), "an empty note clears it");
        String notes = orders.get(order.id()).notes();
        assertTrue(notes.contains("Tegoed leverancier genoteerd 01/09/2026: US$ 120,00 (≈ € 108,00) · tekort."), notes);
        assertFalse(notes.contains("US$ 100,00 (≈ € 90,00)"), "the diary line is rewritten, not doubled");
        assertRule("Het tegoed kan niet hoger zijn dan de afspraak met de leverancier (€ 900,00)",
                () -> credits.update(order.id(), bank.id(),
                        new CreditChange(null, null, new BigDecimal("792.01"), null, null, null, null)));

        credits.delete(order.id(), changed.id());
        assertEquals(List.of(bank.id()), orders.supplierCredits(order.id()).stream().map(PurchaseSupplierCredit::id).toList());
        assertFalse(orders.get(order.id()).notes().contains("· tekort."));
        assertEquals(new BigDecimal("812.00"), orders.reconciliation(order.id()).totals().forecastExternalEur());
    }

    @Test
    @TestTransaction
    void aRefundSettlesTheCreditUntilItIsUndoneAndSettledCreditsCannotBeEditedOrDeleted() {
        var supplier = supplier("Refund Co");
        long product = product(supplier, "CREDIT-R");
        PurchaseOrder order = placed(supplier, product);
        var credit = credits.add(order.id(),
                new CreditRequest(DAY, new BigDecimal("50"), Currency.USD, null, Reason.SHORTAGE, null));
        assertEquals(new BigDecimal("45.00"), credit.amountEur());

        assertRule("Geef de datum van de terugbetaling op", () -> credits.update(order.id(), credit.id(),
                new CreditChange(null, null, null, null, null, Status.REFUNDED, null)));
        var refunded = credits.update(order.id(), credit.id(),
                new CreditChange(null, null, new BigDecimal("44.10"), null, null, Status.REFUNDED, DAY.plusDays(9)));
        assertEquals(Status.REFUNDED, refunded.status());
        assertEquals(DAY.plusDays(9), refunded.settledOn());
        assertEquals(new BigDecimal("44.10"), refunded.amountEur(), "the bank's euro replaces the order-rate value");
        var totals = orders.reconciliation(order.id()).totals();
        assertEquals(new BigDecimal("44.10"), totals.supplierCreditEur());
        assertEquals(new BigDecimal("0.00"), totals.supplierCreditOpenEur(), "refunded: nothing left to receive");
        assertTrue(orders.get(order.id()).notes()
                .contains("Tegoed leverancier terugbetaald 10/09/2026: US$ 50,00 (≈ € 44,10)."));

        assertRule("Een verrekend of terugbetaald tegoed pas je niet meer aan; zet het eerst terug op open",
                () -> credits.update(order.id(), credit.id(),
                        new CreditChange(new BigDecimal("40"), null, null, null, null, null, null)));
        assertRule("Alleen een open tegoed kan verwijderd worden", () -> credits.delete(order.id(), credit.id()));
        assertRule(PurchaseSupplierCreditService.OFFSET_IS_UNDONE_ELSEWHERE, () -> credits.update(order.id(),
                credit.id(), new CreditChange(null, null, null, null, null, Status.OFFSET, DAY)));

        var reopened = credits.update(order.id(), credit.id(),
                new CreditChange(null, null, null, null, null, Status.OPEN, null));
        assertEquals(Status.OPEN, reopened.status());
        assertNull(reopened.settledOn());
        assertFalse(orders.get(order.id()).notes().contains("terugbetaald"));
        assertEquals(new BigDecimal("44.10"), orders.reconciliation(order.id()).totals().supplierCreditOpenEur());
        credits.delete(order.id(), credit.id());
        assertTrue(orders.supplierCredits(order.id()).isEmpty());
    }

    @Test
    @TestTransaction
    void aRefundKeepsTheBankEuroAboveTheAgreementWhileTheCreditItselfStaysCapped() {
        var supplier = supplier("Rate Co");
        long product = product(supplier, "CREDIT-FX");
        PurchaseOrder order = placed(supplier, product);
        /* The whole Afspraak comes back: US$ 1.000 at the order rate of 0,90 is exactly € 900. */
        var credit = credits.add(order.id(),
                new CreditRequest(DAY, new BigDecimal("1000"), Currency.USD, null, Reason.OTHER, null));
        assertEquals(new BigDecimal("900.00"), credit.amountEur(), "a credit equal to the Afspraak is allowed");

        String cap = "Het tegoed kan niet hoger zijn dan de afspraak met de leverancier (€ 900,00)";
        assertRule(cap, () -> credits.update(order.id(), credit.id(),
                new CreditChange(null, null, new BigDecimal("905"), null, null, null, null)));
        assertRule(cap, () -> credits.update(order.id(), credit.id(),
                new CreditChange(new BigDecimal("1010"), null, null, null, null, Status.REFUNDED, DAY.plusDays(20))));
        assertEquals(new BigDecimal("900.00"), orders.supplierCredits(order.id()).getFirst().amountEur());

        /* The dollar rose before the refund came in: the bank booked € 905. */
        var refunded = credits.update(order.id(), credit.id(),
                new CreditChange(null, null, new BigDecimal("905"), null, null, Status.REFUNDED, DAY.plusDays(20)));
        assertEquals(Status.REFUNDED, refunded.status());
        assertEquals(new BigDecimal("905.00"), refunded.amountEur(), "the refund is recorded as the bank booked it");
        assertEquals(new BigDecimal("905.00"), orders.reconciliation(order.id()).totals().supplierCreditEur());
        var corrected = credits.update(order.id(), credit.id(),
                new CreditChange(null, null, new BigDecimal("906"), null, null, null, null));
        assertEquals(new BigDecimal("906.00"), corrected.amountEur(), "a typo in the refund's bank euro can be corrected");
        assertTrue(orders.get(order.id()).notes()
                .contains("Tegoed leverancier terugbetaald 21/09/2026: US$ 1.000,00 (≈ € 906,00)."));
    }

    @Test
    @TestTransaction
    void anOffsetBecomesASupplierPaymentOnTheOtherContainerAndDeletingThatPaymentReopensTheCredit() {
        var supplier = supplier("Offset Co");
        long product = product(supplier, "CREDIT-O");
        PurchaseOrder source = placed(supplier, product);
        PurchaseOrder target = placed(supplier, product);
        var other = supplier("Other Co");
        PurchaseOrder foreign = placed(other, product(other, "CREDIT-X"));
        PurchaseOrder draft = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.95"), BigDecimal.ZERO);
        var credit = credits.add(source.id(),
                new CreditRequest(DAY, new BigDecimal("100"), Currency.USD, null, Reason.SHORTAGE, null));

        String sameSupplier = PurchaseSupplierCreditService.SAME_SUPPLIER_ONLY;
        assertRule(sameSupplier, () -> credits.offset(source.id(), credit.id(), new OffsetRequest(source.id(), DAY, null)));
        assertRule(sameSupplier, () -> credits.offset(source.id(), credit.id(), new OffsetRequest(foreign.id(), DAY, null)));
        assertRule(sameSupplier, () -> credits.offset(source.id(), credit.id(), new OffsetRequest(draft.id(), DAY, null)));
        assertRule(sameSupplier, () -> credits.offset(source.id(), credit.id(), new OffsetRequest(null, DAY, null)));

        /* The target was placed at 0,95: the offset takes its own rate so its terms close exactly. */
        var stored = entityManager.find(be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity.class,
                target.id());
        stored.usdToEurGoods = new BigDecimal("0.95");
        stored.usdToEurTransport = new BigDecimal("0.95");
        entityManager.flush();
        var offset = credits.offset(source.id(), credit.id(), new OffsetRequest(target.id(), DAY.plusDays(3), null));
        assertEquals(Status.OFFSET, offset.status());
        assertEquals(target.id(), offset.offsetOrderId());
        assertEquals(DAY.plusDays(3), offset.settledOn());
        assertEquals(new BigDecimal("90.00"), offset.amountEur(), "the credit keeps the source's euro value");
        PurchasePayment payment = orders.payments(target.id()).getFirst();
        assertEquals(offset.offsetPaymentId(), payment.id());
        assertEquals(PurchasePayment.Payee.SUPPLIER, payment.payee());
        assertEquals(new BigDecimal("100.00"), payment.amount());
        assertEquals(Currency.USD, payment.currency());
        assertEquals(new BigDecimal("95.00"), payment.amountEur());
        assertEquals("Verrekend tegoed " + source.number(), payment.label());
        assertEquals(List.of(new PurchaseSupplierCreditService.CreditOffsetView(credit.id(), source.id(),
                source.number(), payment.id(), new BigDecimal("90.00"))), credits.offsetsOnto(target.id()));
        var view = credits.views(source.id()).getFirst();
        assertEquals(target.number(), view.offsetOrderNumber());
        assertEquals(Status.OFFSET, view.status());
        assertTrue(orders.get(source.id()).notes().contains(
                "Tegoed leverancier verrekend met " + target.number() + ": US$ 100,00 (≈ € 90,00)."));
        assertEquals(new BigDecimal("90.00"), stream(orders.reconciliation(source.id())).creditEur(),
                "the source keeps its lower cost");
        assertEquals(new BigDecimal("95.00"), stream(orders.reconciliation(target.id())).paidEur(),
                "the target counts the offset as paid, never as a lower cost");
        assertEquals(new BigDecimal("0.00"), stream(orders.reconciliation(target.id())).creditEur());

        assertRule(PurchaseSupplierCreditService.OFFSET_IS_UNDONE_ELSEWHERE, () -> credits.update(source.id(),
                credit.id(), new CreditChange(null, null, null, null, null, Status.OPEN, null)));
        assertRule("Alleen een open tegoed kan verwijderd worden", () -> credits.delete(source.id(), credit.id()));
        assertRule("Alleen een open tegoed kan verrekend worden",
                () -> credits.offset(source.id(), credit.id(), new OffsetRequest(target.id(), DAY, null)));
        String locked = "Deze betaling is een verrekend tegoed van " + source.number() + "; maak de verrekening daar ongedaan";
        assertRule(locked, () -> orders.updatePayment(target.id(), payment.id(), payment.paidOn(),
                new BigDecimal("90"), Currency.USD, payment.label(), PurchasePayment.Payee.SUPPLIER, false));
        assertRule(locked, () -> orders.updatePayment(target.id(), payment.id(), payment.paidOn(),
                payment.amount(), Currency.EUR, payment.label(), PurchasePayment.Payee.SUPPLIER, false));
        assertRule(locked, () -> orders.updatePayment(target.id(), payment.id(), payment.paidOn(),
                payment.amount(), Currency.USD, payment.label(), PurchasePayment.Payee.LOGISTICS, false));
        orders.updatePayment(target.id(), payment.id(), DAY.plusDays(4), payment.amount(), Currency.USD,
                "Verrekend tegoed", PurchasePayment.Payee.SUPPLIER, true);
        assertEquals(DAY.plusDays(4), orders.supplierCredits(source.id()).getFirst().settledOn(),
                "the credit was settled the day its offset payment says");

        orders.deletePayment(target.id(), payment.id());
        var back = orders.supplierCredits(source.id()).getFirst();
        assertEquals(Status.OPEN, back.status());
        assertNull(back.offsetOrderId());
        assertNull(back.offsetPaymentId());
        assertNull(back.settledOn());
        assertTrue(credits.offsetsOnto(target.id()).isEmpty());
        assertFalse(orders.get(source.id()).notes().contains("verrekend met"));
        assertEquals(new BigDecimal("90.00"), orders.reconciliation(source.id()).totals().supplierCreditOpenEur());
    }

    @Test
    @TestTransaction
    void aContainerWithACreditIsArchivedNotDeleted() {
        var supplier = supplier("Keep Co");
        long product = product(supplier, "CREDIT-K");
        PurchaseOrder order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        orders.update(order.id(), order.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null,
                List.of(new PurchaseOrderLine(null, product, 100, BigDecimal.TEN, Currency.USD, null, null))));
        credits.add(order.id(), new CreditRequest(DAY, BigDecimal.TEN, Currency.EUR, null, Reason.PRICE, null));
        assertRule("Deze inkooporder heeft een tegoed van de leverancier; archiveer de container zodat het tegoed behouden blijft",
                () -> orders.delete(order.id()));
    }

    private static void assertRule(String message, Executable action) {
        assertEquals(message, assertThrows(BusinessRuleException.class, action).getMessage());
    }

    private static PurchaseReconciliation.Stream stream(PurchaseReconciliation reconciliation) {
        return reconciliation.streams().stream()
                .filter(stream -> stream.payee() == PurchasePayment.Payee.SUPPLIER).findFirst().orElseThrow();
    }

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, name, "CN", "Yiwu", null, null, null, Currency.USD, "FOB",
                "Ningbo", 30, null));
    }

    private long product(Supplier supplier, String sku) {
        var product = new ProductEntity();
        product.sku = sku;
        product.name = "Credit rose " + sku;
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        return product.id;
    }

    /** One container of 100 pieces at US$ 10, ordered. */
    private PurchaseOrder placed(Supplier supplier, long product) {
        PurchaseOrder order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        return orders.update(order.id(), order.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, product, 100, BigDecimal.TEN, Currency.USD, null, null)))).order();
    }
}
