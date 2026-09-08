package be.enrosed.sourcing.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchasePartnerAgreementTest {
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject SalesOrderService sales;
    @Inject be.enrosed.sales.application.PartnerAdvanceSchedules schedules;
    @Inject be.enrosed.sales.application.PartnerFinancingService financing;
    @Inject EntityManager em;

    @Test @TestTransaction
    void linkedAdvanceProtectsPartnerIdentityAndContainerHistory() {
        var partner = customer("Original partner");
        var other = customer("Different partner");
        var container = container(partner.id());
        linkedDocument(container, partner.id(), SalesPurpose.PARTNER_ADVANCE, QuoteStatus.CONCEPT);
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(other.id(), null, null)));
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(), null));
        assertThrows(BusinessRuleException.class, () -> purchases.delete(container.id()));
        assertEquals(partner.id(), purchases.get(container.id()).partnerCustomerId());
    }

    @Test @TestTransaction
    void issuedAdvanceLocksAgreementButSavingSameAgreementRemainsPossible() {
        var partner = customer("Issued advance partner");
        var container = container(partner.id());
        linkedDocument(container, partner.id(), SalesPurpose.PARTNER_ADVANCE, QuoteStatus.UITGEREIKT);
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("50"), new BigDecimal("50"))));
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("100"), new BigDecimal("25"))));
        assertEquals(new BigDecimal("100.00"), purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("100"), new BigDecimal("50"))).partnerCostPct());
    }

    @Test @TestTransaction
    void draftSettlementLocksAgreementAndUnknownPartnerIsRejected() {
        var partner = customer("Settlement partner");
        var container = container(partner.id());
        linkedDocument(container, partner.id(), SalesPurpose.PARTNER_SETTLEMENT, QuoteStatus.CONCEPT);
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("50"), new BigDecimal("50"))));
        assertThrows(NotFoundException.class, () -> purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(Long.MAX_VALUE, null, null)));
    }

    @Test @TestTransaction
    void anInvoiceDraftAlreadyProtectsTheAmountsPromisedInTheAgreement() {
        var partner = customer("Scheduled invoice partner");
        var container = container(partner.id());
        linkedDocument(container, partner.id(), SalesPurpose.PARTNER_ADVANCE, QuoteStatus.CONCEPT);
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(),
                new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("50"), new BigDecimal("50"))));
        assertEquals(new BigDecimal("100.00"), purchases.get(container.id()).partnerCostPct());
    }

    @Test @TestTransaction
    void unbilledPlanProtectsPartnerIdentityButCanBeRemovedWithAnUnusedPurchase() {
        var partner = customer("Unbilled plan partner");
        var other = customer("Replacement plan partner");
        var container = container(partner.id());
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Agreement(
                container.id(), partner.id(), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Row(null, container.id(), 0,
                "Start productie", new BigDecimal("30"), new BigDecimal("30"), null, null));
        assertThrows(BusinessRuleException.class, () -> purchases.setPartner(container.id(),
                new PurchaseOrderService.PartnerRequest(other.id(), null, null)));
        purchases.delete(container.id());
        assertNull(schedules.find(container.id()));
        assertTrue(schedules.rows(container.id()).isEmpty());
    }

    @Test @TestTransaction
    void anEmptyPlanIsClearedWhenThePartnerChanges() {
        var partner = customer("Empty plan original");
        var other = customer("Empty plan replacement");
        var container = container(partner.id());
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Agreement(
                container.id(), partner.id(), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        purchases.setPartner(container.id(), new PurchaseOrderService.PartnerRequest(other.id(), null, null));
        assertNull(schedules.find(container.id()));
        assertEquals(other.id(), purchases.get(container.id()).partnerCustomerId());
    }

    @Test @TestTransaction
    void homepageMilestonesAreUnbilledCommitmentsNotReceivablesOrRevenue() {
        var partner = customer("Milestone dashboard partner");
        var container = container(partner.id());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Brussels"));
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Agreement(
                container.id(), partner.id(), new BigDecimal("12000"), new BigDecimal("50"), new BigDecimal("6000")));
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Row(null, container.id(), 0,
                "Productiestart", new BigDecimal("30"), new BigDecimal("1800"), today.minusDays(1), null));
        schedules.save(new be.enrosed.sales.application.PartnerAdvanceSchedules.Row(null, container.id(), 1,
                "Productie klaar", new BigDecimal("70"), new BigDecimal("4200"), today.plusDays(14), null));
        var summary = financing.get(container.id());
        assertEquals(new BigDecimal("6000.00"), summary.unbilledAdvanceEur());
        assertEquals(2, summary.unbilledAdvanceCount());
        assertEquals(new BigDecimal("1800.00"), summary.overdueUnbilledAdvanceEur());
        assertEquals(today.minusDays(1), summary.nextAdvanceDueDate());
        assertEquals(0, summary.totalOpenEur().signum());
        assertEquals(0, summary.recognizedRevenueEur().signum());
    }

    @Test @TestTransaction
    void partnerCustomerCannotDisappearWhileThePurchaseStillRefersToIt() {
        var partner = customer("Referenced partner");
        var container = container(partner.id());
        assertThrows(BusinessRuleException.class, () -> customers.delete(partner.id()));
        purchases.setPartner(container.id(), null);
        customers.delete(partner.id());
        assertThrows(NotFoundException.class, () -> customers.get(partner.id()));
    }

    private Customer customer(String name) {
        return customers.create(new Customer(null, name, "Finance", null, null, "BE0000000000", "BE", Language.NL,
                "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
    }

    private PurchaseOrder container(long partnerId) {
        var supplier = suppliers.save(new Supplier(null, "Agreement supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var order = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        return purchases.setPartner(order.id(), new PurchaseOrderService.PartnerRequest(partnerId, new BigDecimal("100"), new BigDecimal("50")));
    }

    private void linkedDocument(PurchaseOrder container, long customerId, SalesPurpose purpose, QuoteStatus status) {
        var invoice = sales.create(customerId, "BE", "DAP", DocumentType.FACTUUR);
        var entity = em.find(SalesOrderEntity.class, invoice.id());
        entity.partnerPurchaseOrderId = container.id();
        entity.sourcePurchaseOrderId = container.id();
        entity.partnerSharePct = new BigDecimal("50");
        entity.purpose = purpose;
        entity.partnerSettlement = purpose == SalesPurpose.PARTNER_SETTLEMENT;
        entity.status = status;
        em.flush(); em.clear();
    }
}
