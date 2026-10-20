package be.enrosed.tica;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TicaCsvParserTest {
    static final String HEADER = "\"Locatie\";\"Exposantennummer\";\"Datum\";\"Tijd\";\"Transactienummer\";\"Kassabonregel\";\"Artikelomschrijving\";\"Beeyond artikelnummer\";\"Uw artikelnummer\";\"Lotus Notes nummer\";\"Barcode\";\"Eigen kenmerk\";\"BTW artikel\";\"Aantal\";\"Kortingspercentage\";\"Regeltotaal bruto\";\"Regeltotaal netto\";\"Transactietype\";\"Klant externe code\";\"Klant bedrijfsnaam\"\n";
    static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    static String line(String id, String quantity, String total) {
        return "\"TICA - Brussel\";\"2132\";\"19-10-2026\";\"13:56:00\";\"TEST100\";\"" + id
                + "\";\"Test product\";\"900001\";\"TICA-TEST-SKU\";\"\";\"999000001\";\"Test\";\"21\";\""
                + quantity + "\";\"0,00\";\"" + total + "\";\"" + total + "\";\"Factuur\";\"TEST-CUSTOMER\";\"Fictieve klant\"\n";
    }
    private final TicaCsvParser parser = new TicaCsvParser();

    @Test void acceptsQuotedCsvAndPreservesSourceAmountsWithoutCalculatingTax() {
        var rows = parser.parse("\ufeff" + HEADER + line("1", "2", "39,90").replace("Test product", "Test; \"\"rose\"\"\nlarge"), "2132", DATE, DATE);
        assertEquals(1, rows.size());
        assertEquals("Test; \"rose\"\nlarge", rows.getFirst().description());
        assertEquals(new BigDecimal("39.90"), rows.getFirst().grossTotal());
        assertEquals(rows.getFirst().grossTotal(), rows.getFirst().netTotal());
        assertEquals(new BigDecimal("2.000"), rows.getFirst().quantity());
        assertFalse(rows.toString().contains("Fictieve klant"));
    }

    @Test void acceptsReturnsAndEuropeanThousandsSeparators() {
        var row = parser.parse(HEADER + line("1", "-2", "-1.234,56"), "2132", DATE, DATE).getFirst();
        assertEquals(new BigDecimal("-1234.56"), row.netTotal());
        assertEquals(new BigDecimal("-2.000"), row.quantity());
    }

    @Test void rejectsWrongAccountAndOutOfRangeDates() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(HEADER + line("1", "2", "39,90"), "9999", DATE, DATE));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(HEADER + line("1", "2", "39,90"), "2132", DATE.minusDays(2), DATE.minusDays(1)));
    }

    @Test void rejectsHtmlTruncatedCsvShiftedColumnsAndInvalidMoney() {
        for (String csv : new String[]{"<html>login</html>", HEADER + "\"unterminated", HEADER + "one;two\n",
                HEADER + line("1", "2", "12.34"), HEADER + line("1", "0", "0,00"), HEADER.replace("Datum", "Unknown")})
            assertThrows(IllegalArgumentException.class, () -> parser.parse(csv, "2132", DATE, DATE));
    }

    @Test void collapsesExactDuplicatesButRejectsConflictingDuplicateKeys() {
        assertEquals(1, parser.parse(HEADER + line("1", "2", "39,90").repeat(2), "2132", DATE, DATE).size());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(HEADER + line("1", "2", "39,90")
                + line("1", "3", "59,85"), "2132", DATE, DATE));
    }

    @Test void headerOnlyMeansSuccessfulEmptyRangeButBlankIsNotAnExport() {
        assertTrue(parser.parse(HEADER, "2132", DATE, DATE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> parser.parse("", "2132", DATE, DATE));
    }
}
