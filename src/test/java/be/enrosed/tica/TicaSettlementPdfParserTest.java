package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TicaSettlementPdfParserTest {
    private final TicaSettlementPdfParser parser = new TicaSettlementPdfParser();
    // Synthetic amounts and reference; format follows the verified Brussels document.
    static final String DOCUMENT = """
            Creditnota TICA Brussel Ondernemingsnr.: BE0428038927
            Creditnotanr.: 123456789 Btw-nummer BE1034273386 Documentdatum: 19 oktober 2026
            Vervaldatum: 19 oktober 2026
            Omschrijving Aantal Eenheidsprijs Excl. btw Btw % Regelbedrag Excl. btw
            Commissie week 42 - 10.00% -1 20,00 21 -20,00
            Omzet week 42 1 200,00 21 200,00
            Totaal EUR excl. btw 180,00 Btw-bedrag 37,80 Totaal EUR 217,80
            Specificatie btw-bedrag Basisbedrag btw Btw % Btw-bedrag
            -20,00 21 -4,20
            200,00 21 42,00
            """;
    @Test void readsSalesCommissionVatAndWeekSeparately() {
        var result = parser.parseText(DOCUMENT);
        assertEquals("123456789", result.reference());
        assertEquals(LocalDate.of(2026,10,12), result.periodFrom());
        assertEquals(LocalDate.of(2026,10,18), result.periodTo());
        assertEquals(new BigDecimal("200.00"), result.salesExcl());
        assertEquals(new BigDecimal("42.00"), result.salesVat());
        assertEquals(new BigDecimal("20.00"), result.commissionExcl());
        assertEquals(new BigDecimal("4.20"), result.commissionVat());
        assertEquals(new BigDecimal("217.80"), result.netReceivable());
    }
    @Test void rejectsUnrelatedCreditNotesAndDifferentRecipients() {
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("BE0428038927", "BE0000000000")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("BE1034273386", "BE0000000000")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("Commissie week", "Terugbetaling week")));
    }
    @Test void rejectsAmountsThatWouldLoseCostsOrVatAndMismatchedWeeks() {
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("217,80", "218,80")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("37,80", "37,81")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("-4,20", "-4,21")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("Commissie week 42", "Commissie week 41")));
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("10.00%", "15.00%")));
    }
    @Test void mapsIsoWeekAcrossNewYearAndRejectsFuturePeriod() {
        var result = parser.parseText(DOCUMENT.replace("19 oktober 2026", "5 januari 2026").replace("week 42", "week 1"));
        assertEquals(LocalDate.of(2025,12,29), result.periodFrom());
        assertEquals(LocalDate.of(2026,1,4), result.periodTo());
        assertThrows(BusinessRuleException.class, () -> parser.parseText(DOCUMENT.replace("week 42", "week 43")));
    }
    @Test void handlesDutchThousandsGroupingAndWhitespace() {
        String large = DOCUMENT.replace("200,00", "2.000,00").replace("20,00", "200,00")
                .replace("180,00", "1.800,00").replace("37,80", "378,00").replace("217,80", "2.178,00")
                .replace("4,20", "42,00").replace("21 42,00", "21 420,00");
        // Replacement of the negative tax allocation is intentionally explicit.
        large = large.replace("-200,00 21 -420,00", "-200,00 21 -42,00");
        assertEquals(new BigDecimal("2178.00"), parser.parseText(large.replace(' ', '\u00a0')).netReceivable());
    }
    @Test void rejectsNonPdfAndLargeUploadBeforeParsing() {
        assertThrows(BusinessRuleException.class, () -> parser.parse("<html>not a PDF</html>".getBytes()));
        assertThrows(BusinessRuleException.class, () -> parser.parse(new byte[TicaSettlementPdfParser.MAX_BYTES + 1]));
    }
}
