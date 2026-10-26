package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceSearchTextTest {

    @Test
    void everyAmountExampleOfTheContract() {
        assertAmount("1.234,56", "1234.56");
        assertAmount("1,234.56", "1234.56");
        assertAmount("1234.56", "1234.56");
        assertAmount("18.420", "18420.00");
        assertAmount("18,42", "18.42");
        assertAmount("18,420", "18420.00");
        assertAmount("€1250", "1250.00");
        assertAmount("-125,00", "125.00");
        assertAmount("2026", "2026.00");
        assertNull(InvoiceSearchText.amount("PO-2026-012"));
        assertNull(InvoiceSearchText.amount("12/04"));
        assertAmount("1250eur", "1250.00");
        assertAmount("USD840", "840.00");
        assertAmount("+5", "5.00");
        assertAmount("1'234.50", "1234.50");
        assertAmount("1.234.567", "1234567.00");
        assertNull(InvoiceSearchText.amount("1.2345"), "four digits after one dot is neither");
        assertNull(InvoiceSearchText.amount("1,234,5"));
        assertNull(InvoiceSearchText.amount("1.234,567"), "three decimals after the last separator");
        assertNull(InvoiceSearchText.amount("eur"));
        assertNull(InvoiceSearchText.amount("CI-2026"));
    }

    private static void assertAmount(String word, String expected) {
        assertEquals(new BigDecimal(expected), InvoiceSearchText.amount(word), word);
    }

    @Test
    void spaceGroupedThousandsAreOneAmountAndCurrencySignsAreDropped() {
        assertEquals(List.of("18420"), InvoiceSearchText.words("€ 18 420"));
        assertEquals(List.of("18420,00"), InvoiceSearchText.words("18 420,00"));
        assertEquals(List.of("1234567"), InvoiceSearchText.words("1 234 567"));
        assertEquals(List.of("VT", "2026", "0412"), InvoiceSearchText.words("VT 2026 0412"), "four digits never join");
        assertTrue(InvoiceSearchText.matches("Verhoeven", new BigDecimal("18420.00"), null, "€ 18 420"));
        assertTrue(InvoiceSearchText.matches("Changsha", new BigDecimal("16985.20"), new BigDecimal("18420.00"), "18.420"),
                "the euro amount matches too");
        assertFalse(InvoiceSearchText.matches("Changsha", new BigDecimal("18420.02"), null, "18.420"));
        assertTrue(InvoiceSearchText.matches("Changsha", new BigDecimal("18420.01"), null, "18.420"), "within a cent");
    }

    @Test
    void separatorBlindNumbersAndAccents() {
        assertTrue(InvoiceSearchText.matches("Changsha Pretty CI-2026-118", null, null, "CI 2026/118"));
        assertTrue(InvoiceSearchText.matches("Décor Maison", null, null, "decor"));
        assertTrue(InvoiceSearchText.matches("decor maison", null, null, "DÉCOR"));
        assertFalse(InvoiceSearchText.matches("CI-2026-118", null, null, "CI 2026/119"));
        assertFalse(InvoiceSearchText.matches("A-B-C", null, null, "a/b/c"), "no digit: no separator-blind match");
        assertTrue(InvoiceSearchText.matches("Proximus PX-551203", null, null, "proximus 551203"), "every word must match");
        assertFalse(InvoiceSearchText.matches("Proximus PX-551203", null, null, "proximus telenet"));
        assertEquals("ci20261", InvoiceSearchText.blind("CI 2026/1"));
        assertEquals("cafe creme", InvoiceSearchText.normalize("  Café   Crème "));
    }

    @Test
    void atMostEightWordsOfSixtyFourCharacters() {
        List<String> words = InvoiceSearchText.words("a b c d e f g h i j");
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h"), words);
        assertEquals(64, InvoiceSearchText.words("x".repeat(80)).getFirst().length());
        assertEquals(List.of(), InvoiceSearchText.words("   "));
        assertEquals(List.of(), InvoiceSearchText.words(null));
    }

    @Test
    void odooWordsSplitSeparatedWordsIntoRuns() {
        assertEquals(List.of("VT", "2026", "0412"), texts(InvoiceSearchText.odooWords("VT 2026/0412", Map.of())));
        List<OdooInvoiceSearch.Word> amount = InvoiceSearchText.odooWords("1.250,00", Map.of());
        assertEquals(1, amount.size());
        assertEquals("1.250,00", amount.getFirst().text());
        assertEquals(new BigDecimal("1250.00"), amount.getFirst().amount());
        assertEquals(List.of(), InvoiceSearchText.odooWords("a/b", Map.of()), "no run of two characters: dropped");
        assertEquals(List.of("Proximus"), texts(InvoiceSearchText.odooWords("Proximus", Map.of())));
        assertNull(InvoiceSearchText.odooWords("Proximus", Map.of()).getFirst().amount());
        assertEquals(new BigDecimal("2026.00"), InvoiceSearchText.odooWords("2026", Map.of()).getFirst().amount());
        assertNull(InvoiceSearchText.odooWords("VT 2026/0412", Map.of()).get(1).amount(), "a run never carries an amount");
    }

    @Test
    void containerMarkedMovesRideAlongOnEveryRun() {
        Map<Long, InvoiceRow.ContainerRef> marked = Map.of(
                900001L, new InvoiceRow.ContainerRef(41, "PO-2026-012", "Rozen voorjaar"),
                900002L, new InvoiceRow.ContainerRef(42, "PO-2026-013", null));
        List<OdooInvoiceSearch.Word> words = InvoiceSearchText.odooWords("PO-2026-012", marked);
        assertEquals(List.of("PO", "2026", "012"), texts(words));
        for (OdooInvoiceSearch.Word word : words) assertEquals(Set.of(900001L), word.alsoMoveIds());
        assertEquals(Set.of(900001L), InvoiceSearchText.odooWords("voorjaar", marked).getFirst().alsoMoveIds(), "by alias");
        List<OdooInvoiceSearch.Word> split = InvoiceSearchText.odooWords("PO 2026/013", marked);
        assertEquals(Set.of(900001L, 900002L), split.getFirst().alsoMoveIds(), "'PO' is in both numbers");
        assertEquals(Set.of(900002L), split.getLast().alsoMoveIds(), "the runs of '2026/013' carry that word's moves");
        assertEquals(Set.of(900002L), InvoiceSearchText.odooWords("2026/013", marked).getFirst().alsoMoveIds(),
                "separator-blind on the number");
        assertEquals(Set.of(), InvoiceSearchText.odooWords("Proximus", marked).getFirst().alsoMoveIds());
    }

    @Test
    void atMostTwelveOdooWords() {
        List<OdooInvoiceSearch.Word> words = InvoiceSearchText.odooWords("a1-b2-c3-d4 e5-f6-g7-h8 i9-j0-k1-l2 m3-n4", Map.of());
        assertEquals(12, words.size());
        assertEquals("a1", words.getFirst().text());
        assertEquals("l2", words.getLast().text());
    }

    private static List<String> texts(List<OdooInvoiceSearch.Word> words) {
        return words.stream().map(OdooInvoiceSearch.Word::text).toList();
    }
}
