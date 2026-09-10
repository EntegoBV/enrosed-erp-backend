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
@io.quarkus.test.security.TestSecurity(user = "emre", roles = "admin")
class AdvanceQuoteArrangementsTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceQuotes snapshots;
    @Inject SalesOrderService sales;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject be.enrosed.sales.adapter.in.rest.CustomerQuoteMapper customerQuotes;
    @Inject be.enrosed.sales.adapter.in.rest.SalesOrderResource salesResource;
    @Inject be.enrosed.sales.adapter.in.rest.PortalResource portal;
    @Inject EntityManager em;
    @Inject com.fasterxml.jackson.databind.ObjectMapper json;

    @Test @TestTransaction
    void quoteFreezesThirtySeventyArrangementsAndEachInvoiceUsesOnlyItsOwnTerm() {
        var f = fixture("12000", 12, "50");
        var quote = quotation(f, "50", plan(pct(null, "Bij start", "30"), pct(null, "Productie klaar", "70")));
        em.flush(); em.clear();
        var snapshot = snapshots.find(quote.id());
        assertEquals(List.of(amount("1800"), amount("4200")), snapshot.rows().stream().map(PartnerAdvanceQuotes.Row::amountEur).toList());
        assertEquals(amount("6000"), snapshot.agreedAmountEur());
        assertEquals(SalesPaymentPlan.FULL, quote.paymentPlan());
        assertEquals(amount("0"), schedules.get(f.purchase.id()).reservedOutsideScheduleEur());
        assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id()));
        assertThrows(BusinessRuleException.class, () -> sales.duplicate(quote.id()));
        var first = schedules.createInvoice(f.purchase.id(), snapshot.rows().getFirst().scheduleRowId());
        assertFalse(sales.get(quote.id()).isArchived(), "one advance term is not a conversion of the full agreement");
        var last = schedules.createInvoice(f.purchase.id(), snapshot.rows().getLast().scheduleRowId());
        assertEquals(quote.id(), first.sourceQuoteId()); assertEquals(quote.id(), last.sourceQuoteId());
        assertEquals(amount("1800"), sales.price(first).totals().total());
        assertEquals(amount("4200"), sales.price(last).totals().total());
        assertEquals(first.id(), schedules.createInvoice(f.purchase.id(), snapshot.rows().getFirst().scheduleRowId()).id());
        assertEquals(SalesPaymentPlan.FULL, first.paymentPlan());
        assertTrue(first.notes() == null || first.notes().isBlank());
        assertFalse(first.extraLines().getFirst().description().toLowerCase().contains("partner"));
        assertNull(salesResource.get(quote.id()).invoicedAsId(), "one term must not mark the entire arrangement as invoiced");
        assertEquals(quote.number(), salesResource.get(first.id()).sourceQuoteNumber());
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(first.id()).status());
        assertThrows(BusinessRuleException.class, () -> sales.delete(quote.id()), "retain the source agreement for term invoices");
    }

    @Test @TestTransaction
    void changingThePlanKeepsLegacyQuoteFrozenButNoLongerRequiresAnotherQuote() {
        var f = fixture("12000", 12, "50");
        schedules.save(f.purchase.id(), plan(pct(null, "Start", "30"), pct(null, "Klaar", "70")));
        var quote = quotation(f, "50", null);
        var frozen = snapshots.find(quote.id());
        schedules.save(f.purchase.id(), plan(pct(frozen.rows().getFirst().scheduleRowId(), "Start", "40"),
                pct(frozen.rows().getLast().scheduleRowId(), "Klaar", "60")));
        em.flush(); em.clear();
        assertEquals(amount("1800"), snapshots.find(quote.id()).rows().getFirst().amountEur());
        var invoice = schedules.createInvoice(f.purchase.id(), frozen.rows().getFirst().scheduleRowId());
        assertNull(invoice.sourceQuoteId(), "a changed plan must not be attributed to the old frozen quote");
        assertEquals(amount("2400"), sales.price(invoice).totals().total());
        assertEquals(amount("1800"), snapshots.find(quote.id()).rows().getFirst().amountEur());
    }

    @Test @TestTransaction
    void customerProjectionHasFrozenTermsAndQuantitiesWithoutAFalseFinalPrice() {
        var f = fixture("12000", 12, "50");
        var quote = quotation(f, "50", plan(pct(null, "Start", "30"), pct(null, "Klaar", "70")));
        var customer = customerQuotes.portal(quote, "NL");
        assertEquals(12, customer.totals().pieces());
        assertNull(customer.totals().total()); assertNull(customer.totals().totalInclVat());
        assertNull(customer.totals().subtotal()); assertNull(customer.totals().goodsTotal());
        assertNull(customer.lines().getFirst().unitPrice()); assertNull(customer.lines().getFirst().net());
        assertTrue(customer.extraLines().isEmpty());
        assertEquals(amount("1800"), customer.advanceAgreement().rows().getFirst().amountEur());
        assertEquals(new BigDecimal("50"), customer.advanceAgreement().sharePct().stripTrailingZeros().setScale(0));
        assertNotNull(salesResource.get(quote.id()).advanceAgreement());
        String token = "agreement-" + UUID.randomUUID();
        var entity = em.find(SalesOrderEntity.class, quote.id()); entity.portalToken = token;
        entity.status = QuoteStatus.VERZONDEN; entity.sentAt = java.time.Instant.now(); em.flush(); em.clear();
        assertTrue(portal.catalog(token, "NL").isEmpty(), "a fixed arrangement has no regular per-piece catalog to add to it");
    }

    @Test @TestTransaction
    void zeroPercentFinancingAllowsAQuoteWithoutAdvanceInvoicesAndCanBeSent() {
        var f = fixture("12000", 12, "0");
        var quote = quotation(f, "0", plan());
        assertEquals(amount("0"), snapshots.find(quote.id()).agreedAmountEur());
        assertTrue(snapshots.find(quote.id()).rows().isEmpty());
        assertDoesNotThrow(() -> sales.validateForSend(quote));
        assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id()));
    }

    @Test @TestTransaction
    void financingChangeRecalculatesUnbilledPlanWhileOldQuotedAmountsStayFrozen() {
        var f = fixture("12000", 12, "50");
        var initial = quotation(f, "50", plan(pct(null, "Start", "30"), pct(null, "Klaar", "70")));
        var oldRows = snapshots.find(initial.id()).rows();
        var changed = quotation(f, "100", plan(pct(oldRows.getFirst().scheduleRowId(), "Start", "30"), pct(oldRows.getLast().scheduleRowId(), "Klaar", "70")));
        assertEquals(amount("12000"), snapshots.find(changed.id()).agreedAmountEur());
        assertEquals(amount("3600"), snapshots.find(changed.id()).rows().getFirst().amountEur());
        assertEquals(amount("6000"), snapshots.find(initial.id()).agreedAmountEur());
        var first = schedules.createInvoice(f.purchase.id(), oldRows.getFirst().scheduleRowId());
        assertEquals(changed.id(), first.sourceQuoteId());
        assertThrows(BusinessRuleException.class, () -> quotation(f, "50", plan(pct(oldRows.getFirst().scheduleRowId(), "Start", "30"), pct(oldRows.getLast().scheduleRowId(), "Klaar", "70"))));
    }

    @Test @TestTransaction
    void arrangementFinancialEditsAreBlockedButNotesAndDeletionKeepWorking() throws Exception {
        var f = fixture("12000", 12, "50");
        var quote = quotation(f, "50", plan(pct(null, "Start", "30"), pct(null, "Klaar", "70")));
        assertDoesNotThrow(() -> sales.update(quote.id(), sales.get(quote.id())));
        com.fasterxml.jackson.databind.node.ObjectNode request = json.valueToTree(sales.get(quote.id()));
        request.put("notes", "Levering op afspraak.");
        for (var line : request.withArray("lines")) {
            var fields = (com.fasterxml.jackson.databind.node.ObjectNode) line;
            fields.put("unitPriceEur", fields.get("unitPriceEur").decimalValue().stripTrailingZeros());
            fields.put("unitCostEur", fields.get("unitCostEur").decimalValue().stripTrailingZeros());
        }
        assertEquals("Levering op afspraak.", sales.update(quote.id(), json.treeToValue(request, SalesOrder.class)).notes(),
                "JSON number normalization must not be mistaken for a financial change");
        assertThrows(BusinessRuleException.class, () -> sales.update(quote.id(), quote.withExtraLines(List.of(new SalesExtraLine("Extra", BigDecimal.ONE, BigDecimal.TEN)))));
        assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(quote.id(), new SalesOrderService.PartnerDealRequest(null, null, null)));
        assertThrows(BusinessRuleException.class, () -> sales.updateFreight(quote.id(), FreightState.AANGEVULD, BigDecimal.TEN, FreightPricingStrategy.FIXED, null));
        sales.delete(quote.id()); em.flush(); em.clear();
        assertNull(snapshots.find(quote.id()));
        assertEquals(2, schedules.get(f.purchase.id()).rows().size(), "deleting a quote does not silently remove the purchase plan");
    }

    @Test @TestTransaction
    void newNeutralNumbersContinueLegacySequenceWithoutRenumberingIssuedDocuments() {
        var f = fixture("12000", 12, "50");
        var firstQuote = quotation(f, "50", plan(pct(null, "Start", "30"), pct(null, "Klaar", "70")));
        int year = LocalDate.now().getYear();
        String oldQuoteNumber = "offerte/partner/" + year + "/900047";
        em.find(SalesOrderEntity.class, firstQuote.id()).number = oldQuoteNumber;
        em.flush(); em.clear();
        var rows = snapshots.find(firstQuote.id()).rows();
        var firstInvoice = schedules.createInvoice(f.purchase.id(), rows.getFirst().scheduleRowId());
        String oldInvoiceNumber = "partner/" + year + "/900057";
        em.find(SalesOrderEntity.class, firstInvoice.id()).number = oldInvoiceNumber;
        em.flush(); em.clear();
        var lastInvoice = schedules.createInvoice(f.purchase.id(), rows.getLast().scheduleRowId());
        assertEquals("container/" + year + "/900058", lastInvoice.number());
        assertEquals(oldQuoteNumber, sales.get(firstQuote.id()).number());
        assertEquals(oldInvoiceNumber, sales.get(firstInvoice.id()).number());
    }

    @Test @TestTransaction
    void legacyUnplannedQuoteAndOrdinarySaleKeepTheirExistingConversionBehavior() {
        var f = fixture("12000", 12, "50");
        var legacy = quotation(f, "50", null);
        assertNull(snapshots.find(legacy.id()));
        assertEquals(amount("6000"), sales.price(sales.createInvoiceFrom(legacy.id())).totals().total());
        assertTrue(sales.get(legacy.id()).isArchived());
        var regular = sales.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(f.purchase.id(), f.partnerId,
                "COST", BigDecimal.ZERO, false, null, null, false, List.of(), null, null, SalesPurpose.STANDARD, SalesPaymentPlan.FULL));
        assertNull(snapshots.find(regular.id()));
        assertNotNull(customerQuotes.portal(regular, "NL").totals().total());
        var invoice = sales.createInvoiceFrom(regular.id());
        em.flush(); em.clear();
        var archived = sales.get(regular.id());
        assertNotNull(invoice.id());
        assertTrue(archived.isArchived());
        assertEquals(QuoteStatus.CONCEPT, archived.status());
        assertNull(archived.sentAt());
        assertEquals(QuoteStatus.CONCEPT, invoice.status());
        assertNull(invoice.sentAt());
        assertNull(invoice.portalToken());
        assertEquals(regular.id(), invoice.sourceQuoteId());
        assertEquals(invoice.id(), sales.createInvoiceFrom(regular.id()).id());
        assertEquals(1, sales.list().stream().filter(order -> regular.id().equals(order.sourceQuoteId())).count());
    }

    @Test
    void incompleteTermsRollBackBothTheQuoteAndTheSavedPlan() {
        var purchaseId = new java.util.concurrent.atomic.AtomicReference<Long>();
        assertThrows(BusinessRuleException.class, () -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            var f = fixture("12000", 12, "50"); purchaseId.set(f.purchase.id());
            quotation(f, "50", plan(pct(null, "Only first term", "30")));
        }));
        assertNull(em.find(PurchaseOrderEntity.class, purchaseId.get()), "the failed quote and all plan changes share one rollback");
    }

    private SalesOrder quotation(Fixture f, String financing, PartnerAdvanceScheduleService.Request schedule) {
        // Historical persisted fixture: the public creation path now produces invoices only.
        var purchase = purchases.get(f.purchase.id());
        boolean financingChanged = purchase.partnerCostPctOrDefault().compareTo(new BigDecimal(financing)) != 0;
        if (financingChanged) {
            purchase = purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(f.partnerId,
                    new BigDecimal(financing), new BigDecimal("50")));
            if (schedule != null) schedule = new PartnerAdvanceScheduleService.Request(schedule.rows(), true);
        }
        PartnerAdvanceQuotes.Snapshot snapshot = schedule != null || schedules.hasRows(purchase.id())
                ? schedules.quoteArrangements(purchase.id(), schedule, new BigDecimal("50")) : null;
        BigDecimal external = purchases.reconciliation(purchase.id()).totals().forecastExternalEur();
        BigDecimal amount = snapshot == null ? external.multiply(new BigDecimal(financing)).divide(new BigDecimal("100"))
                : snapshot.agreedAmountEur();
        var ordinary = sales.create(f.partnerId, "BE", "DAP");
        var entity = em.find(SalesOrderEntity.class, ordinary.id());
        entity.number = "offerte/container/" + LocalDate.now().getYear() + "/" + ordinary.id();
        entity.partnerPurchaseOrderId = purchase.id(); entity.sourcePurchaseOrderId = purchase.id();
        entity.partnerSharePct = new BigDecimal("50"); entity.salesChannel = "PARTNER";
        entity.purpose = SalesPurpose.PARTNER_ADVANCE; entity.paymentPlan = SalesPaymentPlan.FULL;
        entity.freight = FreightState.AANGEVULD; entity.manualFreightEur = BigDecimal.ZERO;
        entity.freightPricingStrategy = FreightPricingStrategy.FIXED; entity.freightCarrierId = null;
        var line = new be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity();
        line.order = entity; line.productId = f.productId; line.quantity = purchase.lines().getFirst().quantity();
        line.unitPriceEur = amount.divide(BigDecimal.valueOf(line.quantity), 4, RoundingMode.HALF_UP);
        line.unitCostEur = external.divide(BigDecimal.valueOf(line.quantity), 4, RoundingMode.HALF_UP);
        entity.lines.add(line); em.persist(line); em.flush();
        if (snapshot != null) snapshots.save(entity.id, snapshot);
        em.clear();
        return sales.get(ordinary.id());
    }
    private PartnerAdvanceScheduleService.Request plan(PartnerAdvanceScheduleService.RowRequest... rows) {
        return new PartnerAdvanceScheduleService.Request(List.of(rows), false);
    }
    private PartnerAdvanceScheduleService.RowRequest pct(Long id, String label, String percentage) {
        return new PartnerAdvanceScheduleService.RowRequest(id, label, new BigDecimal(percentage), null, LocalDate.of(2026, 10, 15));
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
