package be.enrosed.sales.adapter.out.document;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Language;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end print checks for the portrait sales document family.
 *
 * The files written to output/pdf are deliberate visual QA specimens. PDFBox
 * then proves the things a screenshot cannot: every page is portrait, every
 * used font is embedded and customer documents do not leak internal pricing.
 */
@QuarkusTest
class PdfQuoteRendererRenderTest {

    @Inject Engine engine;

    private PdfQuoteRenderer renderer;

    @BeforeEach
    void rendererWithCompleteCompanyIdentity() {
        CompanyProfileService company = mock(CompanyProfileService.class);
        when(company.get()).thenReturn(company());
        renderer = new PdfQuoteRenderer(
                engine.getTemplate("quote.html"), engine.getTemplate("packing-slip.html"),
                new Brand(), company, new PdfFonts());
        renderer.portalBaseUrl = "https://enrosed.com";
    }

    @Test
    void quotationSurvivesManyLinesAndShowsEveryCommercialAdjustment() throws Exception {
        String portalUrl = "https://orders.enrosed.com/portal/"
                + "9yB8a4Qm2Lk7Wn5Pz3Rr6Tt1Vv8Xx4Cc7Dd9Ee2Ff5Gg8Hh1Jj4Kk7";
        SalesOrder stressOrder = order(DocumentType.OFFERTE, "ENR-2026-0148", 22);
        PdfQuoteRenderer.Document stressDocument = renderer.render(
                stressOrder, priced(22), customer(), portalUrl);

        /* Keep the review specimen representative as well as challenging:
           eighteen rows exercise a repeated table header without creating an
           action-only page before the frozen terms appendix. The separate
           22-row render above remains the pagination stress contract. */
        SalesOrder previewOrder = order(DocumentType.OFFERTE, "ENR-2026-0148", 18);
        PdfQuoteRenderer.Document previewDocument = renderer.render(
                previewOrder, priced(18), customer(), portalUrl);
        writePreview("enrosed-sales-quote-portrait-preview.pdf", previewDocument.content());

        try (PDDocument pdf = Loader.loadPDF(stressDocument.content())) {
            assertPortraitAndEmbedded(pdf);
            assertTrue(pdf.getNumberOfPages() >= 3,
                    "many commercial lines plus frozen terms must paginate");
            String text = textOf(pdf);
            assertTrue(text.contains("offerte"));
            assertTrue(text.contains("counter display premium kleur 1"));
            assertTrue(text.contains("counter display premium kleur 22"));
            assertTrue(text.contains("aalsmeer beurskorting"));
            assertTrue(text.contains("online bekijken, tekenen of wijzigen"));
            assertFalse(text.contains("internal-margin-sentinel"));
            assertFalse(text.contains("landed unit cost"));
        }

        try (PDDocument pdf = Loader.loadPDF(previewDocument.content())) {
            assertPortraitAndEmbedded(pdf);
            assertPrintSafeTop(pdf, 17);
            String text = textOf(pdf);
            assertTrue(text.contains("counter display premium kleur 18"));
            assertTrue(text.contains("online bekijken, tekenen of wijzigen"));
        }
    }

    @Test
    void belgianInvoiceKeepsPeppolAndPaymentHierarchy() throws Exception {
        SalesOrder invoice = order(DocumentType.FACTUUR, "F-2026-0042", 6);
        PdfQuoteRenderer.Document document = renderer.render(invoice, priced(6), customer(), null);

        writePreview("enrosed-sales-invoice-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("factuur"));
            assertTrue(text.contains("geen geldige factuur"));
            assertTrue(text.contains("peppol"));
            assertTrue(text.contains("te betalen"));
            assertTrue(text.contains("be68 5390 0754 7034"));
            assertTrue(text.contains("f-2026-0042"));
            assertTrue(text.contains("op al onze facturen"));
            assertFalse(text.contains("online bekijken, tekenen of wijzigen"));
        }
    }

    @Test
    void staffCanDownloadACompactDocumentWithoutRemovingCommercialEssentials() throws Exception {
        PdfQuoteRenderer.Document document = renderer.render(
                order(DocumentType.OFFERTE, "ENR-2026-0149", 2), priced(2), customer(), null,
                Language.NL, new SalesPdfOptions(false, false, false, false));

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("counter display premium kleur 1"));
            assertTrue(text.contains("stukprijs"));
            assertTrue(text.contains("totaal"));
            assertFalse(text.contains("er-glass-001"), "SKU is optionele productinformatie");
            assertFalse(text.contains("week 37"), "leverinformatie valt onder logistiek");
            assertFalse(text.contains("algemene voorwaarden"), "voorwaarden zijn uitgeschakeld");
        }
    }

    @Test
    void salesPdfDefaultsAndCleanTitleAreStable() {
        SalesPdfOptions defaults = SalesPdfOptions.defaults();
        assertTrue(defaults.includePhotos());
        assertTrue(defaults.includeProductDetails());
        assertTrue(defaults.includeLogistics());
        assertTrue(defaults.includeTerms());
        assertEquals("Bowl Rozen XL - Red",
                PdfQuoteRenderer.cleanFallbackTitle(
                        "Bowl Rozen XL - B × D × H: 10 × 10 × 8 cm - Red"));
        assertTrue(PdfQuoteRenderer.hasLineDiscounts(priced(1)));
        assertFalse(PdfQuoteRenderer.hasLineDiscounts(priced(1, false)));
    }

    @Test
    void packingSlipIsPortraitReadableAndPriceFree() throws Exception {
        String expectedDate = be.enrosed.shared.DocumentText
                .date(LocalDate.now(), Language.NL).toLowerCase();
        SalesOrder order = order(DocumentType.FACTUUR, "F-2026-0042", 12);
        List<QuoteDocumentRenderer.PackingItem> palletOne = packingItems(1, 8);
        List<QuoteDocumentRenderer.PackingItem> palletTwo = packingItems(9, 16);
        List<QuoteDocumentRenderer.PackingItem> loose = packingItems(17, 20);
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order, customer(),
                List.of(
                        new QuoteDocumentRenderer.PackingPallet(
                                "Pallet 1", "EURO 120 x 80", 168, palletOne),
                        new QuoteDocumentRenderer.PackingPallet(
                                "Pallet 2", "BLOCK 120 x 100", 176, palletTwo)),
                loose, 40, 480, false);

        String html = renderer.packingSlipHtml(slip);
        assertTrue(html.contains("<html lang=\"nl\">"));
        assertTrue(html.toLowerCase().contains(expectedDate));
        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);
        writePreview("enrosed-sales-packing-slip-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            assertTrue(pdf.getNumberOfPages() <= 2,
                    "a 20-line warehouse document must stay operationally compact");
            String text = textOf(pdf);
            assertTrue(text.contains("pakbon"));
            assertTrue(text.contains(expectedDate));
            assertTrue(text.contains("pallet 1"));
            assertTrue(text.contains("preserved rose glass bowl kleur 20"));
            assertTrue(text.contains("geladen door / datum"));
            assertTrue(text.contains("ontvangen door / datum"));
            assertFalse(text.contains("12,50 eur"));
            assertFalse(text.contains("6.032,80 eur"));
            assertFalse(text.contains("stukprijs"));
            assertFalse(text.contains("algemene voorwaarden"));
            assertFalse(text.contains("artikel 1"));
        }
    }

    @Test
    void englishPackingSlipUsesTheCustomerLanguage() throws Exception {
        String expectedDate = be.enrosed.shared.DocumentText
                .date(LocalDate.now(), Language.EN).toLowerCase();
        SalesOrder order = order(DocumentType.FACTUUR, "F-2026-0043", 1);
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order, customer(Language.EN), List.of(),
                List.of(new QuoteDocumentRenderer.PackingItem(
                        "Preserved rose glass bowl - ruby red", 2, 24)),
                2, 24, true);

        String html = renderer.packingSlipHtml(slip);
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.toLowerCase().contains(expectedDate));
        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);
        writePreview("enrosed-sales-packing-slip-en-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("delivery note"));
            assertTrue(text.contains(expectedDate));
            assertTrue(text.contains("delivery address"));
            assertTrue(text.contains("loose cartons"));
            assertTrue(text.contains("loaded by / date"));
            assertTrue(text.contains("received by / date"));
            assertFalse(text.contains("pakbon"));
            assertFalse(text.contains("leveradres"));
            assertFalse(text.contains("general terms and conditions"));
            assertFalse(text.contains("article 1"));
        }
    }

    @Test
    void emptyPackingSlipStillRendersAUsefulControlSheet() throws Exception {
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order(DocumentType.FACTUUR, "F-2026-0044", 0),
                customer(), List.of(), List.of(), 0, 0, false);

        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("pakbon"));
            assertTrue(text.contains("0 dozen"));
            assertTrue(text.contains("geladen door / datum"));
        }
    }

    private static void assertPortraitAndEmbedded(PDDocument pdf) throws Exception {
        for (PDPage page : pdf.getPages()) {
            assertTrue(page.getMediaBox().getHeight() > page.getMediaBox().getWidth(),
                    "sales documents must remain portrait A4");
            for (var name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                assertTrue(font != null && font.isEmbedded(),
                        () -> "font " + name.getName() + " must be embedded");
            }
        }
    }

    private static void assertPrintSafeTop(PDDocument document, double minimumMillimetres)
            throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 72);
        int firstInk = page.getHeight();
        scan:
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                int colour = page.getRGB(x, y);
                int red = colour >>> 16 & 0xff;
                int green = colour >>> 8 & 0xff;
                int blue = colour & 0xff;
                if (red < 248 || green < 248 || blue < 248) {
                    firstInk = y;
                    break scan;
                }
            }
        }
        int minimumPixels = (int) Math.floor(minimumMillimetres * 72 / 25.4);
        assertTrue(firstInk >= minimumPixels,
                "documentinhoud begint op " + firstInk + " px; minimaal "
                        + minimumPixels + " px vereist voor een printveilige bovenmarge");
    }

    private static String textOf(PDDocument pdf) throws Exception {
        return new PDFTextStripper().getText(pdf).toLowerCase().replaceAll("\\s+", " ");
    }

    private static void writePreview(String filename, byte[] content) throws Exception {
        Path directory = Path.of("output", "pdf");
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), content);
    }

    private static CompanyProfile company() {
        return new CompanyProfile(
                "Enrosed BV", "Enrosed BV", "BE 1034.273.386", "1034.273.386",
                "Vekeblok 17", "2400", "Mol", "BE",
                "hello@enrosed.com", "+32 470 02 42 07", "enrosed.com",
                "BE68 5390 0754 7034", "KREDBEBB",
                "Wholesale preserved flowers for professional buyers.",
                "Wholesale preserved flowers for professional buyers.",
                null, null, null, null);
    }

    private static Customer customer() {
        return customer(Language.NL);
    }

    private static Customer customer(Language language) {
        return new Customer(
                7L, "Royal Garden Center Group", "Anne van den Berg",
                "inkoop@royalgarden.example", "+32 3 555 01 02", "BE 0123.456.789",
                "BE", language, "Bloemenlaan 112", "2000", "Antwerpen",
                "DAP", "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
    }

    private static SalesOrder order(DocumentType type, String number, int lineCount) {
        LocalDate date = LocalDate.of(2026, 8, 27);
        List<be.enrosed.sales.domain.SalesOrderLine> lines = new ArrayList<>();
        for (int index = 1; index <= lineCount; index++) {
            lines.add(new be.enrosed.sales.domain.SalesOrderLine(
                    (long) index, (long) index, 24, null, null, "2026-W37"));
        }
        return new SalesOrder(
                148L, number, 7L, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen na factuurdatum",
                "Levering op afspraak aan het centrale magazijn.",
                MarkupMode.PRODUCT, bd("45"), bd("5"), "Aalsmeer beurskorting",
                null, null, null, 0, null, null, null, "internal-margin-sentinel",
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, bd("220"),
                LoadMode.PALLETS, PalletProfile.EURO_120X80, bd("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                type, type == DocumentType.FACTUUR ? date.plusDays(30) : null,
                null, type == DocumentType.FACTUUR ? 147L : null, null,
                lines, List.of());
    }

    private static PricedOrder priced(int lineCount) {
        return priced(lineCount, true);
    }

    private static PricedOrder priced(int lineCount, boolean withLineDiscount) {
        List<PricedOrder.Line> lines = new ArrayList<>();
        BigDecimal lineDiscountPct = withLineDiscount ? bd("5") : BigDecimal.ZERO;
        BigDecimal lineDiscountAmount = withLineDiscount ? bd("15.00") : BigDecimal.ZERO;
        BigDecimal lineNet = withLineDiscount ? bd("285.00") : bd("300.00");
        for (int index = 1; index <= lineCount; index++) {
            lines.add(new PricedOrder.Line(
                    (long) index, "ER-GLASS-" + String.format("%03d", index),
                    "Counter display premium kleur " + index,
                    "Counter display premium kleur " + index,
                    null, 24, 2, 16, 1, 4, 2, bd("164"),
                    bd("0.120"), bd("8.5"), bd("12.50"), bd("300.00"),
                    lineDiscountPct, BigDecimal.ZERO, lineDiscountPct, lineDiscountAmount, lineNet,
                    lineNet.divide(bd("24"), 4, RoundingMode.HALF_UP),
                    bd("6.00"), bd("144.00"), lineNet.subtract(bd("144.00")),
                    bd("49.47"), 48, bd("7.5"), 800, true, true, 0,
                    "2026-09-07", "2026-W37", "Uit voorraad leverbaar"));
        }

        BigDecimal gross = bd("300.00").multiply(BigDecimal.valueOf(lineCount));
        BigDecimal lineDiscount = lineDiscountAmount.multiply(BigDecimal.valueOf(lineCount));
        BigDecimal subtotal = gross.subtract(lineDiscount);
        BigDecimal orderDiscount = subtotal.multiply(bd("0.03"))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterOrder = subtotal.subtract(orderDiscount);
        BigDecimal extraDiscount = afterOrder.multiply(bd("0.05"))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal goods = afterOrder.subtract(extraDiscount);
        BigDecimal freight = bd("220.00");
        BigDecimal handling = bd("35.00");
        BigDecimal total = goods.add(freight).add(handling);
        BigDecimal vat = total.multiply(bd("0.21")).setScale(2, RoundingMode.HALF_UP);

        PricedOrder.Totals totals = new PricedOrder.Totals(
                lineCount * 24, lineCount * 2, Math.max(1, (lineCount + 1) / 2),
                Math.max(1, (lineCount + 2) / 3), 0, 0,
                bd("14.4"), bd("180"), bd("0.120").multiply(BigDecimal.valueOf(lineCount)),
                bd("8.5").multiply(BigDecimal.valueOf(lineCount)),
                gross, lineDiscount, subtotal, bd("3"), orderDiscount,
                bd("5"), "Aalsmeer beurskorting", extraDiscount, goods,
                freight, false, handling, freight.add(handling), total,
                bd("21"), vat, total.add(vat), VatTreatment.BINNENLAND,
                null, null, bd("144.00").multiply(BigDecimal.valueOf(lineCount)),
                goods.subtract(bd("144.00").multiply(BigDecimal.valueOf(lineCount))),
                bd("49.47"), bd("1000.00"));

        return new PricedOrder(lines, totals,
                new PricedOrder.Validation(
                        bd("500"), true, BigDecimal.ZERO, true, true,
                        List.of(), List.of(), List.of(), null));
    }

    private static List<QuoteDocumentRenderer.PackingItem> packingItems(int from, int through) {
        List<QuoteDocumentRenderer.PackingItem> items = new ArrayList<>();
        for (int index = from; index <= through; index++) {
            items.add(new QuoteDocumentRenderer.PackingItem(
                    "Preserved rose glass bowl kleur " + index, 2, 24));
        }
        return items;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
