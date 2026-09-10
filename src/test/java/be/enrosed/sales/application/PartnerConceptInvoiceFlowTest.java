package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre", roles = "admin")
class PartnerConceptInvoiceFlowTest {
    @InjectSpy SalesOrderService sales;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerFinancingService financing;
    @Inject PartnerAdvanceQuotes snapshots;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void plannedCreationProducesOnlyUnsentTermInvoicesAndRetriesReuseTheSameIds() {
        Fixture fixture = fixture("50");
        var request = request(fixture, plan(), SalesPurpose.PARTNER_ADVANCE);

        SalesOrder first = sales.createFromPurchaseOrder(request);
        var plan = schedules.get(fixture.purchaseId());
        var ids = plan.rows().stream().map(PartnerAdvanceScheduleService.Row::invoiceId).toList();
        assertEquals(ids.getFirst(), first.id());
        assertEquals(List.of(money("1800"), money("4200")), plan.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        for (Long id : ids) {
            SalesOrder invoice = sales.get(id);
            assertEquals(DocumentType.FACTUUR, invoice.docType());
            assertEquals(QuoteStatus.CONCEPT, invoice.status());
            assertEquals(SalesPaymentPlan.FULL, invoice.paymentPlan());
            assertTrue(invoice.number().startsWith("container/"));
            assertNull(invoice.sentAt()); assertNull(invoice.portalToken()); assertNull(invoice.sourceQuoteId());
            assertNull(invoice.notes()); assertNull(snapshots.find(id));
        }
        assertEquals(2, linked(fixture).size(), "there must be no umbrella quote or second full advance invoice");
        assertEquals(money("0"), plan.reservedOutsideScheduleEur());
        assertEquals(first.id(), sales.createFromPurchaseOrder(request).id(), "retry still has null plan row ids");
        assertEquals(ids, schedules.get(fixture.purchaseId()).rows().stream().map(PartnerAdvanceScheduleService.Row::invoiceId).toList());
        assertEquals(2, linked(fixture).size());
        var summary = financing.get(fixture.purchaseId());
        assertEquals(money("6000"), summary.committedAdvanceEur());
        assertEquals(money("0"), summary.invoicedAdvanceEur());
        assertEquals(money("0"), summary.receivedAdvanceEur());
        for (Long id : ids) assertNull(sales.issueInvoice(id).sentAt(), "internal issuance must not claim an email was sent");
        assertEquals(money("6000"), financing.get(fixture.purchaseId()).invoicedAdvanceEur());
    }

    @Test @TestTransaction
    void noPlanCreatesOneFullConceptInvoiceWhileOrdinarySalesRemainQuotations() {
        Fixture fixture = fixture("50");
        var request = request(fixture, null, SalesPurpose.PARTNER_ADVANCE);
        SalesOrder invoice = sales.createFromPurchaseOrder(request);
        assertEquals(DocumentType.FACTUUR, invoice.docType());
        assertEquals(QuoteStatus.CONCEPT, invoice.status());
        assertEquals(money("6000"), sales.price(invoice).totals().total());
        assertNull(invoice.sentAt()); assertNull(invoice.portalToken());
        assertTrue(schedules.get(fixture.purchaseId()).rows().isEmpty());
        assertEquals(invoice.id(), sales.createFromPurchaseOrder(request).id());
        assertEquals(1, linked(fixture).size());
        SalesOrder ordinary = sales.createFromPurchaseOrder(request(fixture, null, SalesPurpose.STANDARD));
        assertEquals(DocumentType.OFFERTE, ordinary.docType());
        assertFalse(ordinary.isPartnerDeal());
    }

    @Test @TestTransaction
    void zeroFinancingCreatesNoDocumentAndQuotesCannotBecomePartnerAdvancesThroughOtherEndpoints() {
        Fixture fixture = fixture("0");
        assertThrows(BusinessRuleException.class, () -> sales.createFromPurchaseOrder(request(fixture, null, SalesPurpose.PARTNER_ADVANCE)));
        assertTrue(linked(fixture).isEmpty());
        SalesOrder quote = sales.create(fixture.customerId(), "BE", "DAP");
        assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(quote.id(),
                new SalesOrderService.PartnerDealRequest(fixture.purchaseId(), new BigDecimal("50"), null)));
        assertFalse(sales.get(quote.id()).isPartnerDeal());
    }

    @Test @TestTransaction
    void changingAnAlreadyInvoicedPlanCannotCreateAnotherClaim() {
        Fixture fixture = fixture("50");
        sales.createFromPurchaseOrder(request(fixture, plan(), SalesPurpose.PARTNER_ADVANCE));
        var changed = new PartnerAdvanceScheduleService.Request(List.of(row("Start", "40"), row("Gereed", "60")), false);
        assertThrows(BusinessRuleException.class, () -> sales.createFromPurchaseOrder(request(fixture, changed, SalesPurpose.PARTNER_ADVANCE)));
        assertEquals(2, linked(fixture).size());
        assertEquals(List.of(money("1800"), money("4200")), schedules.get(fixture.purchaseId()).rows().stream()
                .map(PartnerAdvanceScheduleService.Row::amountEur).toList());
    }

    @Test
    void failureOnSecondInvoiceRollsBackTheFirstInvoiceAndTheWholeSavedPlan() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(() -> fixture("50"));
        doThrow(new IllegalStateException("Second invoice could not be stored")).when(sales)
                .createScheduledPartnerAdvance(any(PurchaseOrder.class), anyLong(), eq("Gereed"),
                        any(BigDecimal.class), any(LocalDate.class), nullable(String.class), nullable(Long.class));
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                sales.createFromPurchaseOrder(request(fixture, plan(), SalesPurpose.PARTNER_ADVANCE))));
        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(linked(fixture).isEmpty());
            assertTrue(schedules.get(fixture.purchaseId()).rows().isEmpty());
        });
    }

    private List<SalesOrder> linked(Fixture fixture) {
        return sales.list().stream().filter(order -> Long.valueOf(fixture.purchaseId()).equals(order.linkedPurchaseOrderId())
                && order.isPartnerDeal()).toList();
    }
    private static SalesOrderService.FromPurchaseOrderRequest request(Fixture fixture,
            PartnerAdvanceScheduleService.Request plan, SalesPurpose purpose) {
        return new SalesOrderService.FromPurchaseOrderRequest(fixture.purchaseId(), fixture.customerId(), "COST", BigDecimal.ZERO,
                true, new BigDecimal("50"), fixture.financing(), false, List.of(), "PARTNER", null,
                purpose, SalesPaymentPlan.FULL, plan);
    }
    private static PartnerAdvanceScheduleService.Request plan() {
        return new PartnerAdvanceScheduleService.Request(List.of(row("Start", "30"), row("Gereed", "70")), false);
    }
    private static PartnerAdvanceScheduleService.RowRequest row(String label, String percentage) {
        return new PartnerAdvanceScheduleService.RowRequest(null, label, new BigDecimal(percentage), null, LocalDate.of(2026, 10, 15));
    }
    private record Fixture(long purchaseId, long customerId, BigDecimal financing) {}
    private Fixture fixture(String financing) {
        Customer partner = customers.create(new Customer(null, "Concept invoice partner", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        Supplier supplier = suppliers.save(new Supplier(null, "Concept invoice supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal(financing), new BigDecimal("50")));
        ProductEntity product = new ProductEntity(); product.sku = "DRAFT-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        entities.persist(product); entities.flush();
        PurchaseOrderEntity entity = entities.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id;
        line.quantity = 12; line.exwPrice = new BigDecimal("1000"); line.exwCurrency = Currency.EUR;
        entity.lines.add(line); entities.persist(line); entities.flush(); entities.clear();
        return new Fixture(purchase.id(), partner.id(), new BigDecimal(financing));
    }
    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
