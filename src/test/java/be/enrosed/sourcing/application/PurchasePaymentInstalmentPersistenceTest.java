package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.in.rest.SourcingResource.PaymentRequest;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PriceBasis;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static be.enrosed.sourcing.domain.PaymentTerms.Moment.*;
import static be.enrosed.sourcing.domain.PurchasePayment.Payee.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchasePaymentInstalmentPersistenceTest {
    @Inject PurchaseOrderService service;
    @Inject SupplierService suppliers;
    @Inject EntityManager entities;
    @Inject ObjectMapper json;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final PurchaseOrderService.Payable PAYABLE = new PurchaseOrderService.Payable(
            new BigDecimal("59620.00"), BigDecimal.ZERO, BigDecimal.ZERO, false, false);

    @Test
    @TestTransaction
    void removingTheLastTermPaymentReopensOnlyThatTermAndRevokingEarlierSettlementReopensItsGap() {
        var order = order(PurchaseOrderStatus.ONTVANGEN);
        var first = service.addPayment(order.id(), DAY, new BigDecimal("17000"), Currency.EUR,
                "First 30%", SUPPLIER, true, ORDERED);
        service.addPayment(order.id(), DAY.plusDays(1), new BigDecimal("17886"), Currency.EUR,
                "Next 30%", SUPPLIER, false, SHIPPED);
        var last = service.addPayment(order.id(), DAY.plusDays(2), new BigDecimal("23000"), Currency.EUR,
                "Final 40%", SUPPLIER, true, ARRIVED);
        entities.flush(); entities.clear();
        assertEquals(ORDERED, service.payments(order.id()).getFirst().instalmentDue());
        assertEquals(List.of(), attention(order));
        assertTrue(service.get(order.id()).notes().contains("deze termijn vereffend"));

        service.deletePayment(order.id(), last.id());
        entities.flush(); entities.clear();
        assertEquals(List.of("Betaling open: 40% bij aankomst (€ 23.848,00)"), attention(order));
        assertFalse(service.get(order.id()).notes().contains("Final 40%"));
        var allocation = SupplierPaymentAllocation.calculate(service.get(order.id()), PAYABLE.supplierEur(),
                service.payments(order.id()));
        assertEquals(new BigDecimal("886.00"), allocation.getFirst().settledSavingEur());
        assertFalse(allocation.getLast().finalized());

        service.updatePayment(order.id(), first.id(), DAY, first.amount(), Currency.EUR, first.label(),
                SUPPLIER, false, ORDERED);
        entities.flush(); entities.clear();
        assertEquals(List.of("Betaling open: 30% bij bestelling (€ 886,00)",
                "Betaling open: 40% bij aankomst (€ 23.848,00)"), attention(order));
        assertEquals(new BigDecimal("34886.00"), service.payments(order.id()).stream()
                .map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    @TestTransaction
    void oldClientMetadataEditsPreserveTermScopeAndStoredFxWhileExplicitNullSelectsWholeGroup() {
        var order = order(PurchaseOrderStatus.BESTELD);
        var first = service.addPayment(order.id(), DAY, new BigDecimal("300"), Currency.USD,
                "USD transfer", SUPPLIER, false, ORDERED);
        assertEquals(new BigDecimal("270.00"), first.amountEur());
        var stored = entities.find(PurchaseOrderEntity.class, order.id());
        stored.usdToEurGoods = new BigDecimal("0.99");
        entities.flush(); entities.clear();

        var unchanged = service.updatePayment(order.id(), first.id(), DAY.plusDays(1), first.amount(),
                Currency.USD, "Term settled", SUPPLIER, true);
        assertEquals(ORDERED, unchanged.instalmentDue());
        assertFalse(unchanged.settlesWholeGroup());
        assertEquals(new BigDecimal("270.00"), unchanged.amountEur());
        var whole = service.updatePayment(order.id(), first.id(), DAY.plusDays(1), first.amount(),
                Currency.USD, "Whole supplier group settled", SUPPLIER, true, null);
        entities.flush(); entities.clear();
        var loaded = service.payments(order.id()).getFirst();
        assertNull(loaded.instalmentDue());
        assertTrue(loaded.settlesWholeGroup());
        assertEquals(new BigDecimal("270.00"), whole.amountEur());
        assertEquals(List.of(), attention(order));
    }

    @Test
    @TestTransaction
    void aMilestoneCannotBeAppliedToAnotherCostGroup() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        assertThrows(BusinessRuleException.class, () -> service.addPayment(order.id(), DAY,
                BigDecimal.TEN, Currency.EUR, "Not a supplier payment", LOGISTICS, true, ORDERED));
        assertEquals(List.of(), service.payments(order.id()));
    }

    @Test
    @TestTransaction
    void aRenegotiatedPercentageKeepsThePaidMilestoneAndItsHistoricalTransfer() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        var first = service.addPayment(order.id(), DAY, new BigDecimal("4500"), Currency.USD, "Deposit", SUPPLIER, true, ORDERED);
        var current = service.get(order.id());
        service.update(order.id(), current.withPaymentSplit(new BigDecimal("50"),
                new BigDecimal("50"), BigDecimal.ZERO));
        entities.flush(); entities.clear();
        var saved = service.get(order.id());
        var loadedPayment = service.payments(order.id()).getFirst();
        assertEquals(first.id(), loadedPayment.id());
        assertEquals(first.amount(), loadedPayment.amount());
        assertEquals(first.amountEur(), loadedPayment.amountEur());
        assertEquals(first.currency(), loadedPayment.currency());
        assertEquals(first.paidOn(), loadedPayment.paidOn());
        assertEquals(first.label(), loadedPayment.label());
        assertEquals(first.instalmentDue(), loadedPayment.instalmentDue());
        assertTrue(loadedPayment.settles());
        assertEquals(new BigDecimal("50.00"), saved.payPctOrdered().setScale(2));
        var allocation = SupplierPaymentAllocation.calculate(saved, new BigDecimal("7774.20"), List.of(loadedPayment));
        assertEquals("50% bij bestelling", allocation.getFirst().label());
        assertEquals(new BigDecimal("3887.10"), allocation.getFirst().plannedEur());
        assertEquals(new BigDecimal("4050.00"), allocation.getFirst().paidEur());
        assertEquals(new BigDecimal("162.90"), allocation.getFirst().overpaidEur());
        assertTrue(saved.notes().contains("Deposit"));

        var reason = assertThrows(BusinessRuleException.class, () -> service.update(order.id(),
                saved.withPaymentSplit(BigDecimal.ZERO, new BigDecimal("50"), new BigDecimal("50"))));
        assertTrue(reason.getMessage().contains("betalingen aan deze termijn"));
        assertEquals(new BigDecimal("50.00"), service.get(order.id()).payPctOrdered().setScale(2));
    }

    @Test
    @TestTransaction
    void aPaymentCannotTargetAMomentAbsentFromThePlan() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        service.update(order.id(), order.withPaymentSplit(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(BusinessRuleException.class, () -> service.addPayment(order.id(), DAY,
                BigDecimal.TEN, Currency.EUR, "Invalid moment", SUPPLIER, true, ARRIVED));
    }

    @Test
    void requestDeserializationDistinguishesOldClientsFromExplicitWholeGroupChoice() throws Exception {
        var absent = json.readValue("{\"amount\":10,\"settles\":true}", PaymentRequest.class);
        var whole = json.readValue("{\"amount\":10,\"settles\":true,\"instalmentDue\":null}", PaymentRequest.class);
        var term = json.readValue("{\"amount\":10,\"settles\":true,\"instalmentDue\":\"ORDERED\"}", PaymentRequest.class);
        assertFalse(absent.instalmentDueProvided());
        assertTrue(whole.instalmentDueProvided());
        assertNull(whole.instalmentDue());
        assertTrue(term.instalmentDueProvided());
        assertEquals(ORDERED, term.instalmentDue());
        assertEquals(new BigDecimal("10"), term.amount());
    }

    /**
     * PO-2026-002 as booked: + € 61,28 on the 30 % and - € 60,78 on the 70 %.
     * The supplier's terms net to € 0,50 too much, so nothing is open and the
     * list asks for no payment, through the reconciliation the tab reads and
     * through a payable of a caller's own alike.
     */
    @Test
    @TestTransaction
    void po35TermsNetSoTheListAsksForNoPayment() {
        var order = order(PurchaseOrderStatus.CONCEPT, PaymentTerms.DEPOSIT_30_70);
        long product = product(order, "SKU-PO35", 1);
        service.update(order.id(), order.withReceipt(order.status(), null, null, false, null,
                List.of(new PurchaseOrderLine(null, product, 4, new BigDecimal("7762.01"), Currency.EUR, null, null,
                        PriceBasis.DDP, null))));
        setStatus(order, PurchaseOrderStatus.ONTVANGEN);
        service.addPayment(order.id(), DAY, new BigDecimal("9375.69"), Currency.EUR, "30%", SUPPLIER, false, ORDERED);
        service.addPayment(order.id(), DAY.plusDays(40), new BigDecimal("21672.85"), Currency.EUR, "70%",
                SUPPLIER, false, SHIPPED);
        entities.flush(); entities.clear();

        var loaded = service.get(order.id());
        var reconciliation = service.reconciliation(loaded, service.calculate(loaded));
        var supplier = reconciliation.streams().stream().filter(stream -> stream.payee() == SUPPLIER)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("31048.04"), supplier.plannedEur());
        assertEquals(new BigDecimal("0.00"), supplier.remainingEur());
        assertEquals(new BigDecimal("0.50"), supplier.overpaidEur());
        assertEquals(new BigDecimal("0.00"), reconciliation.totals().remainingEur());
        var terms = reconciliation.supplierInstalments();
        assertEquals(List.of(new BigDecimal("61.28"), new BigDecimal("-60.78")),
                terms.stream().map(PurchaseReconciliation.SupplierInstalment::differenceEur).toList());
        assertEquals(List.of(new BigDecimal("-60.78"), new BigDecimal("60.78")),
                terms.stream().map(PurchaseReconciliation.SupplierInstalment::offsetEur).toList());
        assertEquals(List.of(), service.attention(loaded, reconciliation));
        var po35 = new PurchaseOrderService.Payable(new BigDecimal("31048.04"), BigDecimal.ZERO, BigDecimal.ZERO,
                true, true);
        assertEquals(List.of(), service.attention(loaded, po35));

        // Settling the 30 % on its own takes it out of the netting: its overrun is
        // confirmed there, and the 70 % asks for its € 60,78 again.
        var deposit = service.payments(order.id()).getFirst();
        service.updatePayment(order.id(), deposit.id(), deposit.paidOn(), deposit.amount(), Currency.EUR,
                deposit.label(), SUPPLIER, true, ORDERED);
        entities.flush(); entities.clear();
        loaded = service.get(order.id());
        assertEquals(List.of("Betaling open: 70% bij vertrek (€ 60,78)"),
                service.attention(loaded, service.reconciliation(loaded, service.calculate(loaded))));
    }

    /**
     * Twenty of a hundred pieces never came. The tab reconciles on the
     * ordered quantities, and the list now asks for exactly the term the tab
     * shows open, not for one rebuilt on the pieces that arrived.
     */
    @Test
    @TestTransaction
    void aShortDeliveredOrderAsksForTheTermsTheTabShows() {
        var order = order(PurchaseOrderStatus.CONCEPT, PaymentTerms.DEPOSIT_30_70);
        long product = product(order, "SKU-SHORT", 10);
        service.update(order.id(), order.withReceipt(order.status(), null, null, false, null,
                List.of(new PurchaseOrderLine(null, product, 100, BigDecimal.TEN, Currency.USD, null, null))));
        setStatus(order, PurchaseOrderStatus.ONTVANGEN);
        var line = entities.find(PurchaseOrderEntity.class, order.id()).lines.getFirst();
        line.orderedQuantity = 100;
        line.quantity = 80;
        service.addPayment(order.id(), DAY, new BigDecimal("270"), Currency.EUR, "30%", SUPPLIER, false, ORDERED);
        entities.flush(); entities.clear();

        var loaded = service.get(order.id());
        var reconciliation = service.reconciliation(loaded, service.calculate(loaded));
        var terms = reconciliation.supplierInstalments();
        assertEquals(List.of(new BigDecimal("270.00"), new BigDecimal("630.00")),
                terms.stream().map(PurchaseReconciliation.SupplierInstalment::plannedEur).toList(),
                "100 ordered pieces at US$ 10 and 0,90");
        assertEquals(List.of("Betaling open: 70% bij vertrek (€ 630,00)"), service.attention(loaded, reconciliation));
        assertEquals(new BigDecimal("630.00"), terms.getLast().remainingEur());
        var arrived = service.payable(loaded, service.calculate(loaded), null);
        assertNotEquals(service.attention(loaded, reconciliation), service.attention(loaded, arrived),
                "the 80 pieces that arrived would ask for less than the tab shows");
    }

    private long product(PurchaseOrder order, String sku, int piecesPerCarton) {
        var product = new be.enrosed.catalog.adapter.out.persistence.ProductEntity();
        product.sku = sku;
        product.name = sku;
        product.active = true;
        product.supplierId = order.supplierId();
        product.piecesPerCarton = piecesPerCarton;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        entities.persist(product);
        entities.flush();
        return product.id;
    }

    private void setStatus(PurchaseOrder order, PurchaseOrderStatus status) {
        entities.find(PurchaseOrderEntity.class, order.id()).status = status;
        entities.flush();
    }

    private List<String> attention(PurchaseOrder order) {
        return service.attention(service.get(order.id()), PAYABLE);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        return order(status, PaymentTerms.CUSTOM);
    }

    private PurchaseOrder order(PurchaseOrderStatus status, PaymentTerms terms) {
        var supplier = suppliers.save(new Supplier(null, "Milestone supplier", "CN", "Yiwu", null, null,
                null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = service.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var stored = entities.find(PurchaseOrderEntity.class, order.id());
        stored.status = status;
        stored.paymentTerms = terms;
        if (terms == PaymentTerms.CUSTOM) {
            stored.payPctOrdered = new BigDecimal("30");
            stored.payPctShipped = new BigDecimal("30");
            stored.payPctArrived = new BigDecimal("40");
        }
        stored.trackingReference = "TEST-TRACKING";
        entities.flush(); entities.clear();
        return service.get(order.id());
    }
}
