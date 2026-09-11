package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.PdfFonts;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.PurchaseReconciliationCalculator;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PdfPurchasePaymentsRendererTest {
    private final PdfPurchasePaymentsRenderer renderer = new PdfPurchasePaymentsRenderer(new PdfFonts(), new Brand());

    @Test
    void firstInstalmentCloseKeepsLaterMilestonesOpenAndLabelsItsLimitedScope() throws Exception {
        var order = instalmentOrder();
        var costing = PdfPurchaseRendererRenderTest.costing(1);
        var payable = instalmentPayable();
        var payments = List.of(instalmentPayment(PaymentTerms.Moment.ORDERED));
        var report = new PurchaseReconciliationCalculator().calculate(order, costing, payable, payments);
        var supplier = report.streams().stream().filter(row -> row.payee() == PurchasePayment.Payee.SUPPLIER).findFirst().orElseThrow();
        assertEquals(0, supplier.remainingEur().compareTo(new BigDecimal("41734")));
        assertEquals(0, supplier.settledSavingEur().compareTo(new BigDecimal("886")));
        assertFalse(supplier.explicitlySettled());
        assertFalse(supplier.finalized());
        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        Files.createDirectories(Path.of("target/payment-pdf-qa"));
        Files.write(Path.of("target/payment-pdf-qa/instalment-scope.pdf"), document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf);
            for (String required : List.of("41.734,00", "886,00", "17.886,00", "23.848,00",
                    "Slotbetaling termijn: bij bestelling", "Termijn nog open", "Voorlopig")) {
                assertTrue(text.contains(required), required + " missing from " + text);
            }
            assertFalse(text.contains("Slotbetaling hele groep:"), text);
        }
    }

    @Test
    void legacyGlobalCloseAndDeletedPaymentStayDistinctFromInstalmentClose() {
        var order = instalmentOrder();
        var costing = PdfPurchaseRendererRenderTest.costing(1);
        var global = List.of(instalmentPayment(null));
        var report = new PurchaseReconciliationCalculator().calculate(order, costing, instalmentPayable(), global);
        String html = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), report, global);
        assertTrue(html.contains("Slotbetaling hele groep: Leverancier"), html);
        var reset = new PurchaseReconciliationCalculator().calculate(order, costing, instalmentPayable(), List.of());
        String resetHtml = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), reset, List.of());
        assertEquals(0, reset.totals().remainingEur().compareTo(new BigDecimal("59620")));
        assertTrue(resetHtml.contains("59.620,00"), resetHtml);
        assertFalse(resetHtml.contains("Betaling #1"), resetHtml);
        assertFalse(resetHtml.contains("Slotbetaling hele groep:"), resetHtml);
    }

    static PurchaseOrder instalmentOrder() {
        return new PurchaseOrder(1L, "PO-2026-001", "Termijnafspraak 30/30/40", 7L,
                LocalDate.of(2026, 9, 1), PurchaseOrderStatus.BESTELD, ContainerType.FORTY_HQ,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                Currency.EUR, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Allocation.VALUE, Allocation.VALUE, Allocation.VALUE, Allocation.VALUE,
                "Ningbo", "Rotterdam", null, true, null, null, null, false,
                PaymentTerms.CUSTOM, null, null, null,
                List.of(new PurchaseOrderLine(1L, 1L, 252, BigDecimal.TEN, Currency.EUR, BigDecimal.ZERO, 252)))
                .withPaymentSplit(new BigDecimal("30"), new BigDecimal("30"), new BigDecimal("40"));
    }

    static PurchaseOrderService.Payable instalmentPayable() {
        return new PurchaseOrderService.Payable(new BigDecimal("59620"), BigDecimal.ZERO,
                BigDecimal.ZERO, false, false);
    }

    static PurchasePayment instalmentPayment(PaymentTerms.Moment due) {
        return new PurchasePayment(1L, 1L, LocalDate.of(2026, 9, 10), new BigDecimal("17000"), Currency.EUR,
                new BigDecimal("17000"), "Eerste betaling", "Browsercontrole", Instant.parse("2026-09-10T08:00:00Z"),
                PurchasePayment.Payee.SUPPLIER, true, due);
    }

    @Test
    void partialSettlementShowsSavingsOpenBalanceUnitCostsAndOriginalPayments() throws Exception {
        var costing = PdfPurchaseRendererRenderTest.costing(4);
        PurchaseOrder order = order(4);
        var payable = new PurchaseOrderService.Payable(new BigDecimal("34504.94"), new BigDecimal("11534.86"),
                BigDecimal.ZERO, false, false);
        List<PurchasePayment> payments = List.of(
                payment(1, "1/3 bij bestelling", "10007.44", PurchasePayment.Payee.SUPPLIER, false),
                payment(2, "2/3 bij vertrek", "23942.73", PurchasePayment.Payee.SUPPLIER, true),
                payment(3, "Inklaring & transport <origineel>", "400.00", PurchasePayment.Payee.LOGISTICS, false));
        var report = new PurchaseReconciliationCalculator().calculate(order, costing, payable, payments);
        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        Files.createDirectories(Path.of("target/payment-pdf-qa"));
        Files.write(Path.of("target/payment-pdf-qa/partial-settlement.pdf"), document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            assertEquals(3, pdf.getNumberOfPages(), "summary, product costing and the payment ledger");
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("554,77"), text);
            assertTrue(text.contains("11.134,86"), text);
            assertTrue(text.contains("Voorlopig"), text);
            assertTrue(text.contains("23.942,73"), text);
            assertTrue(text.contains("Inklaring & transport <origineel>"), text);
            assertTrue(text.contains("Met opslag"), text);
            assertTrue(text.contains("Slotbetaling"), text);
            assertTrue(text.contains("preserved") || text.contains("Preserved"), text);
            assertTrue(pdf.getPage(0).getMediaBox().getWidth() < pdf.getPage(0).getMediaBox().getHeight());
        }
        assertEquals("PO-2026-001-betalingen-kostprijs.pdf", document.filename());
    }

    @Test
    void longProductAndPaymentRegistersPaginateWithoutLosingRows() throws Exception {
        int count = 36;
        var costing = PdfPurchaseRendererRenderTest.costing(count);
        var order = order(count);
        var payable = new PurchaseOrderService.Payable(new BigDecimal("34504.94"), BigDecimal.ZERO,
                BigDecimal.ZERO, false, false);
        var payments = IntStream.rangeClosed(1, 80).mapToObj(i -> payment(i, "Betaalregel " + i,
                "500.00", PurchasePayment.Payee.SUPPLIER, i == 80)).toList();
        var report = new PurchaseReconciliationCalculator().calculate(order, costing, payable, payments);
        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        Files.createDirectories(Path.of("target/payment-pdf-qa"));
        Files.write(Path.of("target/payment-pdf-qa/long-settlement.pdf"), document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 5);
            String text = new PDFTextStripper().getText(pdf);
            for (int i = 1; i <= 80; i++) assertTrue(text.contains("Betaalregel " + i), "missing payment " + i);
            assertTrue(text.contains("Rood 36"), text);
            assertTrue(text.contains("40.000,00"), text);
            assertTrue(text.contains("Hoger afgerekend"), text);
        }
    }

    private static PurchaseOrder order(int count) {
        var order = PdfPurchaseRendererRenderTest.order(count);
        var lines = PdfPurchaseRendererRenderTest.costing(count).lines().stream().map(line -> new PurchaseOrderLine(
                line.productId(), line.productId(), line.quantity(), BigDecimal.TEN, Currency.EUR,
                BigDecimal.ZERO, line.quantity())).toList();
        return order.withReceipt(order.status(), null, null, false, order.notes(), lines);
    }

    private static PurchasePayment payment(long id, String label, String value, PurchasePayment.Payee payee, boolean settles) {
        return new PurchasePayment(id, 1L, LocalDate.of(2026, 8, 1), new BigDecimal(value), Currency.EUR,
                new BigDecimal(value), label, "Emre", Instant.parse("2026-08-01T08:00:00Z"), payee, settles);
    }
}
