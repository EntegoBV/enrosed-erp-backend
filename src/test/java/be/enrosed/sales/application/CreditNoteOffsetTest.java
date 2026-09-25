package be.enrosed.sales.application;

import be.enrosed.finance.banking.BankStatementService;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesPaymentEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
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

/**
 * The money side of a credit note: it reads as a negative claim, takes
 * refunds and offsets but never receipts, and an offset is one atomic pair
 * of rows that moves no bank money.
 */
@QuarkusTest
class CreditNoteOffsetTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject BankStatementService bank;
    @Inject SalesRepositories.Events events;
    @Inject EntityManager em;
    private static final Instant AT = Instant.parse("2026-09-20T10:00:00Z");

    @Test @TestTransaction
    void aCreditNoteIsANegativeClaimThatTakesRefundsAndNeverReceipts() {
        long customer = customer();
        var invoice = issued(customer, "100");
        var cn = sales.issueInvoice(credit(invoice, "40").id());
        var summary = summary(cn.id());
        assertEquals(new BigDecimal("-48.40"), summary.invoiceTotalEur(), "40 plus 21 % VAT, negated");
        assertEquals(0, summary.remainingEur().signum());
        assertEquals(new BigDecimal("48.40"), summary.creditEur());
        assertEquals(new BigDecimal("48.40"), summary.refundableEur());
        assertEquals(SalesPaymentSummary.Status.CREDIT, summary.status());
        assertTrue(summary.instalments().isEmpty());

        String receipt = "Op een creditnota registreer je geen ontvangst; verreken ze met een factuur of noteer een terugbetaling";
        assertEquals(receipt, assertThrows(BusinessRuleException.class, () -> incoming.add(cn.id(), receipt("10"))).getMessage());
        assertEquals(receipt, assertThrows(BusinessRuleException.class, () -> incoming.markPaid(cn.id())).getMessage());
        assertThrows(BusinessRuleException.class, () -> incoming.add(cn.id(), refund("48.41")), "never more than the credit");

        incoming.add(cn.id(), refund("48.40"));
        assertEquals(SalesPaymentSummary.Status.PAID, summary(cn.id()).status());
        assertEquals(0, summary(cn.id()).creditEur().signum());
        assertEquals(QuoteStatus.BETAALD, sales.get(cn.id()).status(), "fully refunded reads as settled");
        assertNotNull(sales.get(cn.id()).paidAt());
        var concept = credit(invoice, "5");
        assertEquals("Reik eerst de creditnota uit voordat je een terugbetaling of verrekening registreert",
                assertThrows(BusinessRuleException.class, () -> incoming.add(concept.id(), refund("1"))).getMessage());
    }

    @Test @TestTransaction
    void anOffsetIsOneCrossLinkedPairThatSettlesBothDocumentsAndCanBeRetractedAsOne() {
        long customer = customer();
        var creditedInvoice = issued(customer, "100");
        var target = issued(customer, "100");
        var cn = sales.issueInvoice(credit(creditedInvoice, "40").id());

        var settled = incoming.applyCredit(cn.id(), target.id(), null);
        assertEquals(QuoteStatus.BETAALD, settled.status(), "the whole balance fitted on the open invoice");
        assertEquals(0, summary(cn.id()).creditEur().signum());
        assertEquals(new BigDecimal("-48.40"), summary(cn.id()).receivedEur());
        assertEquals(new BigDecimal("48.40"), summary(target.id()).receivedEur());
        assertEquals(new BigDecimal("72.60"), summary(target.id()).remainingEur());
        assertEquals(SalesPaymentSummary.Status.PARTIAL, summary(target.id()).status());
        var creditRow = incoming.forOrder(cn.id()).getFirst();
        var invoiceRow = incoming.forOrder(target.id()).getFirst();
        assertTrue(creditRow.isOffset()); assertTrue(invoiceRow.isOffset());
        assertEquals(target.id(), creditRow.offsetOrderId());
        assertEquals(cn.id(), invoiceRow.offsetOrderId());
        assertEquals(invoiceRow.id(), creditRow.offsetPaymentId());
        assertEquals(creditRow.id(), invoiceRow.offsetPaymentId());
        assertEquals("Verrekening " + cn.number() + " met " + target.number(), creditRow.reference());
        assertNull(creditRow.bankAccount(), "an offset is never a bank movement");
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.VERREKEND
                && e.summary().equals("Verrekend € 48,40 met " + target.number())));
        assertTrue(events.findByOrder(target.id()).stream().anyMatch(e -> e.type() == QuoteEvent.Type.VERREKEND
                && e.summary().equals("Verrekend € 48,40 met " + cn.number())));
        var listed = incoming.list(null).stream().filter(row -> row.id().equals(creditRow.id())).findFirst().orElseThrow();
        assertEquals(target.number(), listed.offsetOrderNumber());
        assertEquals(invoiceRow.id(), listed.offsetPaymentId());
        assertEquals(DocumentType.CREDITNOTA, listed.docType());

        assertEquals("Een verrekening corrigeer je door ze in te trekken en opnieuw te maken",
                assertThrows(BusinessRuleException.class, () -> incoming.update(cn.id(), creditRow.id(), refund("10"))).getMessage());
        assertEquals("Er valt niets te verrekenen: de creditnota is afgehandeld of de factuur is al betaald",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), target.id(), null)).getMessage());

        /* Retracting one half voids the other, on both documents, and the history stays. */
        incoming.delete(target.id(), invoiceRow.id());
        assertTrue(incoming.forOrder(cn.id()).isEmpty());
        assertTrue(incoming.forOrder(target.id()).isEmpty());
        assertNotNull(em.find(SalesPaymentEntity.class, creditRow.id()).voidedAt);
        assertNotNull(em.find(SalesPaymentEntity.class, invoiceRow.id()).voidedAt);
        assertEquals(new BigDecimal("48.40"), summary(cn.id()).creditEur());
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(cn.id()).status());
        assertEquals(new BigDecimal("121.00"), summary(target.id()).remainingEur());
        assertTrue(incoming.hasHistory(cn.id()) && incoming.hasHistory(target.id()));
        assertTrue(events.findByOrder(cn.id()).stream().anyMatch(e -> "Verrekening ingetrokken".equals(e.summary())));
        assertTrue(events.findByOrder(target.id()).stream().anyMatch(e -> "Verrekening ingetrokken".equals(e.summary())));
        assertThrows(BusinessRuleException.class, () -> quotes.reopen(cn.id()), "money history keeps the credit note issued");
        assertThrows(BusinessRuleException.class, () -> quotes.cancel(cn.id(), null, false));

        /* A partial offset leaves the rest open; a second one may follow, never beyond the smaller side. */
        incoming.applyCredit(cn.id(), target.id(), new BigDecimal("30"));
        assertEquals(new BigDecimal("18.40"), summary(cn.id()).creditEur());
        assertEquals(new BigDecimal("91.00"), summary(target.id()).remainingEur());
        assertEquals("Je kunt hoogstens € 18,40 verrekenen",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), target.id(), new BigDecimal("18.41"))).getMessage());
        incoming.applyCredit(cn.id(), target.id(), new BigDecimal("18.40"));
        assertEquals(QuoteStatus.BETAALD, sales.get(cn.id()).status());
    }

    @Test @TestTransaction
    void anOffsetNeedsAnIssuedCreditNoteAndAnOpenInvoiceOfTheSameCustomer() {
        long customer = customer();
        var invoice = issued(customer, "100");
        var concept = credit(invoice, "40");
        assertEquals("Reik de creditnota eerst uit",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(concept.id(), invoice.id(), null)).getMessage());
        var cn = sales.issueInvoice(concept.id());
        assertEquals("Verrekenen kan alleen met een andere factuur",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), cn.id(), null)).getMessage());
        var draft = sales.create(customer, "BE", "DAP", DocumentType.FACTUUR);
        assertEquals("Factuur " + draft.number() + " is niet actief of nog een concept",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), draft.id(), null)).getMessage());
        var stranger = issued(customer(), "100");
        assertEquals("Verrekenen kan alleen met een factuur van dezelfde klant",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), stranger.id(), null)).getMessage());
        assertEquals("Reik de creditnota eerst uit",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(invoice.id(), cn.id(), null)).getMessage(),
                "the roles never swap");
        var paid = issued(customer, "100");
        incoming.add(paid.id(), receipt("121"));
        assertEquals("Er valt niets te verrekenen: de creditnota is afgehandeld of de factuur is al betaald",
                assertThrows(BusinessRuleException.class, () -> incoming.applyCredit(cn.id(), paid.id(), null)).getMessage());
        assertTrue(incoming.forOrder(cn.id()).isEmpty(), "a refused offset writes nothing");
    }

    @Test @TestTransaction
    void theBankOnlyEverPaysACreditNoteOutAndNeverMatchesAnOffset() {
        long customer = customer();
        var invoice = issued(customer, "100");
        var cn = sales.issueInvoice(credit(invoice, "40").id());

        bank.create(entry("KBC", "-48.40", BankStatementService.Direction.OUTGOING, "refund"));
        var outgoing = bank.list().getFirst();
        var match = bank.suggestions(outgoing.id).stream().filter(m -> m.salesOrderId() == cn.id()).findFirst().orElseThrow();
        assertEquals(new BigDecimal("48.40"), match.openEur());
        assertNull(match.existingPaymentId());
        assertEquals("Nieuwe terugbetaling op creditnota " + cn.number(), match.reason());
        bank.allocate(outgoing.id, new BankStatementService.Allocation(cn.id(), null));
        assertEquals(QuoteStatus.BETAALD, sales.get(cn.id()).status());
        assertEquals(0, summary(cn.id()).creditEur().signum());
        bank.unallocate(outgoing.id);
        assertEquals(new BigDecimal("48.40"), summary(cn.id()).creditEur());
        assertTrue(incoming.forOrder(cn.id()).isEmpty());

        bank.create(entry("KBC", "48.40", BankStatementService.Direction.INCOMING, "incoming"));
        var incomingLine = bank.list().stream().filter(row -> row.amountEur.signum() > 0).findFirst().orElseThrow();
        assertTrue(bank.suggestions(incomingLine.id).stream().noneMatch(m -> m.salesOrderId() == cn.id()),
                "money coming in never lands on a credit note");
        assertEquals("Op een creditnota registreer je geen ontvangst; verreken ze met een factuur of noteer een terugbetaling",
                assertThrows(BusinessRuleException.class, () -> bank.allocate(incomingLine.id,
                        new BankStatementService.Allocation(cn.id(), null))).getMessage());

        var target = issued(customer, "100");
        incoming.applyCredit(cn.id(), target.id(), null);
        var offsetRow = incoming.forOrder(target.id()).getFirst();
        assertTrue(bank.suggestions(incomingLine.id).stream().noneMatch(m -> offsetRow.id().equals(m.existingPaymentId())),
                "an offset row is never an existing-payment match");
        assertEquals("Een verrekening is geen bankbeweging",
                assertThrows(BusinessRuleException.class, () -> bank.allocate(incomingLine.id,
                        new BankStatementService.Allocation(target.id(), offsetRow.id()))).getMessage());
    }

    @Test @TestTransaction
    void aNegativeExtraLineInvoiceStillBehavesAsItAlwaysDid() {
        var legacy = issued(customer(), "-100");
        assertFalse(legacy.isCreditNote());
        assertEquals(SalesPaymentSummary.Status.CREDIT, summary(legacy.id()).status());
        assertEquals(new BigDecimal("121.00"), summary(legacy.id()).creditEur());
        assertThrows(BusinessRuleException.class, () -> incoming.markPaid(legacy.id()));
        incoming.add(legacy.id(), refund("121"));
        assertEquals(QuoteStatus.BETAALD, sales.get(legacy.id()).status());
    }

    /* ---------------------------------------------------------------- helpers */

    private SalesPaymentSummary summary(long id) { var order = sales.get(id); return incoming.summary(order, sales.price(order)); }
    private static IncomingPaymentService.Request receipt(String amount) {
        return new IncomingPaymentService.Request(new BigDecimal(amount), AT, "Europe/Brussels", "Bank");
    }
    private static IncomingPaymentService.Request refund(String amount) {
        return new IncomingPaymentService.Request(new BigDecimal(amount), AT, "Europe/Brussels", "Terugbetaling",
                IncomingPaymentService.Direction.REFUND, null);
    }
    private static BankStatementService.ManualRequest entry(String account, String amount, BankStatementService.Direction direction, String id) {
        return new BankStatementService.ManualRequest(account, new BigDecimal(amount).abs(), direction, AT, "Europe/Brussels",
                "Enrosed", "Klant", id + "-" + UUID.randomUUID());
    }
    private SalesOrder credit(SalesOrder invoice, String amount) {
        return sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(),
                List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal(amount))), false, null));
    }
    private long customer() {
        return customers.create(new Customer(null, "Offset " + UUID.randomUUID(), "Fin", null, null, "BE0000000000", "BE", Language.NL,
                "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now())).id();
    }
    private SalesOrder issued(long customer, String amount) {
        var created = sales.create(customer, "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":" + amount + "}]";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        return sales.get(created.id());
    }
}
