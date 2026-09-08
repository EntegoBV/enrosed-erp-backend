package be.enrosed.sales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/** Numbers written from a pattern, and read back to find where a series stands. */
class NumberSeriesTest {

    @Test
    void writesTheBooksPartnerSeriesAsTheBooksDo() {
        assertEquals("partner/2026/003", NumberSeries.format("partner/{jaar}/{nr:3}", 2026, 3));
        assertEquals("offerte/partner/2026/012", NumberSeries.format("offerte/partner/{jaar}/{nr:3}", 2026, 12));
        assertEquals("ENR-2026-0001", NumberSeries.format("ENR-{jaar}-{nr}", 2026, 1));
        assertEquals("P2026-1000", NumberSeries.format("P{jaar}-{nr:2}", 2026, 1000), "a sequence never loses digits");
    }

    @Test
    void readsItsOwnNumbersBackAndNothingElse() {
        Matcher hit = NumberSeries.series("partner/{jaar}/{nr:3}", 2026).matcher("partner/2026/047");
        assertTrue(hit.matches());
        assertEquals("047", hit.group(1));
        assertFalse(NumberSeries.series("partner/{jaar}/{nr:3}", 2026).matcher("partner/2025/047").matches(), "last year's series is closed");
        assertFalse(NumberSeries.series("partner/{jaar}/{nr:3}", 2026).matcher("F-2026-0047").matches(), "the plain invoices are another series");
        assertTrue(NumberSeries.valid("partner/{jaar}/{nr:3}"));
        assertFalse(NumberSeries.valid("partner/{jaar}"), "without a sequence there is no series");
    }
}
