package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.in.rest.SourcingResource.PaymentRequest;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
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
    void anExistingPaymentProtectsItsMilestoneButNotUnpaidMilestones() {
        var order = order(PurchaseOrderStatus.CONCEPT);
        service.addPayment(order.id(), DAY, BigDecimal.TEN, Currency.EUR, "Deposit", SUPPLIER, true, ORDERED);
        var current = service.get(order.id());
        service.update(order.id(), current.withPaymentSplit(new BigDecimal("30.00"),
                new BigDecimal("20"), new BigDecimal("50")));
        assertEquals(new BigDecimal("20.00"), service.get(order.id()).payPctShipped().setScale(2));
        var reason = assertThrows(BusinessRuleException.class, () -> service.update(order.id(),
                service.get(order.id()).withPaymentSplit(new BigDecimal("40"),
                        new BigDecimal("20"), new BigDecimal("40"))));
        assertTrue(reason.getMessage().contains("betalingen aan deze termijn"));
        assertEquals(new BigDecimal("30.00"), service.get(order.id()).payPctOrdered().setScale(2));
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

    private List<String> attention(PurchaseOrder order) {
        return service.attention(service.get(order.id()), PAYABLE);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        var supplier = suppliers.save(new Supplier(null, "Milestone supplier", "CN", "Yiwu", null, null,
                null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = service.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var stored = entities.find(PurchaseOrderEntity.class, order.id());
        stored.status = status;
        stored.paymentTerms = PaymentTerms.CUSTOM;
        stored.payPctOrdered = new BigDecimal("30");
        stored.payPctShipped = new BigDecimal("30");
        stored.payPctArrived = new BigDecimal("40");
        stored.trackingReference = "TEST-TRACKING";
        entities.flush(); entities.clear();
        return service.get(order.id());
    }
}
