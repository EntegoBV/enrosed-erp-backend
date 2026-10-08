package be.enrosed.sales.adapter.out.document;

import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesAdvanceBillingService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.CreditReason;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invoice or credit note a customer downloads from their account is the
 * document as it was issued: its own amount, and not a word about what was
 * received, offset or refunded since. The staff download of the same
 * document keeps saying how it stands today.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class PdfAccountCopyRenderTest {
    /** Every text that tells how a document was paid, offset or refunded. */
    private static final List<String> PAYMENT_TEXTS = List.of("paymentReceived", "paymentRefunded", "paymentSettled",
            "paymentOverpaid", "paymentCredit", "creditNoteOffset", "creditNoteRefunded", "creditNoteOpen",
            "creditNoteSettled", "advancePaidOn", "advanceOpen");

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesAdvanceBillingService advances;
    @Inject IncomingPaymentService incoming;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    @Test
    void theOptionsOfTheAccountCopyHideReceiptsAndEveryOlderCallerKeepsThem() {
        assertEquals(new SalesPdfOptions(true, true, true, true, false, false, false, false), SalesPdfOptions.accountCopy());
        assertFalse(SalesPdfOptions.accountCopy().includeReceipts());
        assertFalse(SalesPdfOptions.accountCopy().includePaymentDetails());
        assertTrue(SalesPdfOptions.defaults().includeReceipts());
        assertTrue(new SalesPdfOptions(true, true, true, true, false, false, false).includeReceipts());
        assertTrue(SalesPdfOptions.forPackingSlip(true, true).includeReceipts());
    }

    @Test
    void aPartlyPaidAFullyPaidAndAnOverpaidInvoicePrintTheirOwnAmountToPay() throws Exception {
        SalesOrder invoice = issuedInvoice();
        BigDecimal total = shop.inTransaction(() -> sales.price(invoice)).totals().totalInclVat();
        assertNoPaymentState(invoice, total);

        pay(invoice, new BigDecimal("500.00"));
        String partly = staffCopy(invoice, Language.NL);
        assertTrue(partly.contains("ontvangen: " + eur(new BigDecimal("500.00"))), partly);
        assertTrue(partly.contains(eur(total.subtract(new BigDecimal("500.00")))), "staff read what is still due: " + partly);
        assertNoPaymentState(invoice, total);
        assertFalse(accountCopy(invoice, Language.NL).contains(eur(total.subtract(new BigDecimal("500.00")))),
                "the remaining amount would give the payment away");

        pay(invoice, total.subtract(new BigDecimal("500.00")));
        assertTrue(staffCopy(invoice, Language.NL).contains(text(Language.NL, "paymentSettled")));
        assertTrue(staffCopy(invoice, Language.EN).contains(text(Language.EN, "paymentSettled")));
        assertNoPaymentState(invoice, total);

        pay(invoice, new BigDecimal("75.00"));
        assertTrue(staffCopy(invoice, Language.NL).contains(text(Language.NL, "paymentOverpaid")));
        assertNoPaymentState(invoice, total);
    }

    @Test
    void anOffsetAndARefundedCreditNotePrintTheirOwnTotal() throws Exception {
        SalesOrder invoice = issuedInvoice();
        SalesOrder offset = issuedCreditNote(invoice, "100.00");
        BigDecimal credit = shop.inTransaction(() -> sales.price(offset)).totals().totalInclVat();
        incoming.applyCredit(offset.id(), invoice.id(), credit);
        String staff = staffCopy(offset, Language.NL);
        assertTrue(staff.contains(text(Language.NL, "creditNoteOffset")) && staff.contains(text(Language.NL, "creditNoteSettled")), staff);
        assertNoPaymentState(offset, credit);
        assertTrue(accountCopy(offset, Language.NL).contains(invoice.number().toLowerCase()), "it still names the invoice it corrects");
        /* The invoice it was offset against says nothing about that either. */
        assertNoPaymentState(invoice, shop.inTransaction(() -> sales.price(invoice)).totals().totalInclVat());

        SalesOrder refunded = issuedCreditNote(invoice, "50.00");
        BigDecimal refund = shop.inTransaction(() -> sales.price(refunded)).totals().totalInclVat();
        incoming.add(refunded.id(), new IncomingPaymentService.Request(refund, Instant.now().minusSeconds(60), "Europe/Brussels",
                "Terugbetaling", IncomingPaymentService.Direction.REFUND, null));
        assertTrue(staffCopy(refunded, Language.NL).contains(text(Language.NL, "creditNoteRefunded")));
        assertNoPaymentState(refunded, refund);
    }

    @Test
    void aSlotfactuurListsItsAdvancesWithoutSayingWhetherTheyWerePaid() throws Exception {
        Shop.Placed order = shop.place();
        SalesOrder advance = sales.issueInvoice(advances.createAdvanceInvoice(order.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)).id());
        pay(advance, shop.inTransaction(() -> sales.price(advance)).totals().totalInclVat());
        SalesOrder open = sales.issueInvoice(advances.createAdvanceInvoice(order.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("20"), null, null)).id());
        SalesOrder slot = sales.issueInvoice(sales.createInvoiceFrom(order.id()).id());

        String staff = staffCopy(slot, Language.NL);
        assertTrue(staff.contains(text(Language.NL, "advancePaidOn")) && staff.contains(text(Language.NL, "advanceOpen")), staff);
        String copy = accountCopy(slot, Language.NL);
        assertTrue(copy.contains(advance.number().toLowerCase()) && copy.contains(open.number().toLowerCase()),
                "the deducted advances are still listed: " + copy);
        assertNoPaymentState(slot, shop.inTransaction(() -> sales.price(slot)).totals().totalInclVat());
        assertNoPaymentState(advance, shop.inTransaction(() -> sales.price(advance)).totals().totalInclVat());
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** The account copy, in Dutch and English, tells nothing of payments and prints the document's own amount. */
    private void assertNoPaymentState(SalesOrder document, BigDecimal ownAmount) throws Exception {
        for (Language language : List.of(Language.NL, Language.EN)) {
            String copy = accountCopy(document, language);
            for (String key : PAYMENT_TEXTS)
                assertFalse(copy.contains(text(language, key)), key + " (" + language + ") in the account copy of "
                        + document.number() + ": " + copy);
            assertTrue(copy.contains(DocumentText.of(language).get(document.isCreditNote() ? "creditAmount" : "toPay").toLowerCase()
                    + " " + eur(ownAmount)), "the amount of the document itself, " + eur(ownAmount) + ": " + copy);
        }
    }

    /** The stored text up to its first placeholder, as it reads in a PDF: lower case, a label with its colon. */
    private static String text(Language language, String key) {
        String value = DocumentText.of(language).get(key);
        int placeholder = value.indexOf('%');
        String fixed = (placeholder < 0 ? value : value.substring(0, placeholder)).strip().toLowerCase();
        return key.equals("paymentReceived") || key.equals("paymentRefunded") ? fixed + ":" : fixed;
    }

    private String accountCopy(SalesOrder document, Language language) throws Exception {
        return textOf(shop.inTransaction(() -> quotes.documentForAccount(sales.get(document.id()), language)).content());
    }

    private String staffCopy(SalesOrder document, Language language) throws Exception {
        return textOf(shop.inTransaction(() -> quotes.document(document.id(), language)).content());
    }

    private static String textOf(byte[] content) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(pdf).toLowerCase().replaceAll("\\s+", " ");
        }
    }

    private static String eur(BigDecimal amount) {
        return DocumentFormat.eur(amount).toLowerCase();
    }

    private SalesOrder issuedInvoice() {
        return sales.issueInvoice(sales.createInvoiceFrom(shop.place().id()).id());
    }

    private SalesOrder issuedCreditNote(SalesOrder invoice, String amount) {
        return sales.issueInvoice(sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(),
                List.of(new SalesOrderService.CreditAmount("Correctie", new BigDecimal(amount))), false, null)).id());
    }

    private void pay(SalesOrder invoice, BigDecimal amount) {
        incoming.add(invoice.id(), new IncomingPaymentService.Request(amount, Instant.now().minusSeconds(60),
                "Europe/Brussels", "Betaling"));
    }
}
