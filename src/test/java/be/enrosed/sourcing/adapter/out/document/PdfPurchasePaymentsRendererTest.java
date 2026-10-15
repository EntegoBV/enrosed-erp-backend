package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.PdfFonts;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.PurchaseReconciliationCalculator;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
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

    /** PO-2026-002: + € 61,28 on the 30 % and - € 60,78 on the 70 % net to € 0,50 too much, nothing to pay. */
    @Test
    void po35TermsNetSoNothingIsLeftToPayAndEachTermKeepsItsOwnDifference() throws Exception {
        var order = po35Order();
        var payable = po35Payable();
        var payments = po35Payments();
        var report = new PurchaseReconciliationCalculator().calculate(order,
                PdfPurchaseRendererRenderTest.costing(1), payable, payments);
        String html = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        assertTrue(html.contains("<span class=\"label\">Nog te betalen</span><span class=\"value\">€ 0,00</span>"), html);
        assertTrue(html.contains("<span class=\"label\">Verwachte eindkost</span><span class=\"value\">€ 31.048,54</span>"), html);
        assertTrue(html.contains("Betaald · € 0,50 te veel</span>"), "the supplier row: " + html);
        assertTrue(html.contains("Betaald · € 0,50 te veel · € 60,78 verrekend met 70% bij vertrek"), html);
        assertTrue(html.contains("Betaald · verrekend met 30% bij bestelling"), html);
        assertTrue(html.contains("+€ 61,28"), html);
        assertTrue(html.contains("€ -60,78"), html);
        assertFalse(html.contains("Termijn nog open"), html);

        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        Files.createDirectories(Path.of("target/payment-pdf-qa"));
        Files.write(Path.of("target/payment-pdf-qa/po35-netted-terms.pdf"), document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertTrue(text.contains("verrekend met 30% bij bestelling"), text);
            assertTrue(text.contains("61,28"), text);
            assertTrue(text.contains("60,78"), text);
        }

        var purchasePdf = PdfPurchaseRenderer.payableView(payments, payable, report);
        assertTrue(purchasePdf.overpaid(), "the purchase PDF shows the supplier paid too much, not an open balance");
        assertEquals(be.enrosed.shared.DocumentFormat.eur(new BigDecimal("0.50")), purchasePdf.openSupplier());
    }

    /** Two terms each closed on their own, + € 10,00 and - € 60,00: the supplier settled € 50,00 lower, not higher. */
    @Test
    void closedTermsThatNetLowerReadAsSettledLower() {
        var order = po35Order();
        var payable = po35Payable();
        var payments = List.of(
                po35Payment(1L, "9324.41", true, PaymentTerms.Moment.ORDERED),
                po35Payment(2L, "21673.63", true, PaymentTerms.Moment.SHIPPED));
        var report = new PurchaseReconciliationCalculator().calculate(order,
                PdfPurchaseRendererRenderTest.costing(1), payable, payments);
        var supplier = report.streams().stream().filter(row -> row.payee() == PurchasePayment.Payee.SUPPLIER).findFirst().orElseThrow();
        assertEquals(PurchaseReconciliation.Status.OVERPAID, supplier.status());
        assertEquals(0, supplier.overpaidEur().compareTo(new BigDecimal("10.00")));
        assertEquals(0, supplier.settledSavingEur().compareTo(new BigDecimal("60.00")));
        assertEquals(0, supplier.varianceEur().compareTo(new BigDecimal("-50.00")));
        assertTrue(supplier.finalized());
        assertFalse(supplier.explicitlySettled());
        String html = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), report, payments);
        assertTrue(html.contains("<b>Leverancier</b><br/><span class=\"muted small\">Lager afgerekend</span>"), html);
        assertFalse(html.contains("Hoger afgerekend"), html);
        assertTrue(html.contains("€ -50,00"), html);
    }

    static PurchaseOrder po35Order() {
        return new PurchaseOrder(35L, "PO-2026-002", null, 7L,
                LocalDate.of(2026, 3, 1), PurchaseOrderStatus.ONTVANGEN, ContainerType.FORTY_HQ,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                Currency.EUR, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Allocation.VALUE, Allocation.VALUE, Allocation.VALUE, Allocation.VALUE,
                "Ningbo", "Rotterdam", null, true, null, null, null, false,
                PaymentTerms.DEPOSIT_30_70, null, null, null,
                List.of(new PurchaseOrderLine(1L, 1L, 252, BigDecimal.TEN, Currency.EUR, BigDecimal.ZERO, 252)));
    }

    static PurchaseOrderService.Payable po35Payable() {
        return new PurchaseOrderService.Payable(new BigDecimal("31048.04"), BigDecimal.ZERO,
                BigDecimal.ZERO, true, true);
    }

    /** As on production: + € 61,28 on the 30 %, - € 60,78 on the 70 %. */
    static List<PurchasePayment> po35Payments() {
        return List.of(
                po35Payment(1L, "9375.69", false, PaymentTerms.Moment.ORDERED),
                po35Payment(2L, "21672.85", false, PaymentTerms.Moment.SHIPPED));
    }

    private static PurchasePayment po35Payment(long id, String value, boolean settles, PaymentTerms.Moment due) {
        boolean deposit = due == PaymentTerms.Moment.ORDERED;
        LocalDate day = deposit ? LocalDate.of(2026, 3, 2) : LocalDate.of(2026, 6, 2);
        return new PurchasePayment(id, 35L, day, new BigDecimal(value), Currency.EUR,
                new BigDecimal(value), deposit ? "Aanbetaling" : "Saldo", "Emre", Instant.parse(day + "T08:00:00Z"),
                PurchasePayment.Payee.SUPPLIER, settles, due);
    }

    /** Spec §5.4: a set is priced per set with the piece price under it; a mixed container averages per piece. */
    @Test
    void setsArePricedPerSetAndPerStukAndAMixedContainerAveragesPerPiece() throws Exception {
        var order = instalmentOrder();
        var roses = setLine(1L, "Long stem rose box display", 100, "3120.00", "3360.00");
        var display = report(List.of(roses), "DISPLAY", 12, 1200);
        String html = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), display, List.of());
        for (String required : List.of("verdeeld over 100 bestelde sets (1.200 stuks)", ">Aantal<", ">Extern / eenheid<",
                ">Met opslag / eenheid<", ">100 sets<", "€ 31,2000 /set<br/>€ 2,6000 /stuk",
                "€ 33,6000 /set<br/>€ 2,8000 /stuk", "Bij sets staat de prijs per set en per stuk.")) {
            assertTrue(html.contains(required), required + " missing from " + html);
        }
        assertFalse(html.contains(">Extern / stuk<"), html);
        try (var pdf = Loader.loadPDF(renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), display, List.of()).content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("2,6000") && text.contains("/stuk") && text.contains("100 sets"), text);
        }

        var bowl = pieceLine(2L, "Rozenschaal", 200, "1000.00");
        var mixed = report(List.of(roses, bowl), "MIXED", 12, 1400);
        String mixedHtml = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), mixed, List.of());
        for (String required : List.of("verdeeld over 1.400 bestelde stuks (sets als 12 rozen geteld)", "€ 5,0000 /stuk",
                "€ 2,9429 /stuk (gem.)", ">1.400 stuks<", ">200<")) {
            assertTrue(mixedHtml.contains(required), required + " missing from " + mixedHtml);
        }

        var pieces = report(List.of(bowl), "PIECE", null, 200);
        String piecesHtml = renderer.html(order, PdfPurchaseRendererRenderTest.supplier(), pieces, List.of());
        assertTrue(piecesHtml.contains(">Stuks<") && piecesHtml.contains(">Extern / stuk<"), piecesHtml);
        assertTrue(piecesHtml.contains("verdeeld over 200 bestelde stuks"), piecesHtml);
        assertFalse(piecesHtml.contains("Bij sets"), piecesHtml);
    }

    private static PurchaseReconciliation.Line setLine(Long id, String name, int quantity, String forecast, String pricing) {
        BigDecimal external = new BigDecimal(forecast);
        BigDecimal priced = new BigDecimal(pricing);
        int pieces = quantity * 12;
        return new PurchaseReconciliation.Line(id, name, quantity, 0, 0, 0, quantity, PurchaseReconciliation.UnitCostBasis.ORDERED,
                external, BigDecimal.ZERO, external, external, BigDecimal.ZERO, priced.subtract(external), priced,
                external.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP),
                priced.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP), "Naar goederenwaarde.",
                BigDecimal.ZERO).withPieces("DISPLAY", 12, "roos", "roos", "rozen", pieces,
                external.divide(BigDecimal.valueOf(pieces), 4, java.math.RoundingMode.HALF_UP),
                priced.divide(BigDecimal.valueOf(pieces), 4, java.math.RoundingMode.HALF_UP));
    }

    private static PurchaseReconciliation.Line pieceLine(Long id, String name, int quantity, String forecast) {
        BigDecimal external = new BigDecimal(forecast);
        BigDecimal unit = external.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP);
        return new PurchaseReconciliation.Line(id, name, quantity, 0, 0, 0, quantity, PurchaseReconciliation.UnitCostBasis.ORDERED,
                external, BigDecimal.ZERO, external, external, BigDecimal.ZERO, BigDecimal.ZERO, external, unit, unit,
                "Naar goederenwaarde.", BigDecimal.ZERO);
    }

    private static PurchaseReconciliation report(List<PurchaseReconciliation.Line> lines, String basis, Integer setPieces,
                                                 int pieces) {
        BigDecimal forecast = lines.stream().map(PurchaseReconciliation.Line::forecastExternalEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pricing = lines.stream().map(PurchaseReconciliation.Line::forecastPricingEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        int quantity = lines.stream().mapToInt(PurchaseReconciliation.Line::unitCostQuantity).sum();
        var totals = new PurchaseReconciliation.Totals(forecast, BigDecimal.ZERO, forecast, forecast, BigDecimal.ZERO,
                pricing.subtract(forecast), pricing, pricing, false, quantity, 0, 0, 0, quantity,
                PurchaseReconciliation.UnitCostBasis.ORDERED,
                forecast.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP),
                pricing.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP), false, null,
                BigDecimal.ZERO, BigDecimal.ZERO)
                .withPieces(basis, setPieces, setPieces == null ? null : "rozen", pieces, 0, pieces,
                        forecast.divide(BigDecimal.valueOf(pieces), 4, java.math.RoundingMode.HALF_UP),
                        pricing.divide(BigDecimal.valueOf(pieces), 4, java.math.RoundingMode.HALF_UP));
        return new PurchaseReconciliation(List.of(), totals, lines,
                "PIECE".equals(basis) ? List.of() : List.of("Bij sets is de kostprijs per set."));
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
