package be.enrosed.finance.invoices;

import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.PurchaseInvoiceService.PurchaseInvoiceView;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErpInvoiceRowsStatusTest {
    private static final LocalDate TODAY = LocalDate.of(2031, 3, 15);
    private static final Instant AT = Instant.parse("2031-03-01T10:00:00Z");

    static PurchaseInvoiceView view(String amount, Currency currency, String amountEur, String paidEur) {
        return new PurchaseInvoiceView(1L, 41L, Payee.SUPPLIER, "Leverancier", "CI-1", LocalDate.of(2031, 3, 1),
                new BigDecimal(amount), currency, amountEur == null ? null : new BigDecimal(amountEur), null, null, null, "x",
                AT, true, false, List.of(), new BigDecimal(paidEur));
    }

    static PurchasePayment pay(long id, String amount, Currency currency, String amountEur, boolean settles,
                               PaymentTerms.Moment instalment) {
        return new PurchasePayment(id, 41L, LocalDate.of(2031, 3, 2), new BigDecimal(amount), currency, new BigDecimal(amountEur),
                "Betaling", "x", AT, Payee.SUPPLIER, settles, instalment);
    }

    @Test
    void aUsdInvoicePaidInFullAtOtherRatesIsPaid() {
        PurchaseInvoiceView usd = view("18420.00", Currency.USD, "16985.20", "16500.00");
        List<PurchasePayment> payments = List.of(pay(1, "5526.00", Currency.USD, "4950.00", false, null),
                pay(2, "12894.00", Currency.USD, "11550.00", false, null));
        assertEquals("PAID", ErpInvoiceRows.containerInvoiceStatus(usd, payments),
                "the dollars reach the invoice; euro values never decide");
        assertEquals("PARTIAL", ErpInvoiceRows.containerInvoiceStatus(usd, payments.subList(0, 1)));
        assertEquals("PARTIAL", ErpInvoiceRows.containerInvoiceStatus(usd,
                List.of(pay(3, "18420.00", Currency.EUR, "16500.00", false, null))), "euros are not dollars");
    }

    @Test
    void aEuroInvoicePartlyPaidIsPartialAndNothingPaidIsOpen() {
        PurchaseInvoiceView eur = view("410.00", Currency.EUR, null, "200.00");
        assertEquals("PARTIAL", ErpInvoiceRows.containerInvoiceStatus(eur, List.of(pay(1, "200.00", Currency.EUR, "200.00", false, null))));
        assertEquals("PAID", ErpInvoiceRows.containerInvoiceStatus(eur, List.of(pay(1, "409.99", Currency.EUR, "409.99", false, null))),
                "within a cent");
        assertEquals("OPEN", ErpInvoiceRows.containerInvoiceStatus(view("410.00", Currency.EUR, null, "0.00"), List.of()));
    }

    @Test
    void aSettlingPaymentOfTheWholeGroupPaysItButAMilestoneDoesNotByItself() {
        PurchaseInvoiceView eur = view("1000.00", Currency.EUR, null, "300.00");
        assertEquals("PAID", ErpInvoiceRows.containerInvoiceStatus(eur, List.of(pay(1, "300.00", Currency.EUR, "300.00", true, null))));
        assertEquals("PARTIAL", ErpInvoiceRows.containerInvoiceStatus(eur,
                List.of(pay(1, "300.00", Currency.EUR, "300.00", true, PaymentTerms.Moment.ORDERED))));
    }

    static SalesOrder order(DocumentType type, QuoteStatus status, LocalDate due) {
        return new SalesOrder(7L, "F-2031-0001", 2L, "BE", TODAY.minusDays(20), TODAY.plusDays(30), status, "DAP", null, null,
                MarkupMode.PRODUCT, new BigDecimal("45"), null, null, null, null, null, 0,
                null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.PICKUP, null, null, null,
                type, due, null, null, null,
                List.of(), List.of());
    }

    @Test
    void salesInvoiceStatuses() {
        SalesOrder open = order(DocumentType.FACTUUR, QuoteStatus.UITGEREIKT, TODAY.plusDays(5));
        assertEquals("OPEN", ErpInvoiceRows.salesStatus(open, new BigDecimal("121.00"), BigDecimal.ZERO, TODAY));
        assertEquals("PARTIAL", ErpInvoiceRows.salesStatus(open, new BigDecimal("121.00"), new BigDecimal("21.00"), TODAY));
        assertEquals("PAID", ErpInvoiceRows.salesStatus(open, new BigDecimal("121.00"), new BigDecimal("121.00"), TODAY));
        assertEquals("PAID", ErpInvoiceRows.salesStatus(open, new BigDecimal("121.00"), new BigDecimal("130.00"), TODAY));
        SalesOrder late = order(DocumentType.FACTUUR, QuoteStatus.UITGEREIKT, TODAY.minusDays(1));
        assertEquals("OVERDUE", ErpInvoiceRows.salesStatus(late, new BigDecimal("121.00"), new BigDecimal("21.00"), TODAY));
        assertEquals("PAID", ErpInvoiceRows.salesStatus(order(DocumentType.FACTUUR, QuoteStatus.BETAALD, TODAY.minusDays(1)),
                new BigDecimal("121.00"), BigDecimal.ZERO, TODAY), "marked paid");
    }

    @Test
    void salesCreditNoteStatuses() {
        SalesOrder credit = order(DocumentType.CREDITNOTA, QuoteStatus.UITGEREIKT, null);
        assertEquals("OPEN", ErpInvoiceRows.salesStatus(credit, new BigDecimal("-48.40"), BigDecimal.ZERO, TODAY),
                "credit still due");
        assertEquals("OPEN", ErpInvoiceRows.salesStatus(credit, new BigDecimal("-48.40"), new BigDecimal("-30.00"), TODAY));
        assertEquals("SETTLED", ErpInvoiceRows.salesStatus(credit, new BigDecimal("-48.40"), new BigDecimal("-48.40"), TODAY));
    }

    @Test
    void docLabelsAndFileKinds() {
        assertEquals("Commercial invoice", ErpInvoiceRows.docLabel(null, Payee.SUPPLIER));
        assertEquals("Factuur", ErpInvoiceRows.docLabel(null, Payee.LOGISTICS));
        assertEquals("Proforma (PI)", ErpInvoiceRows.docLabel(document(PurchaseDocument.Kind.PROFORMA_INVOICE, null), Payee.SUPPLIER));
        assertEquals("Factuur transport", ErpInvoiceRows.docLabel(document(PurchaseDocument.Kind.OTHER, " Factuur transport "), Payee.LOGISTICS));
        assertEquals("Factuur", ErpInvoiceRows.docLabel(document(PurchaseDocument.Kind.OTHER, " "), Payee.OTHER));
        assertEquals("PDF", ErpInvoiceRows.fileKind("CI.PDF", null));
        assertEquals("PDF", ErpInvoiceRows.fileKind("scan", "application/pdf"));
        assertEquals("XML", ErpInvoiceRows.fileKind("ubl.xml", "application/octet-stream"));
        assertEquals("IMAGE", ErpInvoiceRows.fileKind("IMG_2034.jpeg", "image/jpeg"));
        assertEquals("FILE", ErpInvoiceRows.fileKind("sheet.xlsx", "application/vnd.ms-excel"));
    }

    private static PurchaseDocument document(PurchaseDocument.Kind kind, String label) {
        return new PurchaseDocument(5L, 41L, kind, label, "f.pdf", "application/pdf", 10, "k", null, "x", AT);
    }
}
