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
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
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
 * A partner container that arrived short: the advance financed more than
 * the landed basis, so a credit note on the advance hands the difference
 * back, and every partner ledger reads the net advance from then on.
 */
@QuarkusTest
class PartnerCreditNoteTest {
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceSchedules scheduleRows;
    @Inject SalesOrderService sales;
    @Inject PartnerSettlements settlements;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService financing;
    @Inject PartnerContainerDeletionService deletion;
    @Inject QuoteService quotes;
    @Inject EntityManager em;

    @Test @TestTransaction
    void aShortContainerProposesTheOverFinancingAndTheCreditNoteNetsEveryPartnerLedger() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Productie", "100")), false));
        var advance = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().getFirst().id()).id());
        assertEquals(amount("6000"), sales.price(advance).totals().total());

        var before = financing.creditProposal(f.purchase.id());
        assertFalse(before.received());
        assertFalse(before.settlementExists());
        assertEquals(0, before.overFinancingEur().signum(), "nothing is short before the receipt");
        assertEquals(amount("6000"), before.issuedAdvanceEur());
        assertEquals(amount("12000"), before.actualBasisEur(), "the basis on the ordered pieces, a number before the receipt too");
        assertEquals(amount("6000"), before.agreedShareEur());
        assertEquals(advance.id(), before.suggestedAdvanceInvoiceId());
        assertEquals(amount("7260"), before.advances().getFirst().maxCreditInclVatEur());
        assertEquals(f.purchase.number(), before.containerName(), "without a Herkenbare naam the container is its number");
        em.find(PurchaseOrderEntity.class, f.purchase.id()).alias = "container/2026/010";
        em.flush(); em.clear();
        assertEquals("container/2026/010", financing.creditProposal(f.purchase.id()).containerName());
        assertEquals("container/2026/010", sales.proposeCreditNote(advance.id()).container().containerName());
        assertEquals(f.purchase.number(), sales.proposeCreditNote(advance.id()).container().number());

        receive(f, 10);
        var after = financing.creditProposal(f.purchase.id());
        assertTrue(after.received());
        assertEquals(2, after.missingPieces());
        assertEquals(0, after.damagedPieces());
        assertEquals(amount("10000"), after.actualBasisEur(), "the landed basis on the ten received pieces");
        assertEquals(0, new BigDecimal("50").compareTo(after.financingPct()));
        assertEquals(amount("5000"), after.agreedShareEur());
        assertEquals(amount("1000"), after.overFinancingEur());
        assertEquals(amount("1210"), after.overFinancingInclVatEur());
        assertEquals(amount("0"), after.creditedAdvanceEur());

        var invoiceProposal = sales.proposeCreditNote(advance.id());
        assertEquals(CreditReason.PARTNER_SHORTFALL, invoiceProposal.suggestedReason());
        assertNotNull(invoiceProposal.partnerShortfall());
        assertEquals(amount("1000"), invoiceProposal.partnerShortfall().overFinancingEur());
        assertEquals("Een creditnota op een voorschot bevat alleen een bedrag, geen producten",
                assertThrows(BusinessRuleException.class, () -> sales.createCreditNote(advance.id(),
                        new SalesOrderService.CreditNoteRequest(CreditReason.PARTNER_SHORTFALL,
                                List.of(new SalesOrderService.CreditLine(f.productId, 1, null)), List.of(), false, null))).getMessage());

        var cn = sales.createCreditNote(advance.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PARTNER_SHORTFALL, List.of(),
                List.of(new SalesOrderService.CreditAmount("Voorschot te veel gefinancierd · " + f.purchase.number() + " (2 stuks minder ontvangen)",
                        amount("1000"))), false, null));
        assertEquals(SalesPurpose.PARTNER_ADVANCE, cn.purpose());
        assertTrue(cn.isPartnerDeal());
        assertEquals(f.purchase.id(), cn.linkedPurchaseOrderId());
        assertFalse(cn.partnerSettlement());
        assertEquals("Reik eerst de conceptcreditnota uit of verwijder ze voordat je een afrekening maakt",
                assertThrows(BusinessRuleException.class, () -> settle(f, 10, "12000", true)).getMessage());

        sales.issueInvoice(cn.id());
        var summary = financing.get(f.purchase.id());
        assertEquals(amount("5000"), summary.invoicedAdvanceEur(), "issued advances net of the credit note");
        assertEquals(amount("1210"), summary.creditNotesEur());
        assertEquals(amount("1210"), summary.creditEur(), "the open credit balance is money owed to the partner");
        assertEquals(0, summary.recognizedRevenueEur().signum(), "an advance credit recognises nothing");
        assertTrue(summary.documents().stream().anyMatch(d -> d.docType() == DocumentType.CREDITNOTA
                && advance.id().equals(d.creditedInvoiceId()) && d.invoiceTotalEur().compareTo(amount("-1210")) == 0));
        var availability = sales.partnerSettlementAvailability(f.purchase.id());
        assertEquals(amount("5000"), availability.issuedAdvanceEur());
        assertEquals(amount("5000"), availability.remainingAdvanceEur());
        assertEquals(amount("1000"), financing.creditProposal(f.purchase.id()).creditedAdvanceEur());
        assertEquals(0, financing.creditProposal(f.purchase.id()).overFinancingEur().signum());
        var zeros = financing.accounting(sales.get(cn.id()), sales.price(sales.get(cn.id())));
        assertEquals(0, zeros.recognizedRevenueEur().signum());
        assertEquals(0, zeros.recognizedQuantity());
        assertFalse(deletion.preview(f.purchase.id()).allowed(), "a container with a credit note is never swept away");

        /* The settlement credits only the net advance, and the credit note can be offset against it. */
        var settlement = settle(f, 10, "12000", true);
        assertEquals(amount("5000"), settlements.find(settlement.id()).advanceEur());
        sales.issueInvoice(settlement.id());
        assertEquals("Afrekening " + settlement.number() + " heeft het voorschot al verrekend; maak de creditnota op die afrekening",
                assertThrows(BusinessRuleException.class, () -> sales.createCreditNote(advance.id(),
                        new SalesOrderService.CreditNoteRequest(CreditReason.PARTNER_SHORTFALL, List.of(),
                                List.of(new SalesOrderService.CreditAmount("Nog eens", amount("1"))), false, null))).getMessage());
        var withSettlement = financing.creditProposal(f.purchase.id());
        assertTrue(withSettlement.settlementExists());
        assertEquals(0, withSettlement.overFinancingEur().signum());
        /* The settlement netted the credit note; it can neither go back to concept nor be issued twice. */
        assertEquals("Er bestaat al een gekoppelde partnerafrekening; het voorschot of de eerdere afrekening kan niet terug naar concept",
                assertThrows(BusinessRuleException.class, () -> quotes.reopen(cn.id())).getMessage());
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(cn.id()).status());
        assertEquals(amount("5000"), sales.partnerSettlementAvailability(f.purchase.id()).issuedAdvanceEur());
        BigDecimal settlementOpen = incoming.summary(sales.get(settlement.id()), sales.price(sales.get(settlement.id()))).remainingEur();
        assertTrue(settlementOpen.signum() > 0, "the partner still owes on the final settlement");
        incoming.applyCredit(cn.id(), settlement.id(), null);
        assertEquals(0, incoming.summary(sales.get(cn.id()), sales.price(sales.get(cn.id()))).creditEur().signum());
        assertEquals(settlementOpen.subtract(amount("1210")), incoming.summary(sales.get(settlement.id()), sales.price(sales.get(settlement.id()))).remainingEur());
        assertEquals(0, financing.get(f.purchase.id()).creditEur().signum());

        /* A credit note on the settlement corrects money only. */
        var settlementCredit = sales.issueInvoice(sales.createCreditNote(settlement.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(), List.of(new SalesOrderService.CreditAmount("Correctie", amount("100"))), false, null)).id());
        var accounting = financing.accounting(sales.get(settlementCredit.id()), sales.price(sales.get(settlementCredit.id())));
        assertEquals(amount("-100"), accounting.recognizedRevenueEur());
        assertEquals(0, accounting.recognizedCostEur().signum());
        assertEquals(amount("-100"), accounting.recognizedProfitEur());
        assertEquals(0, accounting.recognizedQuantity());
        assertEquals(sales.price(sales.get(settlement.id())).totals().total().subtract(amount("100")), financing.get(f.purchase.id()).settlementEur());
        assertEquals(10, sales.partnerSettlementAvailability(f.purchase.id()).lines().getFirst().settledQuantity(),
                "a settlement credit never reopens auctioned pieces");
    }

    @Test @TestTransaction
    void theContainerDeletionNamesTheCreditNoteThatBlocksIt() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Productie", "100")), false));
        var advance = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().getFirst().id()).id());
        var cn = sales.createCreditNote(advance.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PRICE_CORRECTION, List.of(),
                List.of(new SalesOrderService.CreditAmount("Correctie", amount("100"))), false, null));
        var preview = deletion.preview(f.purchase.id());
        assertFalse(preview.allowed());
        assertEquals("Er bestaat een creditnota " + cn.number() + " op deze container; annuleer of verwijder die eerst.", preview.blockReason());
        assertEquals(preview.blockReason(), assertThrows(BusinessRuleException.class, () -> deletion.delete(f.purchase.id(),
                new PartnerContainerDeletionService.Request(List.of(advance.id(), cn.id())))).getMessage());
    }

    @Test @TestTransaction
    void partnerAndOrdinaryDocumentsNeverOffsetEachOther() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Productie", "100")), false));
        var advance = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().getFirst().id()).id());
        receive(f, 10);
        var cn = sales.issueInvoice(sales.createCreditNote(advance.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PARTNER_SHORTFALL, List.of(), List.of(new SalesOrderService.CreditAmount("Te veel", amount("1000"))), false, null)).id());
        var ordinary = sales.create(f.partnerId, "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, ordinary.id());
        stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":100}]";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED; stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD; stored.status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        assertEquals("Verrekenen tussen een partnerdocument en een gewoon document is niet mogelijk",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), ordinary.id(), null)).getMessage());
        assertTrue(incoming.forOrder(cn.id()).isEmpty());
    }

    /** Emre's container: € 8.020,64 financed, € 7.991,49 agreed after receipt, € 29,15 back to the partner. */
    @Test @TestTransaction
    void theSuggestionIsThePartnersShareOfTheMissingPiecesAndAPendingConceptIsNotProposedAgain() {
        var partner = customers.create(new Customer(null, "Verhoeven BV " + UUID.randomUUID(), "Finance", null, null, "NL123456789B01", "NL",
                Language.NL, "Veilingweg 1", "1431", "Aalsmeer", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Credit supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("50"), new BigDecimal("50")));
        long whole = product(supplier.id());
        long shortOne = product(supplier.id());
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        for (Object[] row : new Object[][] {{whole, 1, "15982.97"}, {shortOne, 7, "8.33"}}) {
            var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = (Long) row[0]; line.quantity = (Integer) row[1];
            line.exwPrice = new BigDecimal((String) row[2]); line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line);
        }
        em.flush(); em.clear();
        var plan = schedules.save(purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Productie", "100")), false));
        var advance = sales.issueInvoice(schedules.createInvoice(purchase.id(), plan.rows().getFirst().id()).id());
        assertEquals(amount("8020.64"), sales.price(advance).totals().total());
        assertEquals(0, sales.price(advance).totals().vatRatePct().signum(), "an intra-EU partner pays no Belgian VAT");

        em.find(PurchaseOrderEntity.class, purchase.id()).status = PurchaseOrderStatus.BESTELD;
        em.flush(); em.clear();
        purchases.receive(purchase.id(), new PurchaseOrderService.Receipt(List.of(new PurchaseOrderService.ReceivedLine(whole, 1, 0),
                new PurchaseOrderService.ReceivedLine(shortOne, 0, 0)), false, null, LocalDate.now(), null));
        em.flush(); em.clear();

        var proposal = financing.creditProposal(purchase.id());
        assertEquals(amount("15982.97"), proposal.actualBasisEur());
        assertEquals(amount("7991.49"), proposal.agreedShareEur(), "50 % of 15.982,97, rounded half up");
        assertEquals(amount("29.15"), proposal.overFinancingEur());
        assertEquals(amount("58.31"), proposal.shortValueEur(), "the missing pieces at the ordered basis");
        assertEquals(advance.id(), proposal.suggestedAdvanceInvoiceId());
        assertEquals(amount("29.15"), proposal.suggestedCreditEur());
        assertEquals(amount("29.15"), proposal.suggestedCreditInclVatEur(), "0 % VAT: incl. equals excl.");
        assertEquals(amount("0"), proposal.remainingCreditEur());
        assertEquals(amount("0"), proposal.pendingCreditEur());
        assertTrue(proposal.pendingCreditNumbers().isEmpty());
        var option = proposal.advances().getFirst();
        assertEquals(0, option.vatRatePct().signum());
        assertEquals(amount("8020.64"), option.maxCreditEur());
        assertEquals(amount("8020.64"), option.openEur());
        assertEquals(amount("29.15"), option.suggestedCreditEur());

        /* A legacy agreement on the external forecast is not comparable: nothing is suggested, the figure stays visible. */
        var agreement = scheduleRows.find(purchase.id());
        scheduleRows.save(new PartnerAdvanceSchedules.Agreement(purchase.id(), partner.id(), agreement.externalCostEur(),
                agreement.financingPct(), agreement.agreedAmountEur(), PartnerAdvanceBasis.Kind.EXTERNAL_FORECAST));
        var legacy = financing.creditProposal(purchase.id());
        assertEquals(amount("29.15"), legacy.overFinancingEur());
        assertEquals(amount("0"), legacy.suggestedCreditEur());
        scheduleRows.save(agreement);

        var cn = sales.createCreditNote(advance.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PARTNER_SHORTFALL, List.of(),
                List.of(new SalesOrderService.CreditAmount("Voorschot te veel gefinancierd", amount("29.15"))), false, null));
        var pending = financing.creditProposal(purchase.id());
        assertEquals(amount("29.15"), pending.overFinancingEur(), "the issued-only figure is unchanged by a concept");
        assertEquals(amount("29.15"), pending.pendingCreditEur());
        assertEquals(List.of(cn.number()), pending.pendingCreditNumbers());
        assertEquals(amount("0"), pending.suggestedCreditEur(), "never proposed twice");
        assertEquals(amount("0"), pending.remainingCreditEur());

        sales.issueInvoice(cn.id());
        var settled = financing.creditProposal(purchase.id());
        assertEquals(0, settled.overFinancingEur().signum());
        assertEquals(amount("0"), settled.pendingCreditEur());
        assertEquals(amount("0"), settled.suggestedCreditEur());

    }

    @Test @TestTransaction
    void theCreditGoesOnTheLatestUnpaidAdvanceThatCanAbsorbIt() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Start", "40"), pct("Klaar", "60")), false));
        var first = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(0).id()).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(1).id()).id());
        incoming.add(first.id(), new IncomingPaymentService.Request(sales.price(first).totals().totalInclVat(),
                java.time.Instant.now().minusSeconds(60), "Europe/Brussels", "Eerste termijn"));
        receive(f, 6);
        var proposal = financing.creditProposal(f.purchase.id());
        assertEquals(amount("3000"), proposal.overFinancingEur());
        assertEquals(second.id(), proposal.suggestedAdvanceInvoiceId(), "the unpaid 60 % can be offset without a refund");
        assertEquals(amount("3000"), proposal.suggestedCreditEur());
        assertEquals(amount("3630"), proposal.suggestedCreditInclVatEur());
        assertEquals(amount("0"), proposal.remainingCreditEur());
        var paid = proposal.advances().stream().filter(o -> o.invoiceId() == first.id()).findFirst().orElseThrow();
        assertEquals(amount("0"), paid.openEur());
        assertEquals(amount("2400"), paid.maxCreditEur());
    }

    @Test @TestTransaction
    void theOpenAmountIncludingVatMustCoverTheCreditIncludingVat() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Start", "50"), pct("Klaar", "50")), false));
        var first = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(0).id()).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(1).id()).id());
        incoming.add(second.id(), new IncomingPaymentService.Request(amount("500"),
                java.time.Instant.now().minusSeconds(60), "Europe/Brussels", "Deel tweede termijn"));
        receive(f, 6);
        var proposal = financing.creditProposal(f.purchase.id());
        assertEquals(amount("3000"), proposal.overFinancingEur());
        assertEquals(amount("3630"), proposal.suggestedCreditInclVatEur());
        var partlyPaid = proposal.advances().stream().filter(o -> o.invoiceId() == second.id()).findFirst().orElseThrow();
        assertEquals(amount("3130"), partlyPaid.openEur(), "more than € 3.000 excl. is open, less than the € 3.630 incl. credit");
        assertEquals(first.id(), proposal.suggestedAdvanceInvoiceId(),
                "only the unpaid first advance still owes the whole credit incl. VAT, so only there it is offset without a refund");
        assertEquals(amount("3000"), proposal.suggestedCreditEur());
    }

    @Test @TestTransaction
    void aCreditLargerThanOneAdvanceIsCappedByItsRoomAndTheRestMovesOn() {
        var f = fixture("12000", 12, "50");
        var plan = schedules.save(f.purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(pct("Start", "40"), pct("Klaar", "60")), false));
        var first = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(0).id()).id());
        var second = sales.issueInvoice(schedules.createInvoice(f.purchase.id(), plan.rows().get(1).id()).id());
        receive(f, 1);
        var proposal = financing.creditProposal(f.purchase.id());
        assertEquals(amount("5500"), proposal.overFinancingEur());
        assertEquals(second.id(), proposal.suggestedAdvanceInvoiceId(), "neither can take it all: the latest");
        assertEquals(amount("3600"), proposal.suggestedCreditEur(), "capped by the room excl. VAT");
        assertEquals(amount("1900"), proposal.remainingCreditEur());
        assertEquals(amount("2400"), proposal.advances().stream().filter(o -> o.invoiceId() == first.id())
                .findFirst().orElseThrow().suggestedCreditEur());

        sales.createCreditNote(second.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PARTNER_SHORTFALL, List.of(),
                List.of(new SalesOrderService.CreditAmount("Te veel", amount("3600"))), false, null));
        var rest = financing.creditProposal(f.purchase.id());
        assertEquals(amount("3600"), rest.pendingCreditEur());
        assertEquals(first.id(), rest.suggestedAdvanceInvoiceId(), "a fully credited advance has no room left");
        assertEquals(amount("1900"), rest.suggestedCreditEur());
        assertEquals(amount("0"), rest.remainingCreditEur());
    }

    /* ---------------------------------------------------------------- helpers */

    private long product(long supplierId) {
        ProductEntity product = new ProductEntity(); product.sku = "PCN-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplierId; product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        return product.id;
    }

    private SalesOrder settle(Fixture f, int quantity, String proceeds, boolean finalSettlement) {
        return sales.createAuctionSettlement(new SalesOrderService.AuctionSettlementRequest(f.partnerId, f.purchase.id(), null,
                null, new BigDecimal("100"), new BigDecimal("50"),
                List.of(new SalesOrderService.AuctionLine(f.productId, quantity, amount(proceeds), null)), null, finalSettlement));
    }
    private void receive(Fixture f, int received) {
        em.find(PurchaseOrderEntity.class, f.purchase.id()).status = PurchaseOrderStatus.BESTELD;
        em.flush(); em.clear();
        purchases.receive(f.purchase.id(), new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(f.productId, received, 0)), false, null, LocalDate.now(), null));
        em.flush(); em.clear();
    }
    private static PartnerAdvanceScheduleService.RowRequest pct(String label, String percentage) {
        return new PartnerAdvanceScheduleService.RowRequest(null, label, new BigDecimal(percentage), null, null);
    }
    private record Fixture(PurchaseOrder purchase, long partnerId, long productId) {}
    private Fixture fixture(String totalCost, int quantity, String financingPct) {
        var partner = customers.create(new Customer(null, "Credit partner " + UUID.randomUUID(), "Finance", null, null, "BE0000000000", "BE", Language.NL,
                "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Credit supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal(financingPct), new BigDecimal("50")));
        ProductEntity product = new ProductEntity(); product.sku = "PCN-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = quantity;
        line.exwPrice = new BigDecimal(totalCost).divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP);
        line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line); em.flush(); em.clear();
        return new Fixture(purchases.get(purchase.id()), partner.id(), product.id);
    }
    private static BigDecimal amount(String value) { return new BigDecimal(value).setScale(2); }
}
