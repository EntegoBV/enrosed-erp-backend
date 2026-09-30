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

/**
 * A partner plan of 1/3 + 2/3 whose first third is invoiced can still become
 * 1/3 · 1/3 · 1/3: issued terms stay exactly as they are, a never-issued
 * concept follows the new split in place (same number), and the new split
 * covers the whole agreed advance.
 */
@QuarkusTest
class PartnerAdvanceResplitTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
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
    void aConceptTermCannotBeDroppedAndAReopenedInvoiceStaysFixed() {
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
        assertTrue(schedules.get(f.id()).rows().get(0).invoiceFixed(), "issued once, fixed for good");
        assertThrows(BusinessRuleException.class, () -> save(f, row(plan.rows().get(0), "4000"), row(plan.rows().get(1), "11000")));
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
    private PurchaseOrder fixture() {
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
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = 12;
        line.exwPrice = new BigDecimal("30000").divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
        line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line); em.flush(); em.clear();
        return purchases.get(purchase.id());
    }
    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }
}
