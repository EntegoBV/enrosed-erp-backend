package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class LegacyPartnerQuoteDraftsTest {
    @Inject LegacyPartnerQuoteDrafts drafts;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceSchedules plans;
    @Inject PartnerAdvanceQuotes snapshots;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject IncomingPaymentService incoming;
    @Inject IncomingPayments payments;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    @Test @TestTransaction
    void untouchedThirtySeventyQuoteBecomesTwoConceptInvoicesWithoutRenumberingItsHistory() {
        var f = fixture(true);
        var before = sales.get(f.quoteId());
        assertTrue(drafts.preview(f.quoteId()).eligible());
        var result = drafts.convert(f.quoteId());
        em.flush(); em.clear();

        assertEquals(2, result.invoices().size());
        assertEquals(List.of(money("22180.13"), money("51753.62")), result.invoices().stream()
                .map(invoice -> sales.price(sales.get(invoice.id())).totals().total()).toList());
        for (var item : result.invoices()) {
            var invoice = sales.get(item.id());
            assertEquals(QuoteStatus.CONCEPT, invoice.status());
            assertEquals(DocumentType.FACTUUR, invoice.docType());
            assertEquals(before.id(), invoice.sourceQuoteId());
            assertEquals(before.notes(), invoice.notes());
            assertNull(invoice.sentAt());
            assertNull(invoice.portalToken());
            assertFalse(incoming.hasHistory(invoice.id()));
        }
        var archived = sales.get(before.id());
        assertTrue(archived.isArchived());
        assertEquals(before.number(), archived.number());
        assertEquals(DocumentType.OFFERTE, archived.docType());
        assertEquals(before.notes(), archived.notes());
        assertEquals(money("73933.75"), snapshots.find(before.id()).agreedAmountEur());
        assertTrue(events.findByOrder(before.id()).stream().anyMatch(event -> event.type() == QuoteEvent.Type.GEFACTUREERD));
        assertTrue(drafts.preview(before.id()).alreadyConverted());
        assertEquals(result.invoices(), drafts.convert(before.id()).invoices());
        assertEquals(2, sourceInvoices(before.id()).size(), "a restart or retry never creates a third invoice");
        assertEquals(result.invoices().stream().map(LegacyPartnerQuoteDrafts.Invoice::id).toList(),
                plans.rows(f.purchaseId()).stream().map(PartnerAdvanceSchedules.Row::invoiceId).toList());
    }

    @Test @TestTransaction
    void existingIssuedTermAndItsReceiptAreReusedUnchanged() {
        var f = fixture(true);
        var first = schedules.createInvoice(f.purchaseId(), plans.rows(f.purchaseId()).getFirst().id(), f.quoteId());
        sales.issueInvoice(first.id());
        incoming.add(first.id(), new IncomingPaymentService.Request(money("100"), Instant.now().minusSeconds(60),
                "Europe/Brussels", "Reeds ontvangen"));
        var original = sales.get(first.id());
        var originalPayments = incoming.forOrder(first.id());

        var result = drafts.convert(f.quoteId());
        assertEquals(first.id(), result.invoices().getFirst().id());
        assertEquals(original, sales.get(first.id()));
        assertEquals(originalPayments, incoming.forOrder(first.id()));
        assertEquals(2, sourceInvoices(f.quoteId()).size());
        assertEquals(money("100"), incoming.summary(sales.get(first.id()), sales.price(sales.get(first.id()))).receivedEur());
    }

    @Test @TestTransaction
    void changedPlanningIsNotRewrittenToMatchAnOldQuote() {
        var f = fixture(true);
        var rows = plans.rows(f.purchaseId());
        schedules.save(f.purchaseId(), new PartnerAdvanceScheduleService.Request(List.of(
                pct(rows.getFirst().id(), "Start", "40"), pct(rows.getLast().id(), "Klaar", "60")), false));
        var preview = drafts.preview(f.quoteId());
        assertFalse(preview.eligible());
        assertTrue(preview.reason().contains("verschilt"));
        assertFalse(sales.get(f.quoteId()).isArchived());
        assertTrue(sourceInvoices(f.quoteId()).isEmpty());
        assertEquals(money("22180.13"), snapshots.find(f.quoteId()).rows().getFirst().amountEur());
    }

    @Test @TestTransaction
    void restoredConceptWithPastCustomerOrPaymentActivityIsNeverAutomaticallyConverted() {
        var f = fixture(true);
        events.add(new QuoteEvent(null, f.quoteId(), QuoteEvent.Type.VERSTUURD, Instant.now(), "emre", false,
                "Historisch verstuurd", null));
        assertFalse(drafts.preview(f.quoteId()).eligible(), "a current CONCEPT status does not erase a prior sent quote");
        var second = fixture(true);
        var payment = payments.save(new SalesPayment(null, second.quoteId(), money("10"), Instant.now(),
                "Europe/Brussels", "Historische ontvangst", Instant.now(), "emre", false));
        payments.voidPayment(payment.id());
        assertFalse(drafts.preview(second.quoteId()).eligible(), "voided receipts still prohibit an automatic history rewrite");
        var third = fixture(true);
        var entity = em.find(SalesOrderEntity.class, third.quoteId());
        entity.portalToken = "old-customer-link";
        em.flush(); em.clear();
        assertFalse(drafts.preview(third.quoteId()).eligible());
    }

    @Test @TestTransaction
    void legacyUnplannedQuoteUsesOneFullConceptInvoiceAndPreservesItsAmount() {
        var f = fixture(false);
        var source = sales.get(f.quoteId());
        var total = sales.price(source).totals().total();
        var result = drafts.convert(source.id());
        assertEquals(1, result.invoices().size());
        assertEquals(total, sales.price(sales.get(result.invoices().getFirst().id())).totals().total());
        assertTrue(sales.get(source.id()).isArchived());
        assertEquals(result.invoices(), drafts.convert(source.id()).invoices());
    }

    @Test @TestTransaction
    void unplannedQuoteCannotDuplicateAnExistingUnlinkedAdvanceEvenBelowTheFinancingLimit() {
        var f = fixture(false);
        var source = sales.get(f.quoteId());
        sales.update(source.id(), source.withExtraLines(List.of(
                new SalesExtraLine("Eerste voorschot", BigDecimal.ONE, money("22180.13")))));
        var existing = sales.create(source.customerId(), "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, existing.id());
        stored.partnerPurchaseOrderId = f.purchaseId(); stored.sourcePurchaseOrderId = f.purchaseId();
        stored.partnerSharePct = money("50"); stored.purpose = SalesPurpose.PARTNER_ADVANCE;
        stored.paymentPlan = SalesPaymentPlan.FULL; stored.salesChannel = "PARTNER";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED; stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        em.flush(); em.clear();
        sales.update(existing.id(), sales.get(existing.id()).withExtraLines(List.of(
                new SalesExtraLine("Reeds opgemaakte eerste termijn", BigDecimal.ONE, money("22180.13")))));
        assertNull(sales.get(existing.id()).sourceQuoteId());

        var preview = drafts.preview(source.id());
        assertFalse(preview.eligible());
        assertTrue(preview.reason().contains("zonder koppeling"));
        assertTrue(sourceInvoices(source.id()).isEmpty());
        assertFalse(sales.get(source.id()).isArchived());
        assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(source.id()),
                "two 30-percent claims fit below the cap but would duplicate the same intended advance");
    }

    @Test
    void failureOnSecondTermRollsBackFirstTermAndCanBeRetried() {
        var f = QuarkusTransaction.requiringNew().call(() -> fixture(true));
        var planned = plans.rows(f.purchaseId());
        var failing = mock(PartnerAdvanceScheduleService.class);
        when(failing.createInvoice(eq(f.purchaseId()), anyLong(), eq(f.quoteId()))).thenAnswer(invocation -> {
            long rowId = invocation.getArgument(1);
            if (rowId == planned.getLast().id()) throw new BusinessRuleException("Tijdelijke fout tijdens tweede termijn");
            return schedules.createInvoice(f.purchaseId(), rowId, f.quoteId());
        });
        var underTest = new LegacyPartnerQuoteDrafts();
        underTest.sales = sales; underTest.orders = orders; underTest.events = events; underTest.purchases = purchases;
        underTest.snapshots = snapshots; underTest.plans = plans; underTest.schedules = failing; underTest.incoming = incoming;
        assertThrows(BusinessRuleException.class, () -> QuarkusTransaction.requiringNew().run(() -> underTest.convert(f.quoteId())));
        assertTrue(sourceInvoices(f.quoteId()).isEmpty());
        assertFalse(sales.get(f.quoteId()).isArchived());
        assertTrue(plans.rows(f.purchaseId()).stream().allMatch(row -> row.invoiceId() == null));
        var retried = drafts.convert(f.quoteId());
        assertEquals(2, retried.invoices().size());
        assertEquals(2, sourceInvoices(f.quoteId()).size());
    }

    private List<SalesOrder> sourceInvoices(long quoteId) {
        return sales.list().stream().filter(order -> Long.valueOf(quoteId).equals(order.sourceQuoteId())).toList();
    }

    private record Fixture(long quoteId, long purchaseId) {}

    /** Historical records are built directly, independently of the new invoice-only creation flow. */
    private Fixture fixture(boolean planned) {
        var partner = customers.create(new Customer(null, "Legacy draft fixture", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Legacy fixture supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), money("100"), money("50")));
        var product = new ProductEntity(); product.sku = "LEGACY-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = 1;
        line.exwPrice = money("73933.75"); line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line);
        em.flush(); em.clear();
        var quote = sales.create(partner.id(), "BE", "DAP", DocumentType.OFFERTE);
        var stored = em.find(SalesOrderEntity.class, quote.id());
        stored.number = "offerte/container/legacy-" + quote.id();
        stored.partnerPurchaseOrderId = purchase.id(); stored.sourcePurchaseOrderId = purchase.id();
        stored.partnerSharePct = money("50"); stored.purpose = SalesPurpose.PARTNER_ADVANCE;
        stored.paymentPlan = SalesPaymentPlan.FULL; stored.salesChannel = "PARTNER";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED; stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        stored.notes = "Graag levering op afspraak.";
        em.flush(); em.clear();
        if (planned) {
            var schedule = schedules.save(purchase.id(), new PartnerAdvanceScheduleService.Request(
                    List.of(pct(null, "Start", "30"), pct(null, "Klaar", "70")), false));
            snapshots.save(quote.id(), new PartnerAdvanceQuotes.Snapshot(purchase.id(), schedule.financingPct(),
                    schedule.agreedAmountEur(), money("50"), schedule.rows().stream().map(row ->
                    new PartnerAdvanceQuotes.Row(row.id(), row.label(), row.percentage(), row.amountEur(), row.dueDate())).toList()));
        } else {
            var current = sales.get(quote.id());
            sales.update(quote.id(), current.withExtraLines(List.of(new SalesExtraLine("Volledig voorschot", BigDecimal.ONE, money("73933.75")))));
        }
        em.flush(); em.clear();
        return new Fixture(quote.id(), purchase.id());
    }

    private static PartnerAdvanceScheduleService.RowRequest pct(Long id, String label, String percentage) {
        return new PartnerAdvanceScheduleService.RowRequest(id, label, new BigDecimal(percentage), null, LocalDate.of(2026, 10, 15));
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
