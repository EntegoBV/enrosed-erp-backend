package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A whole container sold to a regular customer and paid in parts: advance
 * invoices on the quote, then the slotfactuur deducting each of them with its
 * number and date, VAT on the balance, and the document total of all of them
 * equal to the sale.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class SalesAdvanceBillingTest {
    @Inject SalesAdvanceBillingService billing;
    @Inject SalesAdvanceBilling store;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService financing;
    @Inject SalesOrderResource resource;
    @Inject EntityManager em;
    @Inject MockMailbox mailbox;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Test @TestTransaction
    void aPercentageAndAnAmountBecomeConceptAdvanceInvoicesWithinTheQuote() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        BigDecimal base = Money.money(sales.price(quote).totals().total());
        var first = billing.createAdvanceInvoice(quote.id(), new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null));
        assertEquals(DocumentType.FACTUUR, first.docType());
        assertEquals(QuoteStatus.CONCEPT, first.status());
        assertTrue(first.number().matches("^[A-Za-z0-9]+-" + LocalDate.now().getYear() + "-\\d+$"), "the normal F series: " + first.number());
        assertEquals(quote.customerId(), first.customerId());
        assertEquals(quote.countryCode(), first.countryCode());
        assertEquals(quote.incoterm(), first.incoterm());
        assertTrue(first.lines().isEmpty(), "an advance claims an amount, no products");
        assertEquals(List.of("Voorschot 30 % · offerte " + quote.number()), first.extraLines().stream().map(SalesExtraLine::description).toList());
        BigDecimal thirty = base.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        assertEquals(thirty, sales.price(first).totals().total());
        assertEquals(FreightPricingStrategy.FIXED, first.freightPricingStrategy());
        assertEquals(0, first.manualFreightEur().signum());
        assertEquals(SalesPurpose.STANDARD, first.purpose());
        assertEquals(SalesPaymentPlan.FULL, first.paymentPlan());
        assertNull(first.sourceQuoteId(), "the quote's one invoice is still its slotfactuur");
        var row = store.find(first.id());
        assertEquals(SalesAdvanceBilling.Stage.ADVANCE, row.stage());
        assertEquals(0, new BigDecimal("30").compareTo(row.percentage()));
        assertTrue(quotes.history(quote.id()).stream().anyMatch(event -> event.type() == QuoteEvent.Type.GEFACTUREERD
                && ("Voorschotfactuur " + first.number() + " aangemaakt (30 %)").equals(event.summary())));

        LocalDate due = LocalDate.now().plusDays(10);
        var second = billing.createAdvanceInvoice(quote.id(), new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("1000"), due));
        assertEquals("Voorschot · offerte " + quote.number(), second.extraLines().getFirst().description());
        assertEquals(due, second.invoiceDueDate());
        assertEquals(new BigDecimal("1000.00"), sales.price(second).totals().total());

        /* The advance's amount line belongs to the server. */
        var edited = sales.update(first.id(), sales.get(first.id()).withExtraLines(List.of(new SalesExtraLine("Iets anders", BigDecimal.ONE, BigDecimal.TEN))));
        assertEquals("Voorschot 30 % · offerte " + quote.number(), edited.extraLines().getFirst().description());
        assertEquals(thirty, sales.price(edited).totals().total());

        var quoteView = resource.get(quote.id());
        assertEquals(List.of(first.id(), second.id()), quoteView.advanceInvoices().stream().map(SalesAdvanceBillingService.AdvanceInvoice::id).toList());
        assertEquals(thirty, quoteView.advanceInvoices().getFirst().amountExclEur());
        assertNull(quoteView.advanceBilling());
        var advanceView = resource.get(first.id());
        assertEquals(SalesAdvanceBilling.Stage.ADVANCE, advanceView.advanceBilling().stage());
        assertEquals(quote.number(), advanceView.advanceBilling().quoteNumber());
        assertNull(advanceView.advanceInvoices(), "only a quote lists advances");

        /* No minimum order on an advance; its turnover counts, no cost. */
        var issued = sales.issueInvoice(first.id());
        var accounting = financing.accounting(issued, sales.price(issued));
        assertEquals(thirty, accounting.recognizedRevenueEur());
        assertEquals(0, accounting.recognizedCostEur().signum());

        var other = customers.create(customer("BE", "BE0000000001", Language.NL));

        /* Refusals last: a refused transactional call leaves the test transaction for rollback only. */
        /* The quote stays: no delete, reopen or cancel, no other customer; its lines may still change. */
        String blocked = "Deze offerte heeft voorschotfactuur " + first.number() + "; verwijder of annuleer die eerst";
        assertEquals(blocked, assertThrows(BusinessRuleException.class, () -> sales.delete(quote.id())).getMessage());
        assertEquals(blocked, assertThrows(BusinessRuleException.class, () -> sales.requireReopenable(sales.get(quote.id()))).getMessage());
        assertEquals(blocked, assertThrows(BusinessRuleException.class, () -> quotes.cancel(quote.id(), null, false)).getMessage());
        var moved = sales.get(quote.id());
        assertThrows(BusinessRuleException.class, () -> sales.update(quote.id(), new SalesOrder(moved.id(), moved.number(), other.id(),
                moved.countryCode(), moved.orderDate(), moved.validUntil(), moved.status(), moved.incoterm(), moved.paymentTerms(),
                moved.notes(), moved.markupMode(), moved.orderMarkupPct(), moved.extraDiscountPct(), moved.extraDiscountLabel(),
                moved.portalToken(), moved.sentAt(), moved.viewedAt(), moved.viewCount(), moved.decidedAt(), moved.signedByName(),
                moved.customerMessage(), moved.internalNotes(), moved.deliveryTerms(), moved.freight(), moved.manualFreightEur(),
                moved.loadMode(), moved.palletProfile(), moved.maxPalletHeightCm(), moved.freightPricingStrategy(),
                moved.freightRatePerCbmEur(), moved.freightCarrierId(), moved.freightCarrierExtraEur(), moved.docType(),
                moved.invoiceDueDate(), moved.paidAt(), moved.sourceQuoteId(), moved.goodsShippedAt(), moved.lines(), moved.pallets())));

        assertEquals("Met dit voorschot zou meer gefactureerd worden dan de offerte (€ " + euro(base) + " excl. btw)",
                assertThrows(BusinessRuleException.class, () -> billing.createAdvanceInvoice(quote.id(),
                        new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("80"), null, null))).getMessage());
        assertThrows(BusinessRuleException.class, () -> billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("10"), new BigDecimal("10"), null)));
        assertThrows(BusinessRuleException.class, () -> billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, null, null)));
        assertEquals("Een voorschotfactuur factureert alleen het voorschot; de vracht staat op de offerte en de slotfactuur",
                assertThrows(BusinessRuleException.class, () -> sales.updateFreight(second.id(), FreightState.AANGEVULD,
                        new BigDecimal("50"))).getMessage());
    }

    @Test @TestTransaction
    void theSlotfactuurDeductsEveryIssuedAdvanceWithVatOnTheBalanceAndKeepsItsLines() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        BigDecimal base = Money.money(sales.price(quote).totals().total());
        var first = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)).id());
        var second = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("1000"), null)).id());
        Instant paidAt = Instant.now().minusSeconds(60);
        incoming.add(first.id(), new IncomingPaymentService.Request(sales.price(first).totals().totalInclVat(), paidAt, "Europe/Brussels", "Voorschot"));
        incoming.add(second.id(), new IncomingPaymentService.Request(new BigDecimal("100"), paidAt, "Europe/Brussels", "Deel"));
        BigDecimal firstExcl = sales.price(first).totals().total();

        var slot = sales.createInvoiceFrom(quote.id());
        assertEquals(quote.id(), slot.sourceQuoteId());
        var deductions = slot.extraLines().stream().filter(line -> line.description().startsWith("Voorschotfactuur ")).toList();
        assertEquals(List.of("Voorschotfactuur " + first.number() + " van " + DAY.format(first.orderDate()),
                "Voorschotfactuur " + second.number() + " van " + DAY.format(second.orderDate())),
                deductions.stream().map(SalesExtraLine::description).toList());
        assertEquals(firstExcl.negate(), deductions.getFirst().total());
        assertEquals(new BigDecimal("-1000.00"), deductions.getLast().total());
        var priced = sales.price(slot);
        BigDecimal balance = base.subtract(firstExcl).subtract(new BigDecimal("1000.00"));
        assertEquals(balance, priced.totals().total());
        assertEquals(Money.money(Money.percentOf(balance, new BigDecimal("21"))), priced.totals().vatAmount(), "VAT on the balance only");
        var row = store.find(slot.id());
        assertEquals(SalesAdvanceBilling.Stage.FINAL, row.stage());
        assertEquals(firstExcl.add(new BigDecimal("1000.00")), row.amountExclEur());
        assertEquals(List.of(first.id(), second.id()), row.deductions().stream().map(SalesAdvanceBilling.Deduction::advanceInvoiceId).toList());
        assertTrue(quotes.history(slot.id()).stream().anyMatch(event -> ("Slotfactuur · voorschotten verrekend € "
                + euro(row.amountExclEur())).equals(event.summary())));

        /* An edit that drops the deduction lines gets them back. */
        var updated = sales.update(slot.id(), sales.get(slot.id()).withExtraLines(List.of()));
        assertEquals(2, updated.extraLines().stream().filter(line -> line.description().startsWith("Voorschotfactuur ")).count());
        assertEquals(balance, sales.price(updated).totals().total());

        var view = resource.get(slot.id());
        assertEquals(SalesAdvanceBilling.Stage.FINAL, view.advanceBilling().stage());
        assertEquals(2, view.advanceDeductions().size());
        LocalDate paidOn = paidAt.atZone(ZoneId.of("Europe/Brussels")).toLocalDate();
        assertEquals(paidOn, view.advanceDeductions().getFirst().paidOn());
        assertNull(view.advanceDeductions().getLast().paidOn(), "partly paid is not paid");
        assertEquals(List.of(new SalesAdvanceBillingService.Receipt(paidOn, new BigDecimal("100.00"))),
                view.advanceDeductions().getLast().receipts());

        var issuedSlot = sales.issueInvoice(slot.id());
        BigDecimal revenue = List.of(sales.get(first.id()), sales.get(second.id()), issuedSlot).stream()
                .map(order -> financing.accounting(order, sales.price(order)).recognizedRevenueEur()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(base, revenue, "advances and slotfactuur together are the sale, once");
        String settled = "Voorschotfactuur " + first.number() + " is verrekend op slotfactuur " + slot.number();
        assertEquals(settled, assertThrows(BusinessRuleException.class, () -> sales.requireReopenable(sales.get(first.id()))).getMessage());
        assertEquals(settled + "; maak de creditnota op de slotfactuur", assertThrows(BusinessRuleException.class,
                () -> sales.createCreditNote(first.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PRICE_CORRECTION, List.of(),
                        List.of(new SalesOrderService.CreditAmount("Correctie", BigDecimal.ONE)), false, null))).getMessage());
    }

    @Test @TestTransaction
    void aConceptAdvanceOrAnotherVatRegimeStopsTheSlotfactuur() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        var concept = billing.createAdvanceInvoice(quote.id(), new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("40"), null, null));
        var issued = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("20"), null, null)).id());
        var moved = em.find(SalesOrderEntity.class, issued.id());
        moved.countryCode = "NL";
        em.flush(); em.clear();
        assertEquals("Reik eerst voorschotfactuur " + concept.number() + " uit of verwijder ze",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id())).getMessage());
        em.find(SalesOrderEntity.class, concept.id()).status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        assertEquals("Voorschotfactuur " + issued.number() + " heeft een andere btw-regeling dan de offerte",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id())).getMessage());
    }

    @Test @TestTransaction
    void aNegativeBalanceIsNotIssuedAndDeletingTheConceptGivesTheAdvancesBack() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        var advance = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("100"), null, null)).id());
        /* Fewer pieces after the full advance: the balance turns negative. */
        em.find(SalesOrderEntity.class, quote.id()).lines.forEach(line -> line.quantity = line.quantity / 2);
        em.flush(); em.clear();
        var slot = sales.createInvoiceFrom(quote.id());
        assertTrue(sales.price(slot).totals().total().signum() < 0);
        sales.delete(slot.id());
        assertNull(store.find(slot.id()), "deleting the concept slotfactuur gives the advances back");
        assertEquals(SalesAdvanceBilling.Stage.ADVANCE, store.find(advance.id()).stage());
        var again = sales.createInvoiceFrom(quote.id());
        assertEquals(SalesAdvanceBilling.Stage.FINAL, store.find(again.id()).stage());
        assertEquals("De slotfactuur is lager dan de voorschotten; maak een creditnota op een voorschotfactuur",
                assertThrows(BusinessRuleException.class, () -> sales.issueInvoice(again.id())).getMessage());
    }

    @Test @TestTransaction
    void fiftyAndFiftyPercentPrepayAnOddCentTotalAndTheZeroSlotfactuurIsPaidYetCreditable() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        em.find(SalesOrderEntity.class, quote.id()).manualFreightEur = new BigDecimal("120.01");
        em.flush(); em.clear();
        BigDecimal base = Money.money(sales.price(sales.get(quote.id())).totals().total());
        assertEquals(1, base.unscaledValue().mod(java.math.BigInteger.TWO).intValue(), "an odd cent: each 50 % rounds up on its own");
        var first = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("50"), null, null)).id());
        var second = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("50"), null, null)).id());
        BigDecimal half = base.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        assertEquals(half, sales.price(first).totals().total());
        assertEquals(base.subtract(half), sales.price(second).totals().total(), "the second 50 % takes the rest, not a cent more");
        assertEquals(0, new BigDecimal("50").compareTo(store.find(second.id()).percentage()));
        assertEquals(VatTreatment.BINNENLAND, store.find(first.id()).vatTreatment(), "an issued advance keeps its VAT regime");
        assertEquals(0, new BigDecimal("21").compareTo(store.find(first.id()).vatRatePct()));

        var slot = sales.createInvoiceFrom(quote.id());
        assertEquals(0, sales.price(slot).totals().totalInclVat().signum());
        var issued = sales.issueInvoice(slot.id());
        assertEquals(QuoteStatus.BETAALD, issued.status(), "nothing left to pay: never overdue");
        assertNotNull(issued.paidAt());
        assertEquals(QuoteStatus.BETAALD, sales.get(slot.id()).status());

        /* Ten pieces short after the full prepayment: the slotfactuur takes the credit note. */
        BigDecimal gross = sales.price(first).totals().totalInclVat().add(sales.price(second).totals().totalInclVat());
        assertEquals(Money.money(gross), sales.proposeCreditNote(slot.id()).maxCreditInclVatEur());
        long red = issued.lines().stream().filter(line -> new BigDecimal("8.45").compareTo(line.unitPriceEur()) == 0)
                .findFirst().orElseThrow().productId();
        var note = sales.createCreditNote(slot.id(), new SalesOrderService.CreditNoteRequest(CreditReason.SHORT_DELIVERY,
                List.of(new SalesOrderService.CreditLine(red, 10, null)), List.of(), false, null));
        assertEquals(new BigDecimal("84.50"), sales.price(note).totals().total());
        var issuedNote = sales.issueInvoice(note.id());
        assertEquals(QuoteStatus.UITGEREIKT, issuedNote.status());
        assertEquals(Money.money(gross.subtract(sales.price(issuedNote).totals().totalInclVat())),
                sales.proposeCreditNote(slot.id()).maxCreditInclVatEur());
    }

    @Test @TestTransaction
    void aDeductedAdvanceIsPaidOnceItsNetIsPaidAndItsOwnCreditIsNoReceipt() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        var plain = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("1000"), null)).id());
        var offset = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("2000"), null)).id());
        sales.issueInvoice(sales.createCreditNote(plain.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(), List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal("100"))),
                false, null)).id());
        var offsetCredit = sales.issueInvoice(sales.createCreditNote(offset.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(), List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal("200"))),
                false, null)).id());
        Instant paidAt = Instant.now().minus(java.time.Duration.ofDays(2));
        LocalDate paidOn = paidAt.atZone(ZoneId.of("Europe/Brussels")).toLocalDate();
        /* The customer pays each advance net of its credit note; one credit is offset later, the other is not. */
        incoming.add(plain.id(), new IncomingPaymentService.Request(new BigDecimal("1089.00"), paidAt, "Europe/Brussels", "Netto"));
        incoming.add(offset.id(), new IncomingPaymentService.Request(new BigDecimal("2178.00"), paidAt, "Europe/Brussels", "Netto"));
        incoming.applyCredit(offsetCredit.id(), offset.id(), null);

        var listed = resource.get(quote.id()).advanceInvoices();
        assertEquals(new BigDecimal("100.00"), listed.getFirst().creditedExclEur());
        assertEquals(new BigDecimal("200.00"), listed.getLast().creditedExclEur());
        assertEquals(List.of(new SalesAdvanceBillingService.Receipt(paidOn, new BigDecimal("2178.00"))), listed.getLast().receipts(),
                "the offset of its own credit note is no receipt");

        var slot = sales.createInvoiceFrom(quote.id());
        var deductions = resource.get(slot.id()).advanceDeductions();
        assertEquals(new BigDecimal("1089.00"), deductions.getFirst().inclEur());
        assertEquals(paidOn, deductions.getFirst().paidOn(), "paid net of its credit note, although the credit is not offset");
        assertEquals(paidOn, deductions.getLast().paidOn(), "paid on the day the money came, not the day of the offset");
        assertEquals(List.of(new SalesAdvanceBillingService.Receipt(paidOn, new BigDecimal("2178.00"))), deductions.getLast().receipts());
    }

    @Test @TestTransaction
    void anAdvanceCreditedInFullNoLongerHoldsItsQuoteNorItsRoom() {
        var quote = quote("BE", "BE0000000000", Language.NL);
        var advance = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("1000"), null)).id());
        sales.issueInvoice(sales.createCreditNote(advance.id(), new SalesOrderService.CreditNoteRequest(CreditReason.PRICE_CORRECTION,
                List.of(), List.of(new SalesOrderService.CreditAmount("Deal afgeblazen", new BigDecimal("1000"))), false, null)).id());
        assertEquals(new BigDecimal("1000.00"), resource.get(quote.id()).advanceInvoices().getFirst().creditedExclEur());
        /* The deal fell through: the quote can go again, although the advance itself stays. */
        assertDoesNotThrow(() -> sales.requireDeletable(sales.get(quote.id())));
        assertDoesNotThrow(() -> sales.requireReopenable(sales.get(quote.id())));
        /* The credited advance bills nothing: the whole quote is room again. */
        var again = billing.createAdvanceInvoice(quote.id(), new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("100"), null, null));
        assertEquals(Money.money(sales.price(quote).totals().total()), sales.price(again).totals().total());
        assertEquals("Deze offerte heeft voorschotfactuur " + again.number() + "; verwijder of annuleer die eerst",
                assertThrows(BusinessRuleException.class, () -> sales.requireDeletable(sales.get(quote.id()))).getMessage());
    }

    @Test @TestTransaction
    void anAdvanceIssuedInAnotherVatRegimeStopsTheSlotfactuur() {
        var quote = quote("FR", "FR00000000000", Language.FR);
        var advance = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)).id());
        assertEquals(VatTreatment.INTRACOMMUNAUTAIR, store.find(advance.id()).vatTreatment());
        /* The buyer's VAT number is withdrawn after the advance: the quote and the advance now price taxed alike. */
        em.find(SalesEntities.CustomerEntity.class, quote.customerId()).vatNumber = null;
        em.flush(); em.clear();
        assertEquals(sales.price(sales.get(quote.id())).totals().vatTreatment(), sales.price(sales.get(advance.id())).totals().vatTreatment());
        assertEquals("Voorschotfactuur " + advance.number() + " heeft een andere btw-regeling dan de offerte",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(quote.id())).getMessage());
    }

    @Test @TestTransaction
    void theDocumentsPrintInTheCustomersLanguageWithTheDeductedAdvancesAndTheirPayment() throws Exception {
        var quote = quote("FR", "FR00000000000", Language.FR);
        var first = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)).id());
        var second = sales.issueInvoice(billing.createAdvanceInvoice(quote.id(),
                new SalesAdvanceBillingService.AdvanceRequest(null, new BigDecimal("500"), null)).id());
        assertEquals(0, sales.price(first).totals().vatRatePct().signum(), "an intra-EU buyer: 0 %");
        incoming.add(first.id(), new IncomingPaymentService.Request(sales.price(first).totals().totalInclVat(),
                Instant.now().minusSeconds(60), "Europe/Brussels", "Acompte"));

        String advance = text(first.id(), null);
        assertTrue(advance.contains("facture d'acompte"), advance);
        assertTrue(advance.contains("acompte sur le devis " + quote.number().toLowerCase()), advance);
        assertFalse(advance.contains("voorschot 30 %"), "the Dutch line text stays off a French document: " + advance);
        String dutchAdvance = text(first.id(), Language.NL);
        assertTrue(dutchAdvance.contains("voorschotfactuur"), dutchAdvance);
        assertTrue(dutchAdvance.contains("voorschot op offerte " + quote.number().toLowerCase()), dutchAdvance);

        var slot = sales.createInvoiceFrom(quote.id());
        String french = text(slot.id(), null);
        assertTrue(french.contains("facture finale"), french);
        assertTrue(french.contains("factures d'acompte déduites"), french);
        assertTrue(french.contains(first.number().toLowerCase()) && french.contains(second.number().toLowerCase()), french);
        assertTrue(french.contains("payée le"), french);
        assertTrue(french.contains("en attente de paiement"), french);
        assertTrue(french.contains("solde ht"), french);
        assertTrue(french.contains("reste à payer"), french);
        assertFalse(french.contains("voorschotfactuur " + first.number().toLowerCase() + " van"), "the deduction prints once, in its block: " + french);
        String dutch = text(slot.id(), Language.NL);
        assertTrue(dutch.contains("slotfactuur"), dutch);
        assertTrue(dutch.contains("verrekende voorschotfacturen"), dutch);
        assertTrue(dutch.contains("betaald op"), dutch);
        assertTrue(dutch.contains("nog open"), dutch);
        assertTrue(dutch.contains("nog te betalen"), dutch);

        mailbox.clear();
        quotes.send(second.id(), null);
        assertEquals("Facture d'acompte " + second.number() + " d'Enrosed",
                mailbox.getMailsSentTo("advance@example.invalid").getFirst().getSubject());
    }

    /* ---------------------------------------------------------------- helpers */

    /** The document's text, lower case on one line; the PDF lands in output/pdf as a visual QA specimen. */
    private String text(long id, Language language) throws Exception {
        var document = quotes.document(id, language, SalesPdfOptions.defaults());
        java.nio.file.Path directory = java.nio.file.Path.of("output", "pdf");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.write(directory.resolve("enrosed-advance-billing-" + sales.get(id).number().replaceAll("[^A-Za-z0-9]", "-")
                + "-" + (language == null ? "customer" : language.code()) + ".pdf"), document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            return new PDFTextStripper().getText(pdf).toLowerCase().replaceAll("\\s+", " ");
        }
    }

    private static Customer customer(String country, String vatNumber, Language language) {
        return new Customer(null, "Advance " + UUID.randomUUID(), "Buyer", "advance@example.invalid", null,
                vatNumber, country, language, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now());
    }

    /** A regular quote: 240 × 8,45 and 480 × 9,00 with a fixed freight. */
    private SalesOrder quote(String country, String vatNumber, Language language) {
        var buyer = customers.create(customer(country, vatNumber, language));
        long red = product("Rood · 25 cm", "RED");
        long white = product("Wit · 25 cm", "WHITE");
        var created = sales.create(buyer.id(), country, "DAP");
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = new BigDecimal("120.00");
        stored.freight = FreightState.AANGEVULD;
        line(stored, red, 240, "8.45", "5.10");
        line(stored, white, 480, "9.00", "5.40");
        em.flush(); em.clear();
        return sales.get(created.id());
    }

    private long product(String name, String sku) {
        var product = new ProductEntity();
        product.sku = sku + "-" + UUID.randomUUID();
        product.name = name;
        product.active = true;
        product.piecesPerCarton = 12;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        em.persist(product);
        em.flush();
        return product.id;
    }

    private static void line(SalesOrderEntity order, long productId, int quantity, String price, String cost) {
        var line = new SalesOrderLineEntity();
        line.order = order; line.productId = productId; line.quantity = quantity;
        line.unitPriceEur = new BigDecimal(price); line.unitCostEur = new BigDecimal(cost);
        order.lines.add(line);
    }

    private static String euro(BigDecimal amount) {
        return String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }
}
