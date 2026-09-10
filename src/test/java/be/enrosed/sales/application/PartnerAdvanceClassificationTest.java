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
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PartnerAdvanceClassificationTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject SalesOrderService sales;
    @Inject PartnerSettlements settlements;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService financing;
    @Inject EntityManager em;

    @Test @TestTransaction
    void fullFundingAndPaymentKeepBothThirdsAsAdvancesUntilAnExplicitAuctionResult() {
        var fixture = fixture();
        var advances = createThirds(fixture);
        assertEquals(List.of(amount("4000"), amount("8000")), advances.stream()
                .map(invoice -> sales.price(invoice).totals().total()).toList());
        for (var invoice : advances) {
            assertEquals(DocumentType.FACTUUR, invoice.docType());
            assertEquals(QuoteStatus.CONCEPT, invoice.status());
            assertEquals(SalesPaymentPlan.FULL, invoice.paymentPlan(), "each invoice claims its own term only");
            assertNull(invoice.sentAt());
            assertNull(invoice.portalToken());
            receiveInFull(invoice);
            assertEquals(QuoteStatus.BETAALD, sales.get(invoice.id()).status());
        }
        assertOnlyAdvancesWithoutResult(fixture, advances);
        var paid = financing.get(fixture.purchaseId());
        assertEquals(amount("12000"), paid.committedAdvanceEur());
        assertEquals(amount("12000"), paid.invoicedAdvanceEur());
        assertEquals(amount("0"), paid.openAdvanceEur());

        // The final cost exceeds the financing budget; the issued advance amounts stay frozen.
        purchases.addPayment(fixture.purchaseId(), LocalDate.now(), amount("12000"), Currency.EUR,
                "Supplier final payment", PurchasePayment.Payee.SUPPLIER, true);
        purchases.addPayment(fixture.purchaseId(), LocalDate.now(), amount("1000"), Currency.EUR,
                "Actual additional handling", PurchasePayment.Payee.OTHER, true);
        var costs = purchases.reconciliation(fixture.purchaseId());
        assertTrue(costs.totals().finalized());
        assertEquals(amount("12000"), costs.totals().plannedExternalEur());
        assertEquals(amount("13000"), costs.totals().forecastExternalEur());
        assertOnlyAdvancesWithoutResult(fixture, advances);

        var finalInvoice = auctionResult(fixture, amount("18000"));
        var result = settlements.find(finalInvoice.id());
        assertEquals(SalesPurpose.PARTNER_SETTLEMENT, finalInvoice.purpose());
        assertTrue(finalInvoice.partnerSettlement());
        assertTrue(result.finalSettlement());
        assertEquals(amount("13000"), result.costEur(), "use actual reconciled costs, never the submitted unit cost");
        assertEquals(amount("18000"), result.lines().getFirst().proceedsEur());
        assertEquals(amount("15500"), result.revenueEur(), "13000 cost + 50% of the 5000 auction result");
        assertEquals(amount("12000"), result.advanceEur(), "credit both issued advances exactly once");
        assertEquals(amount("3500"), sales.price(finalInvoice).totals().total());
        assertEquals(amount("0"), financing.get(fixture.purchaseId()).recognizedProfitEur(),
                "a settlement draft reserves the result but does not recognize it yet");

        sales.issueInvoice(finalInvoice.id());
        var closed = financing.get(fixture.purchaseId());
        assertTrue(closed.settlementComplete());
        assertEquals(finalInvoice.id(), closed.settlementInvoiceId());
        assertEquals(amount("15500"), closed.recognizedRevenueEur());
        assertEquals(amount("13000"), closed.recognizedCostEur());
        assertEquals(amount("2500"), closed.recognizedProfitEur(),
                "share the net result, not a percentage commission on auction proceeds");
        assertEquals(List.of(amount("4000"), amount("8000")), advances.stream()
                .map(invoice -> sales.price(sales.get(invoice.id())).totals().total()).toList());
        for (var invoice : advances) assertAdvance(sales.get(invoice.id()));
    }

    @Test @TestTransaction
    void aLossAfterFullFundingProducesASeparateCreditWithoutReclassifyingTheLastAdvance() {
        var fixture = fixture();
        var advances = createThirds(fixture);
        advances.forEach(this::receiveInFull);
        purchases.addPayment(fixture.purchaseId(), LocalDate.now(), amount("12000"), Currency.EUR,
                "Supplier final payment", PurchasePayment.Payee.SUPPLIER, true);
        assertTrue(purchases.reconciliation(fixture.purchaseId()).totals().finalized());
        assertOnlyAdvancesWithoutResult(fixture, advances);

        var finalInvoice = auctionResult(fixture, amount("8000"));
        var result = settlements.find(finalInvoice.id());
        assertEquals(SalesPurpose.PARTNER_SETTLEMENT, finalInvoice.purpose());
        assertEquals(amount("12000"), result.costEur());
        assertEquals(amount("10000"), result.revenueEur(), "12000 cost + 50% of a 4000 loss");
        assertEquals(amount("12000"), result.advanceEur());
        assertEquals(amount("-2000"), sales.price(finalInvoice).totals().total());

        var issued = sales.issueInvoice(finalInvoice.id());
        var payment = incoming.summary(issued, sales.price(issued));
        assertEquals(SalesPaymentSummary.Status.CREDIT, payment.status());
        assertEquals(amount("0"), payment.remainingEur(), "a loss credit creates no incoming payment claim");
        assertEquals(sales.price(issued).totals().totalInclVat().negate(), payment.creditEur());
        assertEquals(amount("-2000"), financing.get(fixture.purchaseId()).recognizedProfitEur());
        assertTrue(financing.get(fixture.purchaseId()).settlementComplete());
        for (var invoice : advances) assertAdvance(sales.get(invoice.id()));
    }

    @Test @TestTransaction
    void fullyPaidAdvancesDoNotSupplyAMissingAuctionResult() {
        var fixture = fixture();
        var advances = createThirds(fixture);
        advances.forEach(this::receiveInFull);
        var error = assertThrows(BusinessRuleException.class, () -> auctionResult(fixture, null));
        assertTrue(error.getMessage().contains("netto veilingopbrengst"));
        assertOnlyAdvancesWithoutResult(fixture, advances);
    }

    private List<SalesOrder> createThirds(Fixture fixture) {
        return schedules.createInvoices(fixture.purchaseId(), new PartnerAdvanceScheduleService.Request(List.of(
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij start productie",
                        new BigDecimal("33.3333"), null, LocalDate.now()),
                new PartnerAdvanceScheduleService.RowRequest(null, "2/3 na productie",
                        new BigDecimal("66.6667"), null, LocalDate.now().plusDays(30))), false));
    }

    private void receiveInFull(SalesOrder draft) {
        var issued = sales.issueInvoice(draft.id());
        incoming.add(issued.id(), new IncomingPaymentService.Request(sales.price(issued).totals().totalInclVat(),
                Instant.now().minusSeconds(60), "Europe/Brussels", "Partner advance paid"));
    }

    private void assertOnlyAdvancesWithoutResult(Fixture fixture, List<SalesOrder> advances) {
        var summary = financing.get(fixture.purchaseId());
        assertEquals(2, summary.documents().size(), "full funding must not create an automatic final invoice");
        assertNull(summary.settlementInvoiceId());
        assertFalse(summary.settlementComplete());
        assertEquals(12, summary.remainingQuantity());
        assertEquals(amount("0"), summary.recognizedRevenueEur());
        assertEquals(amount("0"), summary.recognizedCostEur());
        assertEquals(amount("0"), summary.recognizedProfitEur());
        assertTrue(sales.partnerSettlementAvailability(fixture.purchaseId()).settlements().isEmpty());
        for (var invoice : advances) assertAdvance(sales.get(invoice.id()));
    }

    private void assertAdvance(SalesOrder invoice) {
        assertEquals(SalesPurpose.PARTNER_ADVANCE, invoice.purpose());
        assertTrue(invoice.isPartnerAdvance());
        assertFalse(invoice.partnerSettlement());
        assertNull(settlements.find(invoice.id()));
        var accounting = financing.accounting(invoice, sales.price(invoice));
        assertEquals(amount("0"), accounting.recognizedRevenueEur());
        assertEquals(amount("0"), accounting.recognizedCostEur());
        assertEquals(amount("0"), accounting.recognizedProfitEur());
    }

    private SalesOrder auctionResult(Fixture fixture, BigDecimal proceeds) {
        return sales.createAuctionSettlement(new SalesOrderService.AuctionSettlementRequest(
                fixture.partnerId(), fixture.purchaseId(), null, null, BigDecimal.ZERO, new BigDecimal("50"),
                List.of(new SalesOrderService.AuctionLine(fixture.productId(), 12, proceeds,
                        new BigDecimal("99999"))), null, true));
    }

    private record Fixture(long purchaseId, long partnerId, long productId) {}

    private Fixture fixture() {
        var partner = customers.create(new Customer(null, "Advance classification partner", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Advance classification supplier", "CN", "Yiwu", null,
                null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(),
                new BigDecimal("100"), new BigDecimal("50")));
        var product = new ProductEntity();
        product.sku = "CLASSIFY-" + UUID.randomUUID();
        product.name = "Partner roses";
        product.supplierId = supplier.id();
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        product.piecesPerCarton = 1;
        em.persist(product);
        em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.status = PurchaseOrderStatus.BESTELD;
        entity.freightUsd = entity.originCosts = entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity();
        line.order = entity;
        line.productId = product.id;
        line.quantity = 12;
        line.exwPrice = new BigDecimal("1000");
        line.exwCurrency = Currency.EUR;
        entity.lines.add(line);
        em.persist(line);
        em.flush();
        em.clear();
        assertEquals(amount("12000"), purchases.reconciliation(purchase.id()).totals().forecastExternalEur());
        return new Fixture(purchase.id(), partner.id(), product.id);
    }

    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }
}
