package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.OtherCost;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
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
@TestSecurity(user = "emre", roles = "admin")
class PartnerAdvancePricingBasisTest {
    @Inject SalesOrderService sales;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceSchedules agreements;
    @Inject PartnerFinancingService financing;
    @Inject PartnerAdvanceContents contents;
    @Inject PurchaseOrderService purchases;
    @Inject CustomerService customers;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void fullFundingUsesTheExactPurchaseTotalIncludingEnrosedAndSeparateCosts() throws Exception {
        var f = fixture("100", Allocation.SEPARATE, false);
        var costing = purchases.calculate(purchases.get(f.purchaseId()));
        assertEquals(money("54435.82"), costing.totals().totalWithSeparateCostsEur());
        assertEquals(money("53189.64"), purchases.reconciliation(f.purchaseId()).totals().forecastExternalEur());
        assertEquals(money("1246.18"), costing.totals().extraRevenueEur());
        var plan = fixedThirds(money("54435.82"));
        var first = sales.createFromPurchaseOrder(request(f, plan));
        var result = schedules.get(f.purchaseId());
        assertEquals(PartnerAdvanceBasis.Kind.PURCHASE_TOTAL_WITH_SEPARATE_COSTS, result.financingBasis());
        assertEquals(money("54435.82"), result.financingBasisEur());
        assertEquals(money("54435.82"), result.agreedAmountEur());
        assertEquals(List.of(money("18145.27"), money("36290.55")), result.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        assertEquals(result.rows().getFirst().invoiceId(), first.id());
        assertEquals(money("54435.82"), result.rows().stream().map(row -> sales.price(sales.get(row.invoiceId())).totals().total()).reduce(money("0"), BigDecimal::add));
        assertEquals(money("54435.82"), financing.get(f.purchaseId()).financingBasisEur());
        assertEquals(money("54435.82"), financing.get(f.purchaseId()).committedAdvanceEur());
        assertEquals(money("53189.64"), financing.get(f.purchaseId()).forecastExternalEur(), "external cost accounting retains its own meaning");
        var wire = json.readTree(json.writeValueAsString(result));
        assertEquals("PURCHASE_TOTAL_WITH_SEPARATE_COSTS", wire.path("financingBasis").asText());
        assertTrue(wire.has("financingBasisEur"), wire.toString());
        assertEquals(0, money("54435.82").compareTo(wire.path("financingBasisEur").decimalValue()));
        assertEquals(first.id(), sales.createFromPurchaseOrder(request(f, plan)).id());
    }

    @Test @TestTransaction
    void noScheduleUsesOneExactClaimAndNeverReconstructsItFromRoundedUnitPrices() {
        var f = fixture("100", Allocation.SEPARATE, false);
        var invoice = sales.createFromPurchaseOrder(request(f, null));
        assertTrue(invoice.lines().isEmpty());
        assertEquals(1, invoice.extraLines().size());
        assertEquals(money("54435.82"), invoice.extraLines().getFirst().total());
        assertEquals(money("54435.82"), sales.price(invoice).totals().total());
        var roundedUnit = money("54435.82").divide(new BigDecimal("8460"), 3, RoundingMode.HALF_UP);
        assertNotEquals(money("54435.82"), Money.money(roundedUnit.multiply(new BigDecimal("8460"))),
                "a displayed per-piece price is not the invoice's calculation source");
        assertEquals(8460, contents.find(invoice).orElseThrow().totals().pieces());
        assertNull(invoice.sentAt()); assertNull(invoice.portalToken());
        assertEquals(QuoteStatus.CONCEPT, invoice.status());
        assertEquals(SalesPurpose.PARTNER_ADVANCE, invoice.purpose());
        var excessive = invoice.withExtraLines(List.of(new SalesExtraLine("Voorschot", BigDecimal.ONE, money("54435.83"))));
        assertThrows(BusinessRuleException.class, () -> sales.update(invoice.id(), excessive),
                "reservation cap uses the same exact agreed total");
        assertEquals(money("54435.82"), sales.price(sales.get(invoice.id())).totals().total());
    }

    @Test @TestTransaction
    void financingPercentageIsAppliedOnceBeforeTermRoundingAndTheResidualCentStaysInTheLastTerm() {
        var f = fixture("37.5", Allocation.SEPARATE, false);
        var request = new PartnerAdvanceScheduleService.Request(List.of(percent("Start", "50"), percent("Klaar", "50")), false);
        sales.createFromPurchaseOrder(request(f, request));
        var plan = schedules.get(f.purchaseId());
        assertEquals(money("20413.43"), plan.agreedAmountEur());
        assertEquals(List.of(money("10206.72"), money("10206.71")), plan.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        assertEquals(plan.agreedAmountEur(), plan.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).reduce(money("0"), BigDecimal::add));
        assertEquals(plan.agreedAmountEur(), financing.get(f.purchaseId()).committedAdvanceEur());
        assertEquals(money("54435.82"), plan.financingBasisEur());
    }

    @Test @TestTransaction
    void inspectionAndNamedCostsAreFinancedExactlyOnceWhetherSeparateOrSpreadIntoPieces() {
        for (Allocation allocation : List.of(Allocation.SEPARATE, Allocation.VALUE)) {
            var f = fixture("100", allocation, true);
            var costing = purchases.calculate(purchases.get(f.purchaseId()));
            assertEquals(money("620.00"), costing.totals().separateCostsEur());
            assertEquals(money("54546.82"), costing.totals().totalWithSeparateCostsEur());
            var invoice = sales.createFromPurchaseOrder(request(f, null));
            assertEquals(money("54546.82"), sales.price(invoice).totals().total());
            assertEquals(1, invoice.extraLines().size(), "no duplicate separate-cost or large rounding line");
            assertEquals(money("54546.82"), schedules.get(f.purchaseId()).financingBasisEur());
        }
    }

    @Test @TestTransaction
    void savedHistoricalAgreementsRemainFrozenUntilAnExplicitSafeCorrection() {
        var f = fixture("100", Allocation.SEPARATE, false);
        agreements.save(new PartnerAdvanceSchedules.Agreement(f.purchaseId(), f.customerId(), money("53189.64"),
                new BigDecimal("100"), money("53189.64")));
        var saved = schedules.get(f.purchaseId());
        assertEquals(PartnerAdvanceBasis.Kind.EXTERNAL_FORECAST, saved.financingBasis());
        assertEquals(money("53189.64"), saved.financingBasisEur());
        assertEquals(money("53189.64"), saved.agreedAmountEur());
        assertEquals(money("54435.82"), PartnerAdvanceBasis.total(purchases.calculate(purchases.get(f.purchaseId()))));
        assertEquals(money("53189.64"), agreements.find(f.purchaseId()).agreedAmountEur(), "a read must not rewrite prior financial agreements");
        var changed = schedules.save(f.purchaseId(), new PartnerAdvanceScheduleService.Request(List.of(percent("Volledig", "100")), true));
        assertEquals(PartnerAdvanceBasis.Kind.PURCHASE_TOTAL_WITH_SEPARATE_COSTS, changed.financingBasis());
        assertEquals(money("54435.82"), changed.agreedAmountEur());
    }

    @Test @TestTransaction
    void deletingAllUnusedLegacyInvoicesThenExplicitlyRecreatingUsesTheNewTotal() {
        var f = fixture("100", Allocation.SEPARATE, false);
        var old = legacyTermInvoices(f);
        var rowIds = agreements.rows(f.purchaseId()).stream().map(PartnerAdvanceSchedules.Row::id).toList();
        for (var invoice : old) sales.delete(invoice.id());
        assertTrue(agreements.rows(f.purchaseId()).stream().allMatch(row -> row.invoiceId() == null));
        assertEquals(money("53189.64"), schedules.get(f.purchaseId()).agreedAmountEur(), "deletion and reads do not reprice documents or agreements");

        var first = sales.createFromPurchaseOrder(request(f, null));
        var recreated = schedules.get(f.purchaseId());
        assertEquals(money("54435.82"), recreated.agreedAmountEur());
        assertEquals(PartnerAdvanceBasis.Kind.PURCHASE_TOTAL_WITH_SEPARATE_COSTS, recreated.financingBasis());
        assertEquals(rowIds, recreated.rows().stream().map(PartnerAdvanceScheduleService.Row::id).toList());
        assertEquals(List.of(money("18145.27"), money("36290.55")), recreated.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        assertTrue(recreated.rows().stream().allMatch(row -> old.stream().noneMatch(previous -> previous.id().equals(row.invoiceId()))));
        assertEquals(recreated.rows().getFirst().invoiceId(), first.id());
        assertEquals(8460, contents.find(first).orElseThrow().totals().pieces());
        for (var row : recreated.rows()) {
            var invoice = sales.get(row.invoiceId());
            assertEquals(QuoteStatus.CONCEPT, invoice.status()); assertNull(invoice.sentAt());
        }
    }

    @Test @TestTransaction
    void oneRemainingLegacyInvoiceBlocksCreatingAMissingTermWithoutChangingTheExistingDocument() {
        var f = fixture("100", Allocation.SEPARATE, false);
        var old = legacyTermInvoices(f);
        var retained = sales.get(old.getFirst().id());
        sales.delete(old.getLast().id());
        var missing = agreements.rows(f.purchaseId()).getLast();
        assertThrows(BusinessRuleException.class, () -> sales.createFromPurchaseOrder(request(f, null)));
        assertThrows(BusinessRuleException.class, () -> schedules.createInvoice(f.purchaseId(), missing.id()));
        assertEquals(retained, sales.get(retained.id()));
        assertNull(agreements.rows(f.purchaseId()).getLast().invoiceId());
        assertEquals(money("53189.64"), schedules.get(f.purchaseId()).agreedAmountEur());
    }

    @Test @TestTransaction
    void customFixedAmountsAreNotSilentlyIncreasedWhenRecreatingAnUnusedLegacyPlan() {
        var f = fixture("100", Allocation.SEPARATE, false);
        agreements.save(new PartnerAdvanceSchedules.Agreement(f.purchaseId(), f.customerId(), money("53189.64"),
                new BigDecimal("100"), money("53189.64")));
        var first = agreements.save(new PartnerAdvanceSchedules.Row(null, f.purchaseId(), 0, "Vast afgesproken voorschot", null,
                money("20000"), LocalDate.of(2026, 10, 1), null));
        var last = agreements.save(new PartnerAdvanceSchedules.Row(null, f.purchaseId(), 1, "Vast resterend voorschot", null,
                money("33189.64"), LocalDate.of(2026, 11, 1), null));
        assertThrows(BusinessRuleException.class, () -> sales.createFromPurchaseOrder(request(f, null)));
        assertThrows(BusinessRuleException.class, () -> schedules.createInvoice(f.purchaseId(), first.id()));
        assertEquals(money("33189.64"), agreements.rows(f.purchaseId()).getLast().amountEur());
        assertEquals(money("53189.64"), agreements.find(f.purchaseId()).agreedAmountEur());

        var explicitlyRevised = new PartnerAdvanceScheduleService.Request(List.of(
                new PartnerAdvanceScheduleService.RowRequest(first.id(), first.label(), null, money("20000"), first.dueDate()),
                new PartnerAdvanceScheduleService.RowRequest(last.id(), last.label(), null, money("34435.82"), last.dueDate())), true);
        sales.createFromPurchaseOrder(request(f, explicitlyRevised));
        assertEquals(List.of(money("20000"), money("34435.82")), schedules.get(f.purchaseId()).rows().stream()
                .map(PartnerAdvanceScheduleService.Row::amountEur).toList());
    }

    @Test @TestTransaction
    void anUnusedLegacyAgreementWithoutRowsGetsANewCapOnlyOnExplicitFullInvoiceCreation() {
        var f = fixture("100", Allocation.SEPARATE, false);
        agreements.save(new PartnerAdvanceSchedules.Agreement(f.purchaseId(), f.customerId(), money("53189.64"),
                new BigDecimal("100"), money("53189.64")));
        assertEquals(money("53189.64"), schedules.get(f.purchaseId()).agreedAmountEur());
        var invoice = sales.createFromPurchaseOrder(request(f, null));
        assertEquals(money("54435.82"), sales.price(invoice).totals().total());
        assertEquals(money("54435.82"), schedules.get(f.purchaseId()).agreedAmountEur());
        assertTrue(schedules.get(f.purchaseId()).rows().isEmpty());
    }

    @Test @TestTransaction
    void cachedConceptCannotOverwriteADocumentWhoseIssuedStatusChangedInTheDatabase() {
        var f = fixture("100", Allocation.SEPARATE, false);
        var invoice = sales.createFromPurchaseOrder(request(f, null));
        var cached = em.find(SalesOrderEntity.class, invoice.id());
        assertEquals(QuoteStatus.CONCEPT, cached.status);
        // Reproduce the persistence-context state after another transaction commits while this request waits.
        em.createNativeQuery("update sales_order set status='UITGEREIKT' where id=:id")
                .setParameter("id", invoice.id()).executeUpdate();
        assertEquals(QuoteStatus.CONCEPT, cached.status, "the already loaded entity is deliberately stale");
        assertThrows(BusinessRuleException.class, () -> sales.update(invoice.id(), invoice));
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(invoice.id()).status());
        assertEquals(money("54435.82"), sales.price(sales.get(invoice.id())).totals().total());
        assertNull(sales.get(invoice.id()).sentAt());
    }

    private List<SalesOrder> legacyTermInvoices(Fixture f) {
        var oldTotal = money("53189.64");
        agreements.save(new PartnerAdvanceSchedules.Agreement(f.purchaseId(), f.customerId(), oldTotal, new BigDecimal("100"), oldTotal));
        var input = fixedThirds(oldTotal).rows();
        var rows = new java.util.ArrayList<PartnerAdvanceSchedules.Row>();
        for (int i = 0; i < input.size(); i++) {
            var row = input.get(i);
            rows.add(agreements.save(new PartnerAdvanceSchedules.Row(null, f.purchaseId(), i, row.label(), null,
                    row.amountEur(), row.dueDate(), null)));
        }
        var invoices = new java.util.ArrayList<SalesOrder>();
        for (var row : rows) {
            var invoice = sales.createScheduledPartnerAdvance(purchases.get(f.purchaseId()), row.id(), row.label(), row.amountEur(), row.dueDate(), null);
            agreements.save(new PartnerAdvanceSchedules.Row(row.id(), row.purchaseOrderId(), row.position(), row.label(), null,
                    row.amountEur(), row.dueDate(), invoice.id()));
            invoices.add(invoice);
        }
        return List.copyOf(invoices);
    }

    private static PartnerAdvanceScheduleService.RowRequest percent(String label, String percentage) {
        return new PartnerAdvanceScheduleService.RowRequest(null, label, new BigDecimal(percentage), null, LocalDate.of(2026, 11, 1));
    }
    private static PartnerAdvanceScheduleService.Request fixedThirds(BigDecimal total) {
        var first = total.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
        return new PartnerAdvanceScheduleService.Request(List.of(
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij start productie", null, first, LocalDate.of(2026, 10, 1)),
                new PartnerAdvanceScheduleService.RowRequest(null, "2/3 na productie", null, total.subtract(first), LocalDate.of(2026, 11, 1))), false);
    }
    private static SalesOrderService.FromPurchaseOrderRequest request(Fixture f, PartnerAdvanceScheduleService.Request plan) {
        return new SalesOrderService.FromPurchaseOrderRequest(f.purchaseId(), f.customerId(), "COST", BigDecimal.ZERO,
                true, new BigDecimal("50"), f.financingPct(), false, List.of(), "PARTNER", null,
                SalesPurpose.PARTNER_ADVANCE, SalesPaymentPlan.FULL, plan);
    }
    private record Fixture(long purchaseId, long customerId, BigDecimal financingPct) {}
    private Fixture fixture(String financingPct, Allocation allocation, boolean namedCosts) {
        var partner = customers.create(new Customer(null, "Purchase total partner", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Pricing basis supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal(financingPct), new BigDecimal("50")));
        var product = new ProductEntity(); product.sku = "BASIS-" + UUID.randomUUID(); product.name = "Preserved rose display";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 24;
        em.persist(product); em.flush();
        var stored = em.find(PurchaseOrderEntity.class, purchase.id());
        stored.freightUsd = BigDecimal.ZERO; stored.originCosts = BigDecimal.ZERO; stored.destinationCostsEur = BigDecimal.ZERO;
        stored.defaultDutyRatePct = BigDecimal.ZERO; stored.extraRevenueEur = money("1246.18");
        stored.inspectionCostEur = money("509"); stored.allocSeparate = allocation;
        stored.otherCostsJson = namedCosts ? "[{\"label\":\"Certificate\",\"amountEur\":34},{\"label\":\"Samples\",\"amountEur\":77}]" : null;
        var line = new PurchaseOrderLineEntity(); line.order = stored; line.productId = product.id;
        line.quantity = 8460; line.exwPrice = new BigDecimal("6.227026"); line.exwCurrency = Currency.EUR;
        stored.lines.add(line); em.persist(line); em.flush(); em.clear();
        return new Fixture(purchase.id(), partner.id(), new BigDecimal(financingPct));
    }
    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
