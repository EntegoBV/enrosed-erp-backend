package be.enrosed.inventory.adapter.out.document;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.ClosingReportData;
import be.enrosed.inventory.application.ClosingVersionDiff;
import be.enrosed.inventory.application.StockClosingWorkbookTest;
import be.enrosed.shared.Brand;
import be.enrosed.shared.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads the actual portrait PDF: the shorter paper must retain its figures and accounting identity. */
class PdfStockClosingRenderTest {

    private final PdfStockClosingRenderer renderer = new PdfStockClosingRenderer(new PdfFonts(), new Brand());

    @Test
    void thePortraitPaperKeepsItsIdentityFiguresStatementsAndSignOff() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, false);
        byte[] pdf = render(data, "final-with-writedown.pdf");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        assertPortraitAndReadableBounds(pdf);
        String text = text(pdf);

        for (String expected : List.of("Enrosed BV", "BE 1034.273.386", "Vekeblok 17, 2400 Mol, BE",
                "Voorraadinventaris per 31/12/2026", "Boekjaar 2026 · versie 1 · Definitief",
                "Waarderingsmethode: FIFO per ontvangen partij, van toepassing sinds boekjaar 2026",
                "Definitief gemaakt door Emre Yilmaz op 15/01/2027 11:30",
                "Aanschafwaarde eigen voorraad € 3.181,76", "Eigen voorraad € 3.157,22",
                "Goederen onderweg opgenomen € 1.840,00", "Totaal voorraadwaarde € 4.997,22",
                "Roos in stolp rood", "RS-R", "2,5053", "24,54", "Totaal eigen voorraad",
                "Beschadigd", "Doos ingedeukt", "Gegevens: " + "d".repeat(64), "Excel: " + "e".repeat(64),
                "Opgemaakt door: Emre Yilmaz", "Nagezien door (boekhouder):", "Handtekening:",
                "ENROSED / Jaarinventaris 2026 versie 1")) {
            assertHolds(text, expected);
        }
        for (String statement : data.statements()) assertHolds(text, statement);
        assertHolds(text, "Aanschafwaarde, verminderd met de ingevoerde waardeverminderingen.");
        assertHolds(text, "De volledige waarderingsregel en onderbouwing staan in de bijbehorende Excel-export.");
        assertFalse(text.contains("CONCEPT"));
        assertEquals(new BigDecimal("4997.22"), data.closing().totalValueEur, "rendering must not recalculate a stored closing");
        assertEquals(new BigDecimal("2.5053"), data.articles().getFirst().averageUnitEur);
        assertEquals(1270, data.articles().getFirst().ownQuantity);
    }

    @Test
    void aWriteDownAddsTwoColumnsButThePaperLeavesCalculationDetailsToTheWorkbook() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, false);
        String html = renderer.html(data);
        List<List<String>> headers = tableHeaders(html);
        assertEquals(6, headers.getFirst().size(), "write-downs need acquisition, reduction and net values");
        assertTrue(headers.getFirst().containsAll(List.of("Artikel", "Aantal", "Kost/stuk", "Aanschafwaarde",
                "Waardevermindering", "Voorraadwaarde")), headers.toString());
        assertEquals(4, headers.get(1).size(), "the included transit list remains compact");
        for (String removed : List.of("Demostukken (begrepen in de lijst hierboven)", "Partijen (FIFO)",
                "Telverschillen", "Bewegingen", "Waarderingsregel", "Geschatte kosten", "Creditnota's")) {
            assertFalse(headings(html).contains(removed), "removed detail section: " + removed);
        }
        assertFalse(html.contains("class=\"layer"));
        assertFalse(html.contains("PO-2026-009"), "FIFO layer rows are absent from the short PDF");
        assertFalse(html.contains("F-2027-001"), "movement rows are absent from the short PDF");
        assertFalse(html.contains(data.closing().ruleText), "the long valuation rule remains in Excel");
        assertFalse(html.contains("<p class=\"muted\">Geen.</p>"), "empty sections do not fill the report");
    }

    @Test
    void aSimpleClosingHasFourColumnsInlineDemoAndNoEmptyOptionalSections() {
        ClosingReportData data = ownOnly(false);
        data.articles().getFirst().demo = true;
        data.closing().demoValueEur = data.closing().ownValueEur;
        String html = renderer.html(data);
        assertEquals(List.of(List.of("Artikel", "Aantal", "Kost/stuk", "Voorraadwaarde")), tableHeaders(html));
        for (String absent : List.of("Waardeverminderingen", "Partnergoederen opgenomen", "Goederen onderweg opgenomen",
                "Demostukken (begrepen in de lijst hierboven)", "Aandachtspunten")) {
            assertFalse(headings(html).contains(absent), absent);
        }
        byte[] pdf = render(data, "final-simple-demo.pdf");
        assertPortraitAndReadableBounds(pdf);
        String text = text(pdf);
        assertHolds(text, "Roos in stolp rood");
        assertHolds(text, "RS-R");
        assertTrue(text.toLowerCase().contains("demo"), "a demo is identified on its article row");
        assertHolds(text, "2,5053");
        assertHolds(text, "Totaal voorraadwaarde € 3.181,76");
        assertFalse(text.contains("Waardeverminderingen"));
        assertFalse(text.contains("Partnergoederen opgenomen"));
        assertFalse(text.contains("Goederen onderweg opgenomen"));
        assertFalse(text.contains("Geen."));
    }

    @Test
    void onlyIncludedPartnerAndTransitItemsEnterTheirCompactListsAndGrandTotal() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, false);
        StockClosingSeparateEntity included = separate("PARTNER", "Partnerroos meegenomen", true, 10, "10.0000", "100.00");
        StockClosingSeparateEntity excluded = separate("PARTNER", "Partnerroos uitgesloten", false, 20, "5.0000", "100.00");
        StockClosingSeparateEntity transitExcluded = separate("ONDERWEG", "Onderweg uitgesloten", false, 7, "2.0000", "14.00");
        List<StockClosingSeparateEntity> separates = new ArrayList<>(data.separates());
        separates.addAll(List.of(included, excluded, transitExcluded));
        data.closing().partnerIncludedEur = new BigDecimal("100.00");
        data.closing().partnerExcludedEur = new BigDecimal("100.00");
        data.closing().transitExcludedEur = new BigDecimal("14.00");
        data.closing().totalValueEur = new BigDecimal("5097.22");
        data = copy(data, data.notices(), data.articles(), separates, data.changes());

        assertEquals(List.of(6, 4, 4), tableHeaders(renderer.html(data)).stream().map(List::size).toList());
        byte[] pdf = render(data, "final-partner-transit.pdf");
        assertPortraitAndReadableBounds(pdf);
        String text = text(pdf);
        assertHolds(text, "Partnerroos meegenomen");
        assertHolds(text, "Roos in stolp wit");
        assertHolds(text, "Partnergoederen opgenomen € 100,00");
        assertHolds(text, "Goederen onderweg opgenomen € 1.840,00");
        assertHolds(text, "Totaal voorraadwaarde € 5.097,22");
        assertFalse(text.contains("Partnerroos uitgesloten"));
        assertFalse(text.contains("Onderweg uitgesloten"));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            boolean transitPageFound = false;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (!squeezed(pageText).contains(squeezed("Roos in stolp wit"))) continue;
                transitPageFound = true;
                for (String keptTogether : List.of("Opgenomen goederen onderweg", "Artikel", "Aantal", "Kost/stuk", "Subtotaal")) {
                    assertHolds(pageText, keptTogether);
                }
            }
            assertTrue(transitPageFound, "the single transit row stays with its section title, headers and subtotal");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void aConceptKeepsItsBlockersAndIdentifiesPiecesThatHaveNoValue() {
        ClosingReportData data = ownOnly(true);
        StockClosingArticleEntity article = data.articles().getFirst();
        article.ownQuantity = 0;
        article.unvaluedQuantity = 5;
        article.closingQuantity = 5;
        article.countedQuantity = 5;
        article.rollDelta = 0;
        article.invoicedOutQuantity = 0;
        article.costValueEur = BigDecimal.ZERO;
        article.ownValueEur = BigDecimal.ZERO;
        article.averageUnitEur = null;
        data.closing().ownQuantity = 0;
        data.closing().unvaluedQuantity = 5;
        data.closing().costValueEur = BigDecimal.ZERO;
        data.closing().ownValueEur = BigDecimal.ZERO;
        data.closing().totalValueEur = BigDecimal.ZERO;
        List<ClosingNotices.Notice> notices = StockClosingWorkbookTest.sample(true, false).notices();
        data = copy(data, notices, data.articles(), List.of(), null);
        byte[] pdf = render(data, "concept-unvalued.pdf");
        assertPortraitAndReadableBounds(pdf);
        String text = text(pdf);
        assertHolds(text, "Concept BV");
        assertHolds(text, "BE 0000.000.097");
        assertHolds(text, "Dorpsstraat 1, 9000 Gent, BE");
        assertHolds(text, "Boekjaar 2026 · versie 1 · CONCEPT, niet definitief");
        assertHolds(text, "Blokkeert: Bevestig dat de betalingen onder Leverancier, Douane & transport en Inspectie & andere"
                + " kosten zonder aftrekbare btw zijn ingevoerd.");
        assertHolds(text, "1 container met geschatte kosten: € 188,33 in de voorraadwaarde.");
        assertHolds(text, "5 stuks zonder waarde");
        assertFalse(text.contains("Definitief gemaakt door"));
        assertFalse(text.contains("bevestigde op"));
        assertHolds(text, "Excel: wordt vastgelegd bij het definitief maken");
        assertHolds(text, "Opgemaakt door: ……");
    }

    @Test
    void aCorrectionRetainsThePreviousAndNewTotalWithoutAllUnderlyingChanges() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, true);
        String text = text(render(data, "correction.pdf"));
        assertHolds(text, "Boekjaar 2026 · versie 2 · Definitief");
        assertHolds(text, "Vervangt versie 1: Factuur van de expediteur kwam later binnen");
        assertHolds(text, "Wijzigingen tegenover versie 1");
        assertHolds(text, "Voorraadwaarde vorige versie: € 4.990,00");
        assertHolds(text, "Nieuwe voorraadwaarde: € 4.997,22");
        assertHolds(text, "Verschil: € 7,22");
        assertFalse(text.contains("2,5371"), "per-lot changes stay in the workbook");
        assertFalse(text.contains("F-2027-000"), "per-movement changes stay in the workbook");
        assertFalse(text.contains("niet in de lijst"));

        ClosingVersionDiff.Changes unchanged = new ClosingVersionDiff.Changes(1L, 1,
                data.closing().totalValueEur, data.closing().totalValueEur, List.of(), List.of(), List.of(), List.of());
        String unchangedText = text(renderer.render(copy(data, data.notices(), data.articles(), data.separates(), unchanged)));
        assertHolds(unchangedText, "Wijzigingen tegenover versie 1");
        assertTrue(unchangedText.contains("Geen verschillen") || squeezed(unchangedText).contains("€0,00"),
                "a correction with no value change still says so");
    }

    @Test
    void manyLongProductRowsFlowOverPortraitPagesWithoutLosingValuesOrClippingText() {
        ClosingReportData data = ownOnly(false);
        List<StockClosingArticleEntity> articles = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        int quantity = 0;
        for (int index = 1; index <= 80; index++) {
            StockClosingArticleEntity article = new StockClosingArticleEntity();
            article.productId = (long) index;
            article.sku = "ENR-LANGE-ARTIKELCODE-" + String.format("%03d", index);
            article.productName = "Product " + String.format("%03d", index)
                    + " gepreserveerde rozen in glazen stolp met uitgebreide cadeauverpakking en spiegelbasis";
            article.categoryName = index <= 40 ? "Stolpen en geschenkverpakkingen" : "Harten en decoratie";
            article.ownQuantity = 1000 + index;
            article.unvaluedQuantity = 0;
            article.averageUnitEur = new BigDecimal("123.4567");
            article.costValueEur = article.averageUnitEur.multiply(BigDecimal.valueOf(article.ownQuantity))
                    .setScale(2, RoundingMode.HALF_UP);
            article.writeDownEur = index == 1 ? new BigDecimal("24.54") : BigDecimal.ZERO;
            article.ownValueEur = article.costValueEur.subtract(article.writeDownEur);
            article.demo = index == 2;
            articles.add(article);
            cost = cost.add(article.costValueEur);
            quantity += article.ownQuantity;
        }
        data.closing().costValueEur = cost;
        data.closing().writeDownEur = new BigDecimal("24.54");
        data.closing().ownValueEur = cost.subtract(data.closing().writeDownEur);
        data.closing().totalValueEur = data.closing().ownValueEur;
        data.closing().ownQuantity = quantity;
        data = copy(data, List.of(), articles, List.of(), null);
        byte[] pdf = render(data, "many-products.pdf");
        assertPortraitAndReadableBounds(pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() >= 3, "the long inventory should paginate");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String content = stripper.getText(document)
                        .replace("ENROSED / Jaarinventaris 2026 versie 1", "")
                        .replaceAll("(?m)^\\s*\\d+\\s*/\\s*\\d+\\s*$", "");
                assertFalse(content.isBlank(), "no page with only a footer " + page);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String text = text(pdf);
        for (StockClosingArticleEntity article : articles) {
            assertHolds(text, article.productName);
            assertHolds(text, article.sku);
        }
        assertHolds(text, "123,4567");
        assertHolds(text, "Totaal eigen voorraad");
        assertHolds(text, "Totaal voorraadwaarde € " + ClosingNotices.euro(cost.subtract(new BigDecimal("24.54"))));
        assertEquals(cost.subtract(new BigDecimal("24.54")), data.closing().totalValueEur);
    }

    @Test
    void theFilesStateTheFactOfANoticeWithoutTheAdviceOfTheScreen() {
        assertEquals("€ 42,35 bank- en betalingskosten en andere bedragen onder 'Bijkomende kosten' zijn niet opgenomen.",
                ClosingNotices.reportText("€ 42,35 bank- en betalingskosten en andere bedragen onder 'Bijkomende kosten' zijn niet opgenomen."
                        + " Hoort een bedrag bij de zending, zet het dan op de container onder 'Inspectie & andere kosten'."
                        + " Btw die je terugkrijgt hoort hier wel."));
        assertEquals("De koersen zijn op de container ingevoerd.", ClosingNotices.reportText(
                "De koersen zijn op de container ingevoerd en blijven wijzigbaar tot de afsluiting definitief is."));
        assertEquals("Container Herfst: tegoed leverancier voor tekort of schade € 92,00 staat buiten de voorraadwaarde.",
                ClosingNotices.reportText("Container Herfst: tegoed leverancier voor tekort of schade € 92,00 staat buiten de"
                        + " voorraadwaarde. Is het een korting op stuks die er liggen, geef dat dan aan bij het tegoed."));
        assertEquals(List.of("1 stuk", "7 stuks", "0 stuks", "-1 stuk", "-3 stuks"),
                java.util.stream.LongStream.of(1, 7, 0, -1, -3).mapToObj(ClosingNotices::pieces).toList());
    }

    private static ClosingReportData ownOnly(boolean concept) {
        ClosingReportData sample = StockClosingWorkbookTest.sample(concept, false);
        sample.articles().getFirst().writeDownEur = BigDecimal.ZERO;
        sample.articles().getFirst().ownValueEur = sample.articles().getFirst().costValueEur;
        sample.closing().writeDownEur = BigDecimal.ZERO;
        sample.closing().ownValueEur = sample.closing().costValueEur;
        sample.closing().totalValueEur = sample.closing().ownValueEur;
        sample.closing().transitIncludedEur = BigDecimal.ZERO;
        sample.closing().invoicedOutEur = BigDecimal.ZERO;
        sample.closing().estimatedEur = BigDecimal.ZERO;
        return new ClosingReportData(sample.closing(), sample.company(), List.of(), sample.articles(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                sample.decisions().stream().filter(decision -> "VAT_CONFIRMATION".equals(decision.kind)).toList(), Map.of(), null);
    }

    private static ClosingReportData copy(ClosingReportData data, List<ClosingNotices.Notice> notices,
                                           List<StockClosingArticleEntity> articles, List<StockClosingSeparateEntity> separates,
                                           ClosingVersionDiff.Changes changes) {
        return new ClosingReportData(data.closing(), data.company(), notices, articles, data.layers(), data.lines(), data.containers(),
                data.lots(), separates, data.writeDowns(), data.movements(), data.decisions(), data.closingYears(), changes);
    }

    private static StockClosingSeparateEntity separate(String kind, String name, boolean included, int quantity,
                                                       String unit, String value) {
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = kind;
        row.productName = name;
        row.sku = "TEST-" + kind;
        row.included = included;
        row.quantity = quantity;
        row.unitValueEur = new BigDecimal(unit);
        row.valueEur = new BigDecimal(value);
        row.documentNumber = "PO-2026-PARTNER";
        return row;
    }

    /** Optional fixture output for the same PDF that the assertions examine. */
    private byte[] render(ClosingReportData data, String filename) {
        byte[] pdf = renderer.render(data);
        String directory = System.getProperty("enrosed.inventory.qa.dir");
        if (directory != null && !directory.isBlank()) {
            try {
                Path output = Path.of(directory);
                Files.createDirectories(output);
                Files.write(output.resolve(filename), pdf);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return pdf;
    }

    private static List<List<String>> tableHeaders(String html) {
        List<List<String>> tables = new ArrayList<>();
        Matcher tablesMatcher = Pattern.compile("<thead>(.*?)</thead>", Pattern.DOTALL).matcher(html);
        while (tablesMatcher.find()) {
            List<String> columns = new ArrayList<>();
            Matcher cells = Pattern.compile("<th(?:\\s[^>]*)?>(.*?)</th>", Pattern.DOTALL).matcher(tablesMatcher.group(1));
            while (cells.find()) columns.add(cells.group(1).replaceAll("<[^>]+>", "").replace("-", "").strip());
            tables.add(columns);
        }
        return tables;
    }

    private static List<String> headings(String html) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = Pattern.compile("<h[23][^>]*>(.*?)</h[23]>", Pattern.DOTALL).matcher(html);
        while (matcher.find()) headings.add(matcher.group(1).replaceAll("<[^>]+>", " ").strip());
        return headings;
    }

    private static void assertPortraitAndReadableBounds(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                assertEquals(595.28f, page.getMediaBox().getWidth(), 1f, "A4 portrait width");
                assertEquals(841.89f, page.getMediaBox().getHeight(), 1f, "A4 portrait height");
            }
            new PDFTextStripper() {
                private float width;
                private float height;

                @Override
                protected void startPage(PDPage page) throws IOException {
                    width = page.getMediaBox().getWidth();
                    height = page.getMediaBox().getHeight();
                    super.startPage(page);
                }

                @Override
                protected void processTextPosition(TextPosition position) {
                    if (!position.getUnicode().isBlank()) {
                        assertTrue(position.getXDirAdj() >= 12 && position.getXDirAdj() + position.getWidthDirAdj() <= width - 12,
                                "text remains inside horizontal page bounds: " + position.getUnicode() + " at " + position.getXDirAdj());
                        assertTrue(position.getYDirAdj() >= 12 && position.getYDirAdj() <= height - 12,
                                "text remains inside vertical page bounds: " + position.getUnicode() + " at " + position.getYDirAdj());
                    }
                    super.processTextPosition(position);
                }
            }.getText(document);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertHolds(String text, String expected) {
        assertTrue(squeezed(text).contains(squeezed(expected)), "the paper should read: " + expected);
    }

    private static String squeezed(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String text(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
