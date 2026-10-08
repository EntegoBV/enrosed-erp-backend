package be.enrosed.inventory.adapter.out.document;

import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.ClosingReportData;
import be.enrosed.inventory.application.ClosingVersionDiff;
import be.enrosed.inventory.application.StockClosingWorkbookTest;
import be.enrosed.inventory.domain.ValuationRuleText;
import be.enrosed.shared.Brand;
import be.enrosed.shared.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDF from the fixed report of {@link StockClosingWorkbookTest#sample}: what the paper must
 * say, read back from the rendered pages.
 */
class PdfStockClosingRenderTest {

    private final PdfStockClosingRenderer renderer = new PdfStockClosingRenderer(new PdfFonts(), new Brand());

    @Test
    void theFinalPaperCarriesTheRuleTheHashesTheStatementsAndTheSignOffBlock() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, false);
        byte[] pdf = renderer.render(data);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String text = text(pdf);

        assertHolds(text, "Enrosed BV");
        assertHolds(text, "BE 1034.273.386");
        assertHolds(text, "Vekeblok 17, 2400 Mol, BE");
        assertHolds(text, "Voorraadinventaris per 31/12/2026");
        assertHolds(text, "Boekjaar 2026 · versie 1 · Definitief");
        assertHolds(text, "Waarderingsmethode: FIFO per ontvangen partij, van toepassing sinds boekjaar 2026");
        assertHolds(text, "Definitief gemaakt door Emre Yilmaz op 15/01/2027 11:30");
        assertFalse(squeezed(text).contains("CONCEPT"), "a final paper is not marked as a concept");
        assertFalse(squeezed(text).contains(squeezed("Vervangt versie")));

        assertHolds(text, "Totaal voorraadwaarde volgens de genomen beslissingen € 4.997,22");
        assertHolds(text, "Aanschafwaarde eigen voorraad € 3.181,76");
        assertHolds(text, "Op opgenomen partnercontainers en goederen onderweg (€ 1.840,00) is geen lagere marktwaarde ingevoerd;"
                + " het ERP voorziet daar geen waardevermindering.");
        assertHolds(text, "PO-2026-014 · ontvangen 20/11/2026 · geschat");
        assertHolds(text, "€ 2,5449");
        assertHolds(text, "Totaal eigen voorraad 1270");
        assertHolds(text, "Oudere facturen zonder afpunten: 2, niet verwerkt.");
        assertHolds(text, "Bank- en betalingskosten en andere bedragen die niet bij de zending horen ('Bijkomende kosten' in het ERP)");

        /* The rule may run over a page: read it without the footer line that then falls inside it. */
        assertHolds(text.replaceAll("ENROSED / Jaarinventaris 2026 versie 1 \\d+ / \\d+", ""), ValuationRuleText.render(2026));
        assertHolds(text, "een beweging die nadien uit de voorraadgeschiedenis is verwijderd telt daarbij niet mee");
        assertHolds(text, "Gegevens: " + "d".repeat(64));
        assertHolds(text, "Excel: " + "e".repeat(64));
        assertHolds(text, "Emre Yilmaz bevestigde op 10/01/2027: de betalingen onder Leverancier, Douane & transport en"
                + " Inspectie & andere kosten zijn zonder aftrekbare btw ingevoerd (bedragen exclusief btw).");
        assertHolds(text, "De koersen zijn op de container ingevoerd; het ERP bewaart geen factuurdatum of koersbron.");
        assertHolds(text, "Container PO-2026-030: eigendom of risico vanaf 15/12/2026, opgegeven bij de beslissing over goederen onderweg.");
        assertHolds(text, "Opgemaakt door: Emre Yilmaz · Datum: ");
        assertHolds(text, "Nagezien door (boekhouder): ");
        assertHolds(text, "Handtekening: ");
        assertHolds(text, PdfStockClosingRenderer.CLOSING_SENTENCE);
        assertHolds(text, "ENROSED / Jaarinventaris 2026 versie 1");
    }

    @Test
    void aProductStaysWithItsLotsAndEveryListHasFixedColumns() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, false);
        String html = renderer.html(data);
        /* Each product and the lots under it are one block that a page break cannot split. */
        assertTrue(html.contains(".data tbody.product { page-break-inside: avoid; }"));
        assertTrue(html.contains(".data tr.layer { page-break-before: avoid; }"));
        Matcher block = Pattern.compile("<tbody class=\"product\">(.*?)</tbody>", Pattern.DOTALL).matcher(html);
        int blocks = 0, layers = 0;
        while (block.find()) {
            blocks++;
            String rows = block.group(1);
            int own = rows.split("<tr class=\"layer", -1).length - 1;
            layers += own;
            if (own > 0) {
                assertTrue(rows.contains("<tr class=\"parent\">"), "no rule between a product and its own lots");
                assertEquals(1, rows.split("<tr class=\"layer last\">", -1).length - 1, "the rule closes the block under its last lot");
                assertTrue(rows.trim().endsWith("</tr>") && rows.lastIndexOf("<tr class=\"layer last\">") == rows.lastIndexOf("<tr"));
            }
        }
        assertTrue(blocks >= 1 && layers >= 2, blocks + " products, " + layers + " lots");
        assertEquals(layers, html.split("<tr class=\"layer", -1).length - 1, "no lot row outside the block of its product");
        assertEquals(html.split("<tr class=\"group\">", -1).length - 1, html.split("<tbody class=\"product\"><tr class=\"group\">", -1).length - 1,
                "a category heading opens the block of its first product");

        /* A date or a moment never wraps, and the columns of every list add up to the page. */
        assertTrue(html.contains("<td class=\"nw\">20/12/2026</td>"), "the invoice date in one piece");
        for (ClosingReportData.Table table : List.of(data.writeDownTable(), data.transitTable(), data.estimatedTable(),
                data.invoicedTable(), data.movementTable(), data.countDifferenceTable(), data.partnerTable(), data.creditTable())) {
            double[] widths = PdfStockClosingRenderer.widths(table.columns());
            double sum = 0;
            for (int index = 0; index < widths.length; index++) {
                sum += widths[index];
                ClosingReportData.Kind kind = table.columns().get(index).kind();
                if (kind == ClosingReportData.Kind.DAY) assertTrue(widths[index] >= 6, table.title() + ": a date fits");
                if (kind == ClosingReportData.Kind.MOMENT) assertTrue(widths[index] >= 8.5, table.title() + ": a moment fits");
                if (kind == ClosingReportData.Kind.TEXT) assertTrue(widths[index] >= 5, table.title() + ": text keeps room");
            }
            assertEquals(100.0, sum, 0.01, table.title());
        }
        assertTrue(html.contains("<table class=\"data fixed\"><colgroup><col style=\"width:"));
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
        assertEquals("1 container", ClosingNotices.counted(1, "container", "containers"));
        assertEquals("2 containers", ClosingNotices.counted(2, "container", "containers"));
        assertEquals("0 containers", ClosingNotices.counted(0, "container", "containers"));
        assertEquals(List.of("1 stuk", "7 stuks", "0 stuks", "-1 stuk", "-3 stuks"),
                java.util.stream.LongStream.of(1, 7, 0, -1, -3).mapToObj(ClosingNotices::pieces).toList());
    }

    @Test
    void aConceptPrintsTheCompanyHeadItIsGivenAndSaysThatItIsNotFinal() {
        ClosingReportData data = StockClosingWorkbookTest.sample(true, false);
        String text = text(renderer.render(data));
        assertHolds(text, "Concept BV");
        assertHolds(text, "BE 0000.000.097");
        assertHolds(text, "Dorpsstraat 1, 9000 Gent, BE");
        assertHolds(text, "Boekjaar 2026 · versie 1 · CONCEPT, niet definitief");
        assertFalse(squeezed(text).contains(squeezed("Definitief gemaakt door")));
        assertHolds(text, "Blokkeert: Bevestig dat de betalingen onder Leverancier, Douane & transport en Inspectie & andere"
                + " kosten zonder aftrekbare btw zijn ingevoerd.");
        assertFalse(squeezed(text).contains(squeezed("bevestigde op")), "no VAT statement before it was confirmed");
        assertHolds(text, "Excel: wordt vastgelegd bij het definitief maken");
        assertHolds(text, "Opgemaakt door: ……");
    }

    @Test
    void aCorrectionNamesTheVersionItReplacesAndListsWhatChanged() {
        ClosingReportData data = StockClosingWorkbookTest.sample(false, true);
        String text = text(renderer.render(data));
        assertHolds(text, "Boekjaar 2026 · versie 2 · Definitief");
        assertHolds(text, "Vervangt versie 1: Factuur van de expediteur kwam later binnen");
        assertHolds(text, "Wijzigingen tegenover versie 1");
        assertHolds(text, "Totaal voorraadwaarde Waarde 4.990,00 4.997,22 7,22");
        assertHolds(text, "PO-2026-014 · Roos in stolp rood Waarde per stuk 2,5371 2,5449 0,0078");
        assertHolds(text, "niet in de lijst");

        String html = renderer.html(new ClosingReportData(data.closing(), data.company(), data.notices(), data.articles(),
                data.layers(), data.lines(), data.containers(), data.lots(), data.separates(), data.writeDowns(), data.movements(),
                data.decisions(), data.closingYears(), new ClosingVersionDiff.Changes(1L, 1,
                data.closing().totalValueEur, data.closing().totalValueEur, List.of(), List.of(), List.of(), List.of())));
        assertTrue(html.contains("<h2>Wijzigingen tegenover versie 1</h2><p>Geen verschillen.</p>"));
    }

    @Test
    void theWordsThatHaveALegalMeaningStandOnlyWhereTheyBelong() {
        for (ClosingReportData data : List.of(StockClosingWorkbookTest.sample(false, true), StockClosingWorkbookTest.sample(true, false))) {
            /* Every cell, heading and paragraph of the page as its own piece of text. */
            List<String> pieces = new ArrayList<>();
            Matcher element = Pattern.compile("<(td|th|p|h1|h2|h3)[^>]*>(.*?)</\\1>", Pattern.DOTALL).matcher(renderer.html(data));
            while (element.find()) {
                pieces.add(element.group(2).replaceAll("<[^>]+>", " ").replace("&amp;", "&").replace("&#39;", "'")
                        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").strip());
            }
            assertTrue(pieces.contains("Niet in de waarde"));
            assertTrue(pieces.contains("Enrosed kost (buiten waarde)"));
            StockClosingWorkbookTest.assertWording(pieces, data.closing().ruleText);
        }
    }

    private static void assertHolds(String text, String expected) {
        assertTrue(squeezed(text).contains(squeezed(expected)), "the paper should read: " + expected);
    }

    /** Without white space: a line of the page may break anywhere. */
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
