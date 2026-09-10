package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
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
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PartnerSchedulesAndPartialSettlementsTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceSchedules scheduleRows;
    @Inject SalesOrderService sales;
    @Inject PartnerSettlements settlements;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService financing;
    @Inject EntityManager em;

    @Test @TestTransaction
    void thirtySeventyInvoicesDivideThePartnerContributionInsteadOfTheContainerTwice() {
        for (String funding : List.of("50", "100")) {
            var fixture = fixture("12000", 12, funding);
            var schedule = save(fixture.purchase.id(), pct(null, "Start productie", "30", LocalDate.of(2026, 9, 15)),
                    pct(null, "Productie klaar", "70", LocalDate.of(2026, 10, 15)));
            BigDecimal agreed = funding.equals("50") ? amount("6000") : amount("12000");
            assertEquals(agreed, schedule.agreedAmountEur());
            assertEquals(amount("0"), schedule.unallocatedEur());
            var first = schedules.createInvoice(fixture.purchase.id(), schedule.rows().getFirst().id());
            var second = schedules.createInvoice(fixture.purchase.id(), schedule.rows().getLast().id());
            assertEquals(first.id(), schedules.createInvoice(fixture.purchase.id(), schedule.rows().getFirst().id()).id());
            assertEquals(agreed.multiply(new BigDecimal("0.30")).setScale(2), sales.price(first).totals().total());
            assertEquals(agreed.multiply(new BigDecimal("0.70")).setScale(2), sales.price(second).totals().total());
            assertEquals(SalesPaymentPlan.FULL, first.paymentPlan());
            assertEquals(LocalDate.of(2026, 9, 15), first.invoiceDueDate());
        assertNull(first.notes(), "generated payment explanations must not become manual notes on compact PDFs");
            assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(first.id()).status());
            em.flush(); em.clear();
            var persisted = schedules.get(fixture.purchase.id());
            assertEquals(first.id(), persisted.rows().getFirst().invoiceId());
            assertEquals(agreed, persisted.allocatedEur());
        }
    }

    @Test @TestTransaction
    void advanceOverpaymentAndRefundRemainVisibleWithoutCreatingProfit() {
        var fixture = fixture("12000", 12, "50");
        var schedule = save(fixture.purchase.id(), pct(null, "Bijdrage", "100", null));
        var invoice = sales.issueInvoice(schedules.createInvoice(fixture.purchase.id(), schedule.rows().getFirst().id()).id());
        BigDecimal total = sales.price(invoice).totals().totalInclVat();
        var at = java.time.Instant.now().minusSeconds(60);
        incoming.add(invoice.id(), new IncomingPaymentService.Request(total.add(amount("100")), at, "Europe/Brussels", "Testontvangst"));
        assertEquals(amount("100"), financing.get(fixture.purchase.id()).creditEur());
        incoming.add(invoice.id(), new IncomingPaymentService.Request(amount("100"), at.plusSeconds(30), "Europe/Brussels",
                "Te veel ontvangen terugbetaald", IncomingPaymentService.Direction.REFUND, "TST REKENING"));
        var summary = financing.get(fixture.purchase.id());
        assertEquals(amount("0"), summary.creditEur());
        assertEquals(total, summary.totalReceivedEur());
        assertEquals(0, summary.recognizedProfitEur().signum());
    }

    @Test @TestTransaction
    void aFullConceptInvoiceAndCopiesCannotBypassFinancingReservations() {
        var fixture = fixture("12000", 12, "50");
        var quote = sales.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(
                fixture.purchase.id(), fixture.partnerId, "COST", BigDecimal.ZERO, true, new BigDecimal("50"),
                new BigDecimal("50"), false, List.of(), "PARTNER", null, SalesPurpose.PARTNER_ADVANCE, SalesPaymentPlan.FULL));
        assertEquals(DocumentType.FACTUUR, quote.docType());
        assertThrows(BusinessRuleException.class, () -> save(fixture.purchase.id(), pct(null, "30%", "30", null), pct(null, "70%", "70", null)));
        assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id()));
        assertThrows(BusinessRuleException.class, () -> sales.duplicate(quote.id()));
        assertThrows(BusinessRuleException.class, () -> schedules.save(fixture.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(), true)));
    }

    @Test @TestTransaction
    void percentageResidualsCloseExactlyAndBilledRowsRemainFrozen() {
        var fixture = fixture("1", 3, "100");
        var schedule = save(fixture.purchase.id(), pct(null, "Een", "33.33", null), pct(null, "Twee", "33.33", null),
                pct(null, "Drie", "33.34", null));
        assertEquals(List.of(amount("0.33"), amount("0.33"), amount("0.34")), schedule.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        var last = schedules.createInvoice(fixture.purchase.id(), schedule.rows().getLast().id());
        var resaved = schedules.save(fixture.purchase.id(), new PartnerAdvanceScheduleService.Request(schedule.rows().stream()
                .map(row -> pct(row.id(), row.label(), row.percentage().toPlainString(), row.dueDate())).toList(), false));
        assertEquals(amount("0.34"), resaved.rows().getLast().amountEur());
        assertEquals(last.id(), resaved.rows().getLast().invoiceId());
        sales.delete(last.id());
        assertNull(schedules.get(fixture.purchase.id()).rows().getLast().invoiceId());
        assertEquals(amount("1"), schedules.get(fixture.purchase.id()).allocatedEur(), "deleting a draft frees its link, retaining the planned reservation");
    }

    @Test @TestTransaction
    void partialSettlementsReserveRemainingQuantitiesAndCreditOnlyTheirCostShare() {
        var fixture = fixture("12000", 12, "50");
        var plan = save(fixture.purchase.id(), pct(null, "Productie", "100", null));
        var advance = schedules.createInvoice(fixture.purchase.id(), plan.rows().getFirst().id());
        sales.issueInvoice(advance.id());
        var first = settle(fixture, 4, "6000", false);
        var a = settlements.find(first.id());
        assertFalse(a.finalSettlement()); assertEquals(amount("4000"), a.costEur());
        assertEquals(amount("5000"), a.revenueEur()); assertEquals(amount("2000"), a.advanceEur());
        assertEquals(amount("3000"), sales.price(first).totals().total());
        var available = sales.partnerSettlementAvailability(fixture.purchase.id());
        assertEquals(8, available.lines().getFirst().remainingQuantity());
        assertEquals(amount("4000"), available.remainingAdvanceEur());
        assertThrows(BusinessRuleException.class, () -> settle(fixture, 9, "10000", false));
        assertThrows(BusinessRuleException.class, () -> settle(fixture, 4, "6000", true));
        sales.issueInvoice(first.id());
        var last = settle(fixture, 8, "12000", true);
        var b = settlements.find(last.id());
        assertTrue(b.finalSettlement()); assertEquals(amount("8000"), b.costEur());
        assertEquals(amount("4000"), b.advanceEur()); assertEquals(amount("10000"), b.revenueEur());
        assertEquals(amount("6000"), sales.price(last).totals().total());
        assertEquals(0, sales.partnerSettlementAvailability(fixture.purchase.id()).lines().getFirst().remainingQuantity());
        assertThrows(BusinessRuleException.class, () -> settle(fixture, 1, "1000", false));
        assertThrows(BusinessRuleException.class, () -> sales.duplicate(first.id()));
        assertThrows(BusinessRuleException.class, () -> sales.update(first.id(), first));
    }

    @Test @TestTransaction
    void finalCostAndAdvanceCreditConsumeResidualCentsAndDraftDeletionReleasesCapacity() {
        var fixture = fixture("100", 3, "100");
        var plan = save(fixture.purchase.id(), pct(null, "Productie", "100", null));
        var advance = schedules.createInvoice(fixture.purchase.id(), plan.rows().getFirst().id());
        sales.issueInvoice(advance.id());
        var first = settle(fixture, 1, "50", false);
        assertEquals(amount("33.33"), settlements.find(first.id()).costEur());
        assertEquals(amount("33.33"), settlements.find(first.id()).advanceEur());
        sales.delete(first.id());
        assertEquals(3, sales.partnerSettlementAvailability(fixture.purchase.id()).lines().getFirst().remainingQuantity());
        first = settle(fixture, 1, "50", false);
        var second = settle(fixture, 1, "50", false);
        var last = settle(fixture, 1, "50", true);
        var snapshots = List.of(settlements.find(first.id()), settlements.find(second.id()), settlements.find(last.id()));
        assertEquals(amount("100"), snapshots.stream().map(PartnerSettlements.Snapshot::costEur).reduce(amount("0"), BigDecimal::add));
        assertEquals(amount("100"), snapshots.stream().map(PartnerSettlements.Snapshot::advanceEur).reduce(amount("0"), BigDecimal::add));
        assertEquals(amount("125"), snapshots.stream().map(PartnerSettlements.Snapshot::revenueEur).reduce(amount("0"), BigDecimal::add));
        assertEquals(3, snapshots.stream().flatMap(snapshot -> snapshot.lines().stream()).mapToInt(PartnerSettlements.Line::quantity).sum());
    }

    @Test @TestTransaction
    void firstSettlementWaitsForEveryPlannedAdvanceToBeIssuedThenClosesAdvanceInvoicing() {
        var fixture = fixture("12000", 12, "50");
        var plan = save(fixture.purchase.id(), pct(null, "30%", "30", null), pct(null, "70%", "70", null));
        sales.issueInvoice(schedules.createInvoice(fixture.purchase.id(), plan.rows().getFirst().id()).id());
        assertThrows(BusinessRuleException.class, () -> settle(fixture, 6, "9000", false));
        var pending = schedules.createInvoice(fixture.purchase.id(), plan.rows().getLast().id());
        assertThrows(BusinessRuleException.class, () -> settle(fixture, 6, "9000", false));
        sales.issueInvoice(pending.id());
        var first = settle(fixture, 6, "9000", false);
        assertEquals(amount("3000"), settlements.find(first.id()).advanceEur());
        assertTrue(schedules.get(fixture.purchase.id()).invoicingBlocked());
        assertThrows(BusinessRuleException.class, () -> save(fixture.purchase.id(),
                pct(plan.rows().getFirst().id(), "30%", "30", null), pct(plan.rows().getLast().id(), "70%", "70", null),
                new PartnerAdvanceScheduleService.RowRequest(null, "Later", null, BigDecimal.ONE, null)));
        var last = settle(fixture, 6, "9000", true);
        assertEquals(amount("3000"), settlements.find(last.id()).advanceEur());
    }

    @Test @TestTransaction
    void unusedScheduleRemainderCanBeRemovedBeforeSettlementButCannotBeReintroducedAfterward() {
        var fixture = fixture("12000", 12, "50");
        var plan = save(fixture.purchase.id(), pct(null, "30%", "30", null), pct(null, "70%", "70", null));
        sales.issueInvoice(schedules.createInvoice(fixture.purchase.id(), plan.rows().getFirst().id()).id());
        save(fixture.purchase.id(), pct(plan.rows().getFirst().id(), "30%", "30", null));
        var first = settle(fixture, 6, "9000", false);
        assertEquals(amount("900"), settlements.find(first.id()).advanceEur());
        assertThrows(BusinessRuleException.class, () -> save(fixture.purchase.id(),
                pct(plan.rows().getFirst().id(), "30%", "30", null), pct(null, "Later", "70", null)));
        assertEquals(amount("900"), settlements.find(settle(fixture, 6, "9000", true).id()).advanceEur());
    }

    private SalesOrder settle(Fixture f, int quantity, String proceeds, boolean finalSettlement) {
        return sales.createAuctionSettlement(new SalesOrderService.AuctionSettlementRequest(f.partnerId, f.purchase.id(), null,
                null, new BigDecimal("100"), new BigDecimal("50"),
                List.of(new SalesOrderService.AuctionLine(f.productId, quantity, amount(proceeds), null)), null, finalSettlement));
    }
    private PartnerAdvanceScheduleService.Schedule save(long id, PartnerAdvanceScheduleService.RowRequest... rows) {
        return schedules.save(id, new PartnerAdvanceScheduleService.Request(List.of(rows), false));
    }
    private static PartnerAdvanceScheduleService.RowRequest pct(Long id, String label, String percentage, LocalDate due) {
        return new PartnerAdvanceScheduleService.RowRequest(id, label, new BigDecimal(percentage), null, due);
    }
    private record Fixture(PurchaseOrder purchase, long partnerId, long productId) {}
    private Fixture fixture(String totalCost, int quantity, String financingPct) {
        var partner = customers.create(new Customer(null, "Schedule partner", "Finance", null, null, "BE0000000000", "BE", Language.NL,
                "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Schedule supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal(financingPct), new BigDecimal("50")));
        ProductEntity product = new ProductEntity(); product.sku = "FIN-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = quantity;
        line.exwPrice = new BigDecimal(totalCost).divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP);
        line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line); em.flush(); em.clear();
        assertEquals(amount(totalCost), purchases.reconciliation(purchase.id()).totals().forecastExternalEur());
        return new Fixture(purchases.get(purchase.id()), partner.id(), product.id);
    }
    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }
}
