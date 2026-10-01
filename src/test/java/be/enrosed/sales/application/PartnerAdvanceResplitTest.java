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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A partner plan of 1/3 + 2/3 whose first third is invoiced can still become
 * 1/3 · 1/3 · 1/3: issued terms stay exactly as they are, a concept (also one
 * that was issued and reopened) follows the new split in place (same number),
 * and the new split covers the whole agreed advance. Payment history or a live
 * credit note keeps even a concept fixed.
 */
@QuarkusTest
class PartnerAdvanceResplitTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject EntityManager em;

    private static final LocalDate ARRIVAL = LocalDate.of(2026, 12, 15);

    @Test @TestTransaction
    void anIssuedThirdAndAConceptTwoThirdsBecomeThreeThirdsWithTheConceptRevisedInPlace() {
        var f = fixture();
        var plan = thirds(f);
        var first = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var concept = schedules.createInvoice(f.id(), plan.rows().get(1).id());
        var before = schedules.get(f.id());
        assertTrue(before.rows().get(0).invoiceFixed(), "an issued term is fixed");
        assertFalse(before.rows().get(1).invoiceFixed(), "a never-issued concept follows a new split");

        var after = save(f, row(plan.rows().get(0), "5000"),
                new PartnerAdvanceScheduleService.RowRequest(plan.rows().get(1).id(), "1/3 na productie", null, amount("5000"), ARRIVAL),
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij aankomst", null, amount("5000"), null));

        assertEquals(List.of(amount("5000"), amount("5000"), amount("5000")),
                after.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        assertEquals(first.id(), after.rows().get(0).invoiceId());
        assertEquals(concept.id(), after.rows().get(1).invoiceId(), "the concept stays linked to its term");
        assertNull(after.rows().get(2).invoiceId(), "a new term waits for 'Conceptfactuur maken'");
        assertEquals(amount("0"), after.unallocatedEur());

        var revised = sales.get(concept.id());
        assertEquals(concept.number(), revised.number(), "same number: no gap in the container series");
        assertEquals(QuoteStatus.CONCEPT, revised.status());
        assertEquals(amount("5000"), sales.price(revised).totals().total());
        assertEquals(1, revised.extraLines().size());
        assertEquals("Voorschot · 1/3 na productie", revised.extraLines().getFirst().description());
        assertEquals(ARRIVAL, revised.invoiceDueDate());
        assertTrue(quotes.history(concept.id()).stream().anyMatch(event -> event.type() == QuoteEvent.Type.OPGEMAAKT
                && "Voorschottermijn aangepast: € 10.000,00 → € 5.000,00".equals(event.summary())));

        var issued = sales.get(first.id());
        assertEquals(QuoteStatus.UITGEREIKT, issued.status());
        assertEquals(amount("5000"), sales.price(issued).totals().total(), "the issued third is untouched");
        var third = schedules.createInvoice(f.id(), after.rows().get(2).id());
        assertEquals(amount("5000"), sales.price(third).totals().total());
    }

    @Test @TestTransaction
    void anIssuedThirdAndAnOpenTwoThirdsBecomeThreeThirds() {
        var f = fixture();
        var plan = thirds(f);
        sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var after = save(f, row(plan.rows().get(0), "5000"),
                new PartnerAdvanceScheduleService.RowRequest(plan.rows().get(1).id(), "1/3 na productie", null, amount("5000"), null),
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij aankomst", null, amount("5000"), null));
        assertEquals(3, after.rows().size());
        assertEquals(plan.rows().get(1).id(), after.rows().get(1).id(), "the open term keeps its identity");
        assertNull(after.rows().get(1).invoiceId());
        assertFalse(after.rows().get(1).invoiceFixed());
        assertEquals(amount("0"), after.unallocatedEur());
    }

    @Test @TestTransaction
    void issuedTermsStayFixedAndAnIncompleteRemainderIsRefused() {
        var f = fixture();
        var plan = thirds(f);
        sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        assertEquals("Een termijn met een factuur staat vast; verwijder eerst de ongebruikte conceptfactuur om de termijn te wijzigen",
                assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(0), "4000"),
                        row(plan.rows().get(1), "11000"))).getMessage());
        assertEquals("Verdeel het resterende voorschot volledig: nog € 7.000,00 te verdelen.",
                assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(0), "5000"),
                        row(plan.rows().get(1), "3000"))).getMessage());
        assertEquals(amount("10000"), schedules.get(f.id()).rows().get(1).amountEur(), "nothing was saved");
    }

    @Test @TestTransaction
    void aConceptTermCannotBeDroppedAndAReopenedInvoiceFollowsTheSplit() {
        var f = fixture();
        var plan = thirds(f);
        var first = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var concept = schedules.createInvoice(f.id(), plan.rows().get(1).id());
        assertEquals("Termijn met conceptfactuur " + concept.number()
                        + " kan niet weg; verdeel het bedrag over de bestaande termijnen of verwijder eerst het concept.",
                assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(0), "5000"),
                        new PartnerAdvanceScheduleService.RowRequest(null, "Rest", null, amount("10000"), null))).getMessage());

        quotes.reopen(first.id());
        assertEquals(QuoteStatus.CONCEPT, sales.get(first.id()).status());
        var reopened = schedules.get(f.id()).rows().get(0);
        assertFalse(reopened.invoiceFixed(), "a reopened concept is a concept: it follows a new split");
        assertTrue(reopened.invoiceReopened());
        assertFalse(schedules.get(f.id()).rows().get(1).invoiceReopened(), "never issued");
        assertEquals("Termijn met conceptfactuur " + first.number()
                        + " kan niet weg; verdeel het bedrag over de bestaande termijnen of verwijder eerst het concept.",
                assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(1), "15000"))).getMessage(),
                "a reopened concept keeps its term too");

        var after = save(f, row(plan.rows().get(0), "4000"), row(plan.rows().get(1), "11000"));
        assertEquals(List.of(amount("4000"), amount("11000")), after.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList());
        assertEquals(first.number(), sales.get(first.id()).number());
        assertEquals(amount("4000"), sales.price(sales.get(first.id())).totals().total());
    }

    /**
     * Verhoeven BV's container (production, 2026-10-01): € 54.435,82 agreed in
     * 1/3 + 2/3. The first third is issued and paid; '2/3 na productie' was
     * issued and reopened to concept. 1/3 · 1/3 · 1/3 revises that concept in
     * place (same number, now a third) and adds an open third without invoice.
     * The amounts are the screen's one-click split: each part rounded to the
     * cent, the last one taking what is left (18.145,28 + 18.145,27).
     */
    @Test @TestTransaction
    void aReopenedTwoThirdsConceptFollowsTheThirdsSplitWhileThePaidThirdStaysFixed() {
        var f = fixture("108871.64", 1);
        var plan = save(f, new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij start productie", null, amount("18145.27"), null),
                new PartnerAdvanceScheduleService.RowRequest(null, "2/3 na productie", null, amount("36290.55"), null));
        assertEquals(amount("54435.82"), plan.agreedAmountEur());
        var first = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        BigDecimal firstInclVat = sales.price(first).totals().totalInclVat();
        incoming.add(first.id(), new IncomingPaymentService.Request(firstInclVat, Instant.now().minusSeconds(60), "Europe/Brussels", "Verhoeven BV"));
        var second = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(1).id()).id());
        quotes.reopen(second.id());
        assertTrue(quotes.history(second.id()).stream().anyMatch(event -> event.type() == QuoteEvent.Type.HEROPEND));

        var before = schedules.get(f.id());
        assertTrue(before.rows().get(0).invoiceFixed(), "issued and paid");
        assertFalse(before.rows().get(0).invoiceReopened());
        assertEquals(QuoteStatus.CONCEPT, before.rows().get(1).invoiceStatus());
        assertFalse(before.rows().get(1).invoiceFixed(), "issued and reopened: a concept again, it follows a new split");
        assertTrue(before.rows().get(1).invoiceReopened());

        var after = save(f, row(before.rows().get(0), "18145.27"),
                new PartnerAdvanceScheduleService.RowRequest(before.rows().get(1).id(), "1/3 na productie", null, amount("18145.28"), null),
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij aankomst", null, amount("18145.27"), null));
        var amounts = after.rows().stream().map(PartnerAdvanceScheduleService.Row::amountEur).toList();
        assertEquals(List.of(amount("18145.27"), amount("18145.28"), amount("18145.27")), amounts);
        assertEquals(amount("54435.82"), amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add), "every cent of the agreed advance");
        assertEquals(amount("0"), after.unallocatedEur());
        assertEquals(first.id(), after.rows().get(0).invoiceId());
        assertEquals(second.id(), after.rows().get(1).invoiceId(), "the reopened concept stays with its term");
        assertNull(after.rows().get(2).invoiceId(), "the new third waits for 'Conceptfactuur maken'");

        var revised = sales.get(second.id());
        assertEquals(second.number(), revised.number(), "the issued number stays in the series");
        assertEquals(QuoteStatus.CONCEPT, revised.status());
        assertEquals(amount("18145.28"), sales.price(revised).totals().total());
        assertEquals("Voorschot · 1/3 na productie", revised.extraLines().getFirst().description());
        assertTrue(quotes.history(second.id()).stream().anyMatch(event -> event.type() == QuoteEvent.Type.OPGEMAAKT
                && "Voorschottermijn aangepast: € 36.290,55 → € 18.145,28".equals(event.summary())));
        assertEquals(amount("18145.27"), sales.price(sales.get(first.id())).totals().total(), "the paid third is untouched");

        var reissued = sales.issueInvoice(second.id());
        assertEquals(QuoteStatus.UITGEREIKT, reissued.status());
        assertEquals(amount("18145.28"), sales.price(reissued).totals().total());
        assertTrue(schedules.get(f.id()).rows().get(1).invoiceFixed(), "issued again: fixed");
    }

    @Test @TestTransaction
    void aReopenedAdvanceTermIsNotDeletedAndPointsToTheTerms() {
        var f = fixture();
        var plan = thirds(f);
        var concept = schedules.createInvoice(f.id(), plan.rows().get(0).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(1).id()).id());
        assertEquals("Alleen een conceptfactuur die nog nooit verstuurd of gebruikt is kan verwijderd worden",
                assertThrows(BusinessRuleException.class, () -> sales.delete(second.id())).getMessage(),
                "an issued term is fixed: the plan cannot change it, so no pointer to Termijnen aanpassen");
        quotes.reopen(second.id());
        assertEquals("Factuur " + second.number() + " was al uitgereikt; het nummer blijft bestaan. "
                        + "Pas de verdeling aan via Termijnen aanpassen op de inkooporder.",
                assertThrows(BusinessRuleException.class, () -> sales.delete(second.id())).getMessage());
        assertEquals(QuoteStatus.CONCEPT, sales.get(second.id()).status(), "still there, same number");
        sales.delete(concept.id());
        assertNull(schedules.get(f.id()).rows().get(0).invoiceId(), "a never-issued concept may still go");
    }

    /**
     * Invoices made on 2026-09-09/10 stored the term sentence in their notes. A
     * re-split that relabels the reopened concept drops that sentence (it named
     * '2/3 na productie'); the buyer's own note after it stays.
     */
    @Test @TestTransaction
    void aRelabelledReopenedConceptDropsTheStoredTermSentenceButKeepsTheBuyerNote() {
        var f = fixture();
        var plan = thirds(f);
        sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var created = schedules.createInvoice(f.id(), plan.rows().get(1).id());
        String legacy = "Voorschot · 2/3 na productie. Vast bedrag van het afgesproken voorschot. "
                + "Deze factuur betreft uitsluitend deze termijn; de eindafrekening volgt afzonderlijk.";
        setNotes(created.id(), legacy + "\nOnze referentie: PO-2026-77.");
        var second = sales.issueInvoice(created.id());
        quotes.reopen(second.id());

        save(f, row(plan.rows().get(0), "5000"),
                new PartnerAdvanceScheduleService.RowRequest(plan.rows().get(1).id(), "1/3 na productie", null, amount("5000"), null),
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij aankomst", null, amount("5000"), null));

        var revised = sales.get(second.id());
        assertEquals("Voorschot · 1/3 na productie", revised.extraLines().getFirst().description());
        assertEquals("Onze referentie: PO-2026-77.", revised.notes(), "the stale term sentence is gone, the buyer note stays");
    }

    @Test
    void onlyAGeneratedTermSentenceIsDroppedFromTheNotes() {
        String legacy = "Voorschot · 2/3 na productie. 66.67% van het afgesproken voorschot. "
                + "Deze factuur betreft uitsluitend deze termijn; de eindafrekening volgt afzonderlijk.";
        String older = "Partnervoorschot · 2/3 na productie. Vast bedrag van de afgesproken partnerbijdrage € 54435.82 "
                + "(50% van kostbasis € 108871.64). Deze factuur betreft uitsluitend deze termijn.";
        assertNull(SalesOrderService.withoutGeneratedTermNote(legacy));
        assertNull(SalesOrderService.withoutGeneratedTermNote(older));
        assertEquals("Ref 12", SalesOrderService.withoutGeneratedTermNote(older + "\n\nRef 12"));
        assertEquals("Ref 12\n" + legacy, SalesOrderService.withoutGeneratedTermNote("Ref 12\n" + legacy), "only a leading sentence");
        assertEquals(legacy + "Ref", SalesOrderService.withoutGeneratedTermNote(legacy + "Ref"), "not a sentence of its own");
        assertNull(SalesOrderService.withoutGeneratedTermNote(null));
    }

    @Test @TestTransaction
    void aReopenedConceptWithPaymentHistoryStaysFixed() {
        var f = fixture();
        var plan = thirds(f);
        sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(1).id()).id());
        incoming.add(second.id(), new IncomingPaymentService.Request(amount("100"), Instant.now().minusSeconds(60), "Europe/Brussels", "Deel"));
        incoming.delete(second.id(), incoming.forOrder(second.id()).getFirst().id());
        forceConcept(second.id()); // an invoice reopened before the payment-history guard existed

        var row = schedules.get(f.id()).rows().get(1);
        assertEquals(QuoteStatus.CONCEPT, row.invoiceStatus());
        assertTrue(row.invoiceFixed(), "a withdrawn receipt is still payment history");
        assertRefusedResplit(f, plan, second);
    }

    @Test @TestTransaction
    void aReopenedConceptWithALiveCreditNoteStaysFixed() {
        var f = fixture();
        var plan = thirds(f);
        sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(0).id()).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.id(), plan.rows().get(1).id()).id());
        sales.createCreditNote(second.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PRICE_CORRECTION, List.of(),
                List.of(new SalesOrderService.CreditAmount("Correctie", amount("100"))), false, null));
        forceConcept(second.id()); // reopening refuses an invoice with a live credit note; older data may hold one

        var row = schedules.get(f.id()).rows().get(1);
        assertEquals(QuoteStatus.CONCEPT, row.invoiceStatus());
        assertTrue(row.invoiceFixed(), "a live credit note fixes the term");
        assertRefusedResplit(f, plan, second);
    }

    /* ---------------------------------------------------------------- helpers */

    /** A 30 000 container at 50 %: an agreed advance of 15 000 in 1/3 + 2/3. */
    private PartnerAdvanceScheduleService.Schedule thirds(PurchaseOrder f) {
        var plan = save(f, new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij start productie", null, amount("5000"), null),
                new PartnerAdvanceScheduleService.RowRequest(null, "2/3 na productie", null, amount("10000"), null));
        assertEquals(amount("15000"), plan.agreedAmountEur());
        return plan;
    }
    private static PartnerAdvanceScheduleService.RowRequest row(PartnerAdvanceScheduleService.Row row, String amount) {
        return new PartnerAdvanceScheduleService.RowRequest(row.id(), row.label(), null, amount(amount), row.dueDate());
    }
    private PartnerAdvanceScheduleService.Schedule save(PurchaseOrder f, PartnerAdvanceScheduleService.RowRequest... rows) {
        return schedules.save(f.id(), new PartnerAdvanceScheduleService.Request(List.of(rows), false));
    }
    /** The fixed concept is refused by the plan and, under its lock, by the revise path itself. */
    private void assertRefusedResplit(PurchaseOrder f, PartnerAdvanceScheduleService.Schedule plan, SalesOrder fixed) {
        assertEquals("Een termijn met een factuur staat vast; verwijder eerst de ongebruikte conceptfactuur om de termijn te wijzigen",
                assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(0), "5000"),
                        new PartnerAdvanceScheduleService.RowRequest(plan.rows().get(1).id(), "1/3 na productie", null, amount("5000"), null),
                        new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij aankomst", null, amount("5000"), null))).getMessage());
        assertEquals("Factuur " + fixed.number() + " heeft een betaalhistoriek of creditnota en staat vast; de termijn blijft ongewijzigd",
                assertThrows(BusinessRuleException.class, () -> sales.reviseScheduledPartnerAdvance(purchases.get(f.id()), fixed.id(),
                        "1/3 na productie", amount("5000"), null)).getMessage());
        assertEquals(amount("10000"), sales.price(sales.get(fixed.id())).totals().total(), "nothing changed");
    }
    private void setNotes(long invoiceId, String notes) {
        em.flush();
        em.find(SalesOrderEntity.class, invoiceId).notes = notes;
        em.flush(); em.clear();
    }
    private void forceConcept(long invoiceId) {
        em.flush();
        em.find(SalesOrderEntity.class, invoiceId).status = QuoteStatus.CONCEPT;
        em.flush(); em.clear();
    }
    private PurchaseOrder fixture() { return fixture("30000", 12); }
    private PurchaseOrder fixture(String containerTotal, int quantity) {
        var partner = customers.create(new Customer(null, "Resplit partner " + UUID.randomUUID(), "Finance", null, null, "BE0000000000", "BE",
                Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Resplit supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("50"), new BigDecimal("50")));
        ProductEntity product = new ProductEntity(); product.sku = "RSP-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = quantity;
        line.exwPrice = new BigDecimal(containerTotal).divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP);
        line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line); em.flush(); em.clear();
        return purchases.get(purchase.id());
    }
    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }
}
