package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.mailer.MockMailbox;
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

/**
 * A credit note reverses part of an issued invoice: positive lines capped by
 * what was invoiced, priced without carton rounding or tiers, and a life of
 * its own next to the invoice it corrects.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class CreditNoteServiceTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService financing;
    @Inject ProductService products;
    @Inject SalesSplits splits;
    @Inject SalesOrderResource resource;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject SalesRepositories.Events events;
    @Inject EntityManager em;
    @Inject MockMailbox mailbox;

    private record Fixture(SalesOrder invoice, long customerId, long red, long white) {}

    @Test @TestTransaction
    void aCreditNoteTakesTheInvoicedNetPricesCostsAndFreightWithoutCartonRoundingOrTiers() {
        var f = issuedInvoice();
        PricedOrder invoiced = sales.price(f.invoice);
        BigDecimal redNet = netUnit(invoiced, f.red);
        BigDecimal freight = Money.money(invoiced.totals().freight().add(invoiced.totals().handling()));

        SalesOrder cn = sales.createCreditNote(f.invoice.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.SHORT_DELIVERY,
                List.of(new SalesOrderService.CreditLine(f.red, 6, null), new SalesOrderService.CreditLine(f.white, 12, new BigDecimal("4"))),
                List.of(new SalesOrderService.CreditAmount("Extra vergoeding", new BigDecimal("10"))), true, " Te weinig ontvangen "));

        assertTrue(cn.isCreditNote());
        assertTrue(cn.isClaimDocument());
        assertFalse(cn.isInvoice(), "a credit note is never the invoice");
        assertEquals(QuoteStatus.CONCEPT, cn.status());
        assertEquals(f.invoice.id(), cn.creditedInvoiceId());
        assertEquals(CreditReason.SHORT_DELIVERY, cn.creditReason());
        assertTrue(cn.number().startsWith("CN-" + LocalDate.now().getYear() + "-"), cn.number());
        assertEquals(MarkupMode.CONTAINER_COST, cn.markupMode());
        assertEquals(0, cn.orderMarkupPct().signum());
        assertNull(cn.extraDiscountPct());
        assertEquals(FreightPricingStrategy.FIXED, cn.freightPricingStrategy());
        assertEquals(0, cn.manualFreightEur().signum());
        assertEquals(FreightState.AANGEVULD, cn.freight());
        assertEquals(SalesPurpose.STANDARD, cn.purpose());
        assertEquals(SalesPaymentPlan.FULL, cn.paymentPlan());
        assertEquals(f.customerId, cn.customerId());
        assertEquals(LocalDate.now(), cn.invoiceDueDate());
        assertEquals("Te weinig ontvangen", cn.notes());
        assertNull(cn.sourceQuoteId(), "the credit link never borrows the quote link");
        assertNull(cn.goodsReturnedAt());

        var redLine = cn.lines().stream().filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow();
        var whiteLine = cn.lines().stream().filter(l -> l.productId().equals(f.white)).findFirst().orElseThrow();
        assertEquals(6, redLine.quantity());
        assertEquals(redNet, redLine.unitPriceEur(), "the invoiced net unit price, four decimals");
        assertEquals(new BigDecimal("5.1000"), redLine.unitCostEur(), "the cost snapshot of the original line");
        assertEquals(0, redLine.manualDiscountPct().signum());
        assertEquals(new BigDecimal("4.0000"), whiteLine.unitPriceEur(), "a lower price is allowed");
        assertEquals(2, cn.extraLines().size());
        assertEquals(new BigDecimal("10.00"), cn.extraLines().get(0).total());
        assertEquals("Vracht en handling · " + f.invoice.number(), cn.extraLines().get(1).description());
        assertEquals(freight, cn.extraLines().get(1).total());

        PricedOrder priced = sales.price(cn);
        var pricedRed = priced.lines().stream().filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow();
        assertEquals(6, pricedRed.quantity(), "six of a twelve-per-carton product stay six");
        assertEquals(Money.money(redNet.multiply(BigDecimal.valueOf(6))), pricedRed.net());
        assertEquals(0, Money.nz(pricedRed.tierPercent()).signum(), "no tiers on a credit note");
        BigDecimal expectedTotal = Money.money(redNet.multiply(BigDecimal.valueOf(6)))
                .add(new BigDecimal("48.00")).add(new BigDecimal("10.00")).add(freight);
        assertEquals(expectedTotal, priced.totals().total());
        assertEquals(Money.money(Money.percentOf(expectedTotal, new BigDecimal("21"))), priced.totals().vatAmount());
        assertTrue(priced.validation().meetsMinimum(), "the minimum order value never applies to a credit note");
        assertEquals(new BigDecimal("95.40"), priced.totals().costTotal(), "6 × 5.10 and 12 × 5.40 of cost reversed");

        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.OPGEMAAKT
                && e.summary().equals("Creditnota opgemaakt op " + f.invoice.number() + " · Te weinig geleverd")));
        assertTrue(events.findByOrder(f.invoice.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GECREDITEERD
                && e.summary().startsWith("Creditnota " + cn.number() + " in concept · € ")));
        assertEquals(1L, ((Number) em.createNativeQuery("select count(*) from activity_log where entity_id = :id and action = 'CREDITED'")
                .setParameter("id", Long.toString(f.invoice.id())).getSingleResult()).longValue());

        var view = resource.get(f.invoice.id());
        assertEquals(1, view.creditNotes().size());
        assertEquals(cn.id(), view.creditNotes().getFirst().id());
        assertEquals(QuoteStatus.CONCEPT, view.creditNotes().getFirst().status());
        assertEquals(0, view.creditedEur().signum(), "a concept credits nothing yet");
        var creditView = resource.get(cn.id());
        assertEquals(f.invoice.number(), creditView.creditedInvoiceNumber());
        assertEquals(QuoteStatus.UITGEREIKT, creditView.creditedInvoiceStatus());
        assertEquals(SalesPaymentSummary.Status.CREDIT, creditView.paymentSummary().status());
        assertEquals(priced.totals().totalInclVat().negate(), creditView.paymentSummary().invoiceTotalEur());
    }

    @Test @TestTransaction
    void anOrderLevelDiscountLowersTheCreditedUnitPriceAndAFullCreditStillReachesTheCap() {
        var f = issuedInvoice();
        em.find(SalesOrderEntity.class, f.invoice.id()).extraDiscountPct = new BigDecimal("5"); em.flush(); em.clear();
        SalesOrder invoice = sales.get(f.invoice.id());
        PricedOrder invoiced = sales.price(invoice);
        assertEquals(new BigDecimal("8.4500"), netUnit(invoiced, f.red), "the line itself carries no discount");
        assertEquals(new BigDecimal("317.40"), invoiced.totals().extraDiscountAmount(), "five percent come off the subtotal");

        /* The customer paid 8,45 less five percent per piece; that is what one piece credits. */
        assertEquals(new BigDecimal("8.0275"), sales.proposeCreditNote(invoice.id()).lines().stream()
                .filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow().netUnitPriceEur());
        var partial = sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.SHORT_DELIVERY,
                List.of(new SalesOrderService.CreditLine(f.red, 24, null)), List.of(), true, null));
        assertEquals(new BigDecimal("8.0275"), partial.lines().getFirst().unitPriceEur());
        assertEquals("De creditprijs per stuk kan niet hoger zijn dan de gefactureerde prijs (€ 8,0275)",
                refused(invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 1, new BigDecimal("8.03")))));
        var edit = sales.get(partial.id()).withLinesAndPallets(List.of(new SalesOrderLine(partial.lines().getFirst().id(), f.red, 24,
                new BigDecimal("8.03"), null, null, null)), List.of());
        assertEquals("De creditprijs per stuk kan niet hoger zijn dan de gefactureerde prijs (€ 8,0275)",
                assertThrows(BusinessRuleException.class, () -> sales.update(partial.id(), edit)).getMessage());

        /* Everything on every line and the freight reach the discounted invoice, a cent of rounding per line aside. */
        var rest = sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.CANCELLATION,
                List.of(new SalesOrderService.CreditLine(f.red, 216, null), new SalesOrderService.CreditLine(f.white, 480, null)),
                List.of(), false, null));
        assertEquals(new BigDecimal("8.5500"), lineOf(rest, f.white).unitPriceEur());
        BigDecimal credited = sales.price(partial).totals().totalInclVat().add(sales.price(rest).totals().totalInclVat());
        assertTrue(credited.subtract(invoiced.totals().totalInclVat()).abs().compareTo(new BigDecimal("0.03")) <= 0,
                credited + " vs " + invoiced.totals().totalInclVat());
        assertEquals("Samen met " + partial.number() + ", " + rest.number() + " zou meer gecrediteerd worden dan factuur " + invoice.number()
                + " (€ " + String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f", invoiced.totals().totalInclVat()) + " incl. btw)",
                refused(invoice.id(), amount("Nog een euro", "1")));
    }

    @Test @TestTransaction
    void anEditedCreditNoteTakesItsLineCostsFromTheInvoiceNotFromTheClient() {
        var f = issuedInvoice();
        em.find(ProductEntity.class, f.white).landedCostEur = new BigDecimal("7.25"); em.flush(); em.clear();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        SalesOrderLine red = sales.get(cn.id()).lines().getFirst();

        /* The client sends a cost of its own on the stored line and on a line it adds without an id. */
        var edited = sales.update(cn.id(), sales.get(cn.id()).withLinesAndPallets(List.of(red.withUnitCost(BigDecimal.ONE),
                new SalesOrderLine(null, f.white, 12, new BigDecimal("9"), null, null, new BigDecimal("9.99"))), List.of()));
        assertEquals(new BigDecimal("5.1000"), lineOf(edited, f.red).unitCostEur(), "a stored line keeps the cost it was written with");
        assertEquals(new BigDecimal("5.4000"), lineOf(edited, f.white).unitCostEur(), "a new line takes the invoice's snapshot");

        /* Removed and added again without a cost, the line still reads the invoice, not today's product cost. */
        sales.update(cn.id(), sales.get(cn.id()).withLinesAndPallets(List.of(lineOf(edited, f.red)), List.of()));
        var again = sales.update(cn.id(), sales.get(cn.id()).withLinesAndPallets(List.of(lineOf(edited, f.red),
                new SalesOrderLine(null, f.white, 12, new BigDecimal("9"), null, null)), List.of()));
        assertEquals(new BigDecimal("5.4000"), lineOf(again, f.white).unitCostEur());
        assertEquals(new BigDecimal("187.20"), sales.price(again).totals().costTotal(), "24 × 5.10 and 12 × 5.40 of cost reversed");
    }

    @Test @TestTransaction
    void aPriceCorrectionCarriesNoCostAndAnEditKeepsTheStoredIdentity() {
        var f = issuedInvoice();
        SalesOrder cn = sales.createCreditNote(f.invoice.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(new SalesOrderService.CreditLine(f.red, 240, new BigDecimal("0.50"))),
                List.of(), false, null));
        assertEquals(0, cn.lines().getFirst().unitCostEur().signum());
        assertEquals(new BigDecimal("120.00"), sales.price(cn).totals().total());
        assertEquals(0, sales.price(cn).totals().costTotal().signum(), "a price correction reverses revenue, not cost");
        sales.issueInvoice(cn.id());
        var accounting = financing.accounting(sales.get(cn.id()), sales.price(sales.get(cn.id())));
        assertEquals(new BigDecimal("-120.00"), accounting.recognizedRevenueEur());
        assertEquals(0, accounting.recognizedCostEur().signum());
        assertEquals(new BigDecimal("-120.00"), accounting.recognizedProfitEur());
        assertEquals(-240, accounting.recognizedQuantity());
        quotes.reopen(cn.id());

        /* An edit from a client that sends another customer and country, a different mode, a discount and freight changes none of it. */
        SalesOrder stored = sales.get(cn.id());
        long other = customers.create(new Customer(null, "Other " + UUID.randomUUID(), "Buyer", null, null,
                "DE000000000", "DE", Language.NL, "Weg 1", "10115", "Berlin", "DAP", null, null, LocalDate.now())).id();
        SalesOrder tampered = new SalesOrder(stored.id(), stored.number(), other, "DE",
                stored.orderDate(), stored.validUntil(), stored.status(), "EXW", "60 dagen", "Nieuwe notitie",
                MarkupMode.PRODUCT, new BigDecimal("45"), new BigDecimal("5"), "Korting", null, null, null, 0, null, null, null,
                stored.internalNotes(), stored.deliveryTerms(), FreightState.BEREKEND, new BigDecimal("80"), stored.loadMode(),
                stored.palletProfile(), null, FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, stored.invoiceDueDate(), null, null, null,
                List.of(new SalesOrderLine(stored.lines().getFirst().id(), f.red, 200, new BigDecimal("0.50"), new BigDecimal("10"), null, null)),
                List.of()).withExtraLines(List.of(new SalesExtraLine("Extra", BigDecimal.ONE, new BigDecimal("5"))));
        SalesOrder edited = sales.update(cn.id(), tampered);
        assertTrue(edited.isCreditNote());
        assertEquals(f.invoice.id(), edited.creditedInvoiceId());
        assertEquals(CreditReason.PRICE_CORRECTION, edited.creditReason());
        assertEquals(MarkupMode.CONTAINER_COST, edited.markupMode());
        assertNull(edited.extraDiscountPct());
        assertEquals(FreightPricingStrategy.FIXED, edited.freightPricingStrategy());
        assertEquals(0, edited.manualFreightEur().signum());
        assertEquals("Nieuwe notitie", edited.notes());
        assertEquals(200, edited.lines().getFirst().quantity());
        assertEquals(0, edited.lines().getFirst().unitCostEur().signum(), "the zero cost survives an edit");
        assertEquals(0, edited.lines().getFirst().manualDiscountPct().signum());
        assertEquals(new BigDecimal("105.00"), sales.price(edited).totals().total());
        assertEquals(f.customerId, edited.customerId(), "the customer follows the credited invoice");
        assertEquals("BE", edited.countryCode());
        assertEquals(stored.incoterm(), edited.incoterm());
        assertEquals(stored.paymentTerms(), edited.paymentTerms());
        assertEquals(new BigDecimal("21.00"), sales.price(edited).totals().vatRatePct(), "still priced in the invoice's regime");

        /* A line added to a price correction carries no cost either, whatever the client sent. */
        var extended = sales.update(cn.id(), sales.get(cn.id()).withLinesAndPallets(List.of(edited.lines().getFirst(),
                new SalesOrderLine(null, f.white, 10, new BigDecimal("0.50"), null, null, new BigDecimal("9.99"))), List.of()));
        assertEquals(0, lineOf(extended, f.white).unitCostEur().signum());
        assertEquals(0, sales.price(extended).totals().costTotal().signum());

        var negative = tampered.withExtraLines(List.of(new SalesExtraLine("Korting", BigDecimal.ONE, new BigDecimal("-5"))));
        assertEquals("Op een creditnota staan alleen positieve bedragen",
                assertThrows(BusinessRuleException.class, () -> sales.update(cn.id(), negative)).getMessage());
        var tooMany = tampered.withLinesAndPallets(List.of(new SalesOrderLine(stored.lines().getFirst().id(), f.red, 241,
                new BigDecimal("0.50"), null, null, null)), List.of());
        assertTrue(assertThrows(BusinessRuleException.class, () -> sales.update(cn.id(), tooMany)).getMessage()
                .contains("zijn nog 240 stuks te crediteren op " + f.invoice.number()));
    }

    @Test @TestTransaction
    void everyGuardSpeaksBeforeAnythingIsCredited() {
        var f = issuedInvoice();
        var quote = sales.create(f.customerId, "BE", "DAP", DocumentType.OFFERTE);
        assertEquals("Een creditnota maak je op een factuur", refused(quote.id(), amount("Korting", "10")));
        var concept = sales.create(f.customerId, "BE", "DAP", DocumentType.FACTUUR);
        assertEquals("Reik factuur " + concept.number() + " eerst uit. Een concept pas je gewoon aan, daar hoort geen creditnota bij",
                refused(concept.id(), amount("Korting", "10")));
        var dead = issuedInvoice();
        em.find(SalesOrderEntity.class, dead.invoice.id()).status = QuoteStatus.GEANNULEERD; em.flush(); em.clear();
        assertEquals("Factuur " + dead.invoice.number() + " is niet actief", refused(dead.invoice.id(), amount("Korting", "10")));

        assertEquals("Kies minstens één regel of bedrag om te crediteren", refused(f.invoice.id(),
                new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(), List.of(), false, null)));
        assertTrue(refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(9_999_999L, 1, null)))
                .endsWith(" staat niet op factuur " + f.invoice.number()));
        assertEquals("Vul een aantal van minstens 1 in", refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 0, null))));
        assertEquals("Van Rood · 25 cm zijn nog 240 stuks te crediteren op " + f.invoice.number(),
                refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 241, null))));
        assertEquals("De creditprijs per stuk kan niet hoger zijn dan de gefactureerde prijs (€ 8,4500)",
                refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 1, new BigDecimal("8.46")))));
        assertEquals("De creditprijs per stuk kan niet hoger zijn dan de gefactureerde prijs (€ 8,4500)",
                refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 1, BigDecimal.ZERO))));
        assertEquals("Een creditbedrag is groter dan nul", refused(f.invoice.id(), amount("Korting", "0")));

        /* Concept credit notes already count: per product, for the freight and for the total. */
        var first = sales.createCreditNote(f.invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.SHORT_DELIVERY,
                List.of(new SalesOrderService.CreditLine(f.red, 24, null)), List.of(), true, null));
        assertEquals("Van Rood · 25 cm zijn nog 216 stuks te crediteren op " + f.invoice.number(),
                refused(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 217, null))));
        assertEquals("De vracht van " + f.invoice.number() + " is al gecrediteerd", refused(f.invoice.id(),
                new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(), List.of(), true, null)));
        BigDecimal invoiced = sales.price(f.invoice).totals().totalInclVat();
        String cap = refused(f.invoice.id(), amount("Alles", invoiced.toPlainString()));
        assertEquals("Samen met " + first.number() + " zou meer gecrediteerd worden dan factuur " + f.invoice.number()
                + " (€ " + String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f", invoiced) + " incl. btw)", cap);

        /* Everything on every line reaches the cap exactly, a cent of rounding per line aside. */
        var rest = sales.createCreditNote(f.invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.CANCELLATION,
                List.of(new SalesOrderService.CreditLine(f.red, 216, null), new SalesOrderService.CreditLine(f.white, 480, null)),
                List.of(), false, null));
        BigDecimal credited = sales.price(first).totals().totalInclVat().add(sales.price(rest).totals().totalInclVat());
        assertTrue(credited.subtract(invoiced).abs().compareTo(new BigDecimal("0.03")) <= 0, credited + " vs " + invoiced);

        assertEquals("Van een creditnota maak je geen factuur",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(first.id())).getMessage());
        assertEquals("Een creditnota maak je op een factuur", refused(first.id(), amount("Nog eens", "1")));
    }

    @Test @TestTransaction
    void theProposalPrefillsTheShortageOfTheReceivedContainerAndWhatWasAlreadyCredited() {
        var f = issuedInvoice();
        var container = receivedContainer(f, 216, 12, 480, 0);
        em.find(SalesOrderEntity.class, f.invoice.id()).sourcePurchaseOrderId = container; em.flush(); em.clear();

        var proposal = sales.proposeCreditNote(f.invoice.id());
        assertEquals(f.invoice.number(), proposal.invoiceNumber());
        assertEquals(CreditReason.SHORT_DELIVERY, proposal.suggestedReason());
        assertEquals(new BigDecimal("21.00"), proposal.vatRatePct());
        assertFalse(proposal.vatExempt());
        assertEquals(0, proposal.alreadyCreditedInclVatEur().signum());
        assertEquals(proposal.invoiceTotalInclVatEur(), proposal.maxCreditInclVatEur());
        assertEquals(proposal.invoiceTotalInclVatEur(), proposal.remainingEur(), "nothing received yet");
        assertNotNull(proposal.container());
        assertTrue(proposal.container().received());
        assertEquals(24, proposal.container().missingPieces());
        assertEquals(12, proposal.container().damagedPieces());
        var red = proposal.lines().stream().filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow();
        assertEquals(240, red.invoicedQuantity());
        assertEquals(36, red.suggestedQuantity(), "24 missing and 12 damaged");
        assertEquals(new BigDecimal("8.4500"), red.netUnitPriceEur());
        assertEquals(new BigDecimal("5.1000"), red.unitCostEur());
        var white = proposal.lines().stream().filter(l -> l.productId().equals(f.white)).findFirst().orElseThrow();
        assertEquals(0, white.suggestedQuantity());
        assertFalse(proposal.freightAlreadyCredited());
        assertTrue(proposal.freightEur().signum() > 0);
        assertNull(proposal.partnerShortfall(), "only advances carry a partner proposal");

        var cn = sales.createCreditNote(f.invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.SHORT_DELIVERY,
                List.of(new SalesOrderService.CreditLine(f.red, 24, null)), List.of(), true, null));
        var again = sales.proposeCreditNote(f.invoice.id());
        assertEquals(24, again.lines().stream().filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow().alreadyCreditedQuantity());
        assertEquals(36, again.lines().stream().filter(l -> l.productId().equals(f.red)).findFirst().orElseThrow().suggestedQuantity(),
                "the shortage stays the suggestion; the remaining quantity is the ceiling");
        assertTrue(again.freightAlreadyCredited());
        assertEquals(sales.price(cn).totals().totalInclVat(), again.alreadyCreditedInclVatEur());

        /* A container that has not arrived suggests nothing; the invoice still proposes its lines. */
        var g = issuedInvoice();
        var open = sales.proposeCreditNote(g.invoice.id());
        assertNull(open.container());
        assertEquals(CreditReason.RETURN, open.suggestedReason());
        assertTrue(open.lines().stream().allMatch(l -> l.suggestedQuantity() == 0));
        var concept = sales.create(f.customerId, "BE", "DAP", DocumentType.FACTUUR);
        assertEquals("Reik factuur " + concept.number() + " eerst uit. Een concept pas je gewoon aan, daar hoort geen creditnota bij",
                assertThrows(BusinessRuleException.class, () -> sales.proposeCreditNote(concept.id())).getMessage());
    }

    @Test @TestTransaction
    void theCreditNoteLivesNextToItsInvoiceIssueSendReopenCancelDeleteAndTheInvoiceGuards() {
        var f = issuedInvoice();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        BigDecimal total = sales.price(cn).totals().totalInclVat();

        /* The invoice cannot leave its shape while a live credit note exists, a concept included. */
        String guard = "Factuur " + f.invoice.number() + " heeft creditnota " + cn.number() + "; annuleer of verwijder die eerst";
        assertEquals(guard, assertThrows(BusinessRuleException.class, () -> sales.delete(f.invoice.id())).getMessage());
        assertEquals(guard, assertThrows(BusinessRuleException.class, () -> quotes.reopen(f.invoice.id())).getMessage());

        var issued = sales.issueInvoice(cn.id());
        assertEquals(QuoteStatus.UITGEREIKT, issued.status());
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.UITGEREIKT
                && e.summary().equals("Creditnota uitgereikt zonder verzending")));
        assertTrue(events.findByOrder(f.invoice.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GECREDITEERD
                && e.summary().startsWith("Creditnota " + cn.number() + " uitgereikt · € ")));
        assertEquals(total, resource.get(f.invoice.id()).creditedEur(), "an issued credit note credits the invoice");
        assertEquals(Money.money(total).negate(), incoming.summary(issued, sales.price(issued)).invoiceTotalEur());

        assertEquals(QuoteStatus.VERZONDEN, sales.markInvoiceSent(cn.id()).status());
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.VERSTUURD
                && e.summary().equals("Creditnota gemarkeerd als verstuurd")));
        assertEquals(1, events.findByOrder(cn.id()).stream().filter(e -> e.type() == QuoteEvent.Type.UITGEREIKT).count(),
                "marking an issued credit note as sent does not issue it twice");

        var reopened = quotes.reopen(cn.id());
        assertEquals(QuoteStatus.CONCEPT, reopened.status());
        assertEquals(cn.validUntil(), reopened.validUntil(), "no validity reset on a claim document");
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.HEROPEND
                && e.summary().equals("Creditnota heropend naar concept zonder e-mail")));
        assertTrue(events.findByOrder(f.invoice.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GECREDITEERD
                && e.summary().equals("Creditnota " + cn.number() + " terug naar concept")));
        assertEquals("Een conceptcreditnota verwijder je in plaats van ze te annuleren",
                assertThrows(BusinessRuleException.class, () -> quotes.cancel(cn.id(), null, false)).getMessage());

        sales.issueInvoice(cn.id());
        mailbox.clear();
        var cancelled = quotes.cancel(cn.id(), "Verkeerde regel", true);
        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        assertTrue(mailbox.getMailsSentTo("credit@example.invalid").isEmpty(), "a credit note is never cancelled by mail");
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GEANNULEERD
                && e.summary().equals("Creditnota geannuleerd") && "Verkeerde regel".equals(e.detail())));
        assertTrue(events.findByOrder(f.invoice.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GECREDITEERD
                && e.summary().equals("Creditnota " + cn.number() + " geannuleerd")));
        assertTrue(sales.liveCreditNotesOf(f.invoice.id()).isEmpty(), "cancelled notes are dead everywhere");
        assertEquals(0, resource.get(f.invoice.id()).creditedEur().signum());
        assertTrue(resource.get(f.invoice.id()).creditNotes().isEmpty());
        assertDoesNotThrow(() -> quotes.reopen(f.invoice.id()), "the invoice is free again");
        var next = sales.createCreditNote(sales.issueInvoice(f.invoice.id()).id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        assertEquals(sequence(cn.number()) + 1, sequence(next.number()), "a cancelled number stays reserved");
        assertEquals(QuoteStatus.CONCEPT, quotes.reopen(cn.id()).status(), "a cancelled credit note can be reopened");
        assertEquals("Factuur " + f.invoice.number() + " heeft creditnota " + cn.number() + "; annuleer of verwijder die eerst",
                assertThrows(BusinessRuleException.class, () -> quotes.reopen(f.invoice.id())).getMessage());

        assertEquals("Maak een nieuwe creditnota vanuit de factuur",
                assertThrows(BusinessRuleException.class, () -> sales.duplicate(next.id())).getMessage());
    }

    @Test @TestTransaction
    void anIssuedThenReopenedCreditNoteStaysInTheBooks() {
        var f = issuedInvoice();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        sales.issueInvoice(cn.id());
        assertEquals(QuoteStatus.CONCEPT, quotes.reopen(cn.id()).status());
        assertEquals("Een eerder uitgereikte creditnota blijft bewaard, ook nadat ze heropend is",
                assertThrows(BusinessRuleException.class, () -> sales.delete(cn.id())).getMessage());
    }

    @Test @TestTransaction
    void anUnusedConceptCreditNoteGoesToTheTrashAndAnyCreditNoteCanBeArchived() {
        var f = issuedInvoice();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        assertEquals(1, sales.liveCreditNotesOf(f.invoice.id()).size());
        assertNotNull(sales.archive(cn.id()).archivedAt());
        assertNull(sales.unarchive(cn.id()).archivedAt());
        sales.delete(cn.id());
        assertThrows(be.enrosed.shared.NotFoundException.class, () -> sales.get(cn.id()));
        assertTrue(sales.liveCreditNotesOf(f.invoice.id()).isEmpty(), "a trashed credit note frees the invoice");
        assertEquals(QuoteStatus.CONCEPT, quotes.reopen(f.invoice.id()).status());
    }

    @Test @TestTransaction
    void aCreditNoteHasNoShipmentFreightSplitPackingSlipPartnerLinkOrReceipt() {
        var f = issuedInvoice();
        var cn = sales.issueInvoice(sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null))).id());
        assertEquals("Een creditnota verzendt geen goederen; boek een retour",
                assertThrows(BusinessRuleException.class, () -> sales.shipGoods(cn.id())).getMessage());
        assertEquals("Een creditnota heeft geen vracht of levering",
                assertThrows(BusinessRuleException.class, () -> sales.updateFreight(cn.id(), FreightState.AANGEVULD,
                        new BigDecimal("10"), FreightPricingStrategy.FIXED, null, null)).getMessage());
        assertEquals("Een creditnota splits je niet", splits.eligibility(cn.id()).reason());
        assertFalse(splits.eligibility(cn.id()).allowed());
        assertEquals("Een creditnota heeft geen pakbon",
                assertThrows(BusinessRuleException.class, () -> quotes.packingSlip(cn.id())).getMessage());
        assertEquals("De partnerkoppeling van een creditnota volgt de factuur",
                assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(cn.id(),
                        new SalesOrderService.PartnerDealRequest(null, null, null))).getMessage());
        assertEquals("Op een creditnota registreer je geen ontvangst; verreken ze met een factuur of noteer een terugbetaling",
                assertThrows(BusinessRuleException.class, () -> sales.markInvoicePaid(cn.id())).getMessage());
        assertEquals("Creditnota " + cn.number() + " staat op uitgereikt; alleen conceptcreditnota's kunnen gewijzigd worden",
                assertThrows(BusinessRuleException.class, () -> sales.update(cn.id(), sales.get(cn.id()))).getMessage());
        assertFalse(OpenQuoteWork.closedQuoteIds(List.of(sales.get(cn.id()))).contains(cn.id()));
        assertTrue(sales.list().stream().filter(o -> o.id().equals(cn.id())).allMatch(SalesOrder::isClaimDocument));
    }

    @Test @TestTransaction
    void returnedGoodsGoBackIntoStockOnceAndOnlyAfterTheInvoiceShipped() {
        var f = issuedInvoice();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 30, null)));
        assertEquals("Alleen een uitgereikte creditnota met productregels neemt goederen terug",
                assertThrows(BusinessRuleException.class, () -> sales.returnGoods(cn.id())).getMessage());
        sales.issueInvoice(cn.id());
        assertEquals("De goederen van " + f.invoice.number() + " zijn nog niet als verzonden geboekt; er valt niets terug te nemen",
                assertThrows(BusinessRuleException.class, () -> sales.returnGoods(cn.id())).getMessage());
        sales.shipGoods(f.invoice.id());
        int before = products.get(f.red).stockQuantity();

        var returned = sales.returnGoods(cn.id());
        assertNotNull(returned.goodsReturnedAt());
        assertEquals(before + 30, products.get(f.red).stockQuantity());
        assertTrue(products.stockMovements(f.red).stream().anyMatch(m -> m.kind() == be.enrosed.catalog.domain.StockMovement.Kind.SALE_RETURN
                && m.delta() == 30 && cn.number().equals(m.reference())));
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GOEDEREN_RETOUR
                && e.summary().equals("Goederen terug in voorraad · 30 st")));
        assertEquals(1L, ((Number) em.createNativeQuery("select count(*) from activity_log where entity_id = :id and action = 'STOCK_RETURNED'")
                .setParameter("id", Long.toString(cn.id())).getSingleResult()).longValue());
        assertEquals("De goederen van " + cn.number() + " staan al terug in voorraad",
                assertThrows(BusinessRuleException.class, () -> sales.returnGoods(cn.id())).getMessage());
        assertEquals(before + 30, products.get(f.red).stockQuantity());
        assertEquals("De retour van deze creditnota staat al in de voorraad; ze kan niet terug naar concept",
                assertThrows(BusinessRuleException.class, () -> quotes.reopen(cn.id())).getMessage());
        assertEquals("De retour staat al in de voorraad; deze creditnota kan niet meer geannuleerd worden",
                assertThrows(BusinessRuleException.class, () -> quotes.cancel(cn.id(), null, false)).getMessage());

        var amountOnly = sales.issueInvoice(sales.createCreditNote(f.invoice.id(), amount("Korting", "5")).id());
        assertEquals("Alleen een uitgereikte creditnota met productregels neemt goederen terug",
                assertThrows(BusinessRuleException.class, () -> sales.returnGoods(amountOnly.id())).getMessage());
    }

    @Test @TestTransaction
    void theCreditNoteMailNamesBothNumbersAndExplainsTheOpenBalance() {
        var f = issuedInvoice();
        var cn = sales.createCreditNote(f.invoice.id(), lines(new SalesOrderService.CreditLine(f.red, 24, null)));
        String sentence = quotes.creditNoteSentence(cn, sales.price(cn), customers.get(f.customerId));
        assertTrue(sentence.startsWith("Openstaand tegoed: " + be.enrosed.shared.DocumentFormat.eur(sales.price(cn).totals().totalInclVat())), sentence);
        mailbox.clear();
        var sent = quotes.send(cn.id(), "Met excuses");
        assertEquals(QuoteStatus.VERZONDEN, sent.status());
        var mails = mailbox.getMailsSentTo("credit@example.invalid");
        assertEquals(1, mails.size());
        assertEquals("Creditnota " + cn.number() + " van Enrosed", mails.getFirst().getSubject());
        assertTrue(mails.getFirst().getHtml().contains("In bijlage vindt u creditnota " + cn.number() + " op factuur " + f.invoice.number() + "."),
                mails.getFirst().getHtml());
        assertTrue(mails.getFirst().getHtml().contains("Openstaand tegoed"), mails.getFirst().getHtml());
        assertEquals(1, mails.getFirst().getAttachments().size());
        assertTrue(mails.getFirst().getAttachments().getFirst().getName().startsWith("CN-"));

        /* Mailed straight from concept, the credit note is issued by that mail: both histories say so, and it stays in the books. */
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.UITGEREIKT
                && e.summary().equals("Creditnota uitgereikt")));
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.VERSTUURD
                && e.summary().equals("Creditnota gemarkeerd als verstuurd")));
        assertTrue(events.findByOrder(f.invoice.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.GECREDITEERD
                && e.summary().startsWith("Creditnota " + cn.number() + " uitgereikt · € ")));
        assertEquals(sales.price(sent).totals().totalInclVat(), resource.get(f.invoice.id()).creditedEur());
        assertEquals(QuoteStatus.CONCEPT, quotes.reopen(cn.id()).status());
        assertEquals("Alleen een conceptcreditnota die nog nooit verstuurd of gebruikt is kan verwijderd worden",
                assertThrows(BusinessRuleException.class, () -> sales.delete(cn.id())).getMessage());
        assertTrue(sales.get(cn.id()).isCreditNote(), "a mailed credit note stays in the books, reopened or not");
    }

    /* ---------------------------------------------------------------- helpers */

    private String refused(long invoiceId, SalesOrderService.CreditNoteRequest request) {
        return assertThrows(BusinessRuleException.class, () -> sales.createCreditNote(invoiceId, request)).getMessage();
    }

    private static SalesOrderService.CreditNoteRequest lines(SalesOrderService.CreditLine... lines) {
        return new SalesOrderService.CreditNoteRequest(CreditReason.RETURN, List.of(lines), List.of(), false, null);
    }

    private static SalesOrderService.CreditNoteRequest amount(String description, String amount) {
        return new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(),
                List.of(new SalesOrderService.CreditAmount(description, new BigDecimal(amount))), false, null);
    }

    private static SalesOrderLine lineOf(SalesOrder order, long productId) {
        return order.lines().stream().filter(l -> productId == l.productId()).findFirst().orElseThrow();
    }

    private static BigDecimal netUnit(PricedOrder priced, long productId) {
        var line = priced.lines().stream().filter(l -> l.productId().equals(productId)).findFirst().orElseThrow();
        return line.net().divide(BigDecimal.valueOf(line.quantity()), 4, RoundingMode.HALF_UP);
    }

    private static int sequence(String number) {
        return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
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

    /** An issued Belgian invoice: 240 × 8,45 and 480 × 9,00, fixed freight, 21 % VAT. */
    private Fixture issuedInvoice() {
        var customer = customers.create(new Customer(null, "Credit " + UUID.randomUUID(), "Buyer", "credit@example.invalid", null,
                "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        long red = product("Rood · 25 cm", "RED");
        long white = product("Wit · 25 cm", "WHITE");
        var created = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = new BigDecimal("120.00");
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        line(stored, red, 240, "8.45", "5.10");
        line(stored, white, 480, "9.00", "5.40");
        em.flush(); em.clear();
        return new Fixture(sales.get(created.id()), customer.id(), red, white);
    }

    /** A received container for the fixture's products with the given counts. */
    private long receivedContainer(Fixture f, int redReceived, int redDamaged, int whiteReceived, int whiteDamaged) {
        var supplier = suppliers.save(new Supplier(null, "Credit supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.status = PurchaseOrderStatus.BESTELD;
        for (long[] row : new long[][] {{f.red, 240}, {f.white, 480}}) {
            var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = row[0]; line.quantity = (int) row[1];
            line.exwPrice = new BigDecimal("5"); line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line);
        }
        em.flush(); em.clear();
        purchases.receive(purchase.id(), new PurchaseOrderService.Receipt(List.of(
                new PurchaseOrderService.ReceivedLine(f.red, redReceived, redDamaged),
                new PurchaseOrderService.ReceivedLine(f.white, whiteReceived, whiteDamaged)), false, null, LocalDate.now(), null));
        em.flush(); em.clear();
        return purchase.id();
    }
}
