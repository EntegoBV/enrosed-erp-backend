package be.enrosed.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The formatting that lands on the quote.
 *
 * Dates and weeks are exactly the kind of thing that fails silently: an
 * American month-first notation gets misread without anyone noticing, and
 * week numbering around New Year is wrong in most home-grown variants.
 * Hence pinning it down here.
 */
class DocumentFormatTest {

    @Test
    @DisplayName("datums staan in Belgische vorm")
    void belgianDates() {
        assertEquals("25/05/2026", DocumentFormat.be(LocalDate.of(2026, 5, 25)));
        assertEquals("01/01/2027", DocumentFormat.be(LocalDate.of(2027, 1, 1)));
        assertEquals("", DocumentFormat.be(null));

        assertEquals("17/08/2026", DocumentFormat.beDate("2026-08-17"));
        assertEquals("", DocumentFormat.beDate(null));
        assertEquals("", DocumentFormat.beDate("  "));
    }

    @Test
    @DisplayName("een leverweek wordt uitgeschreven met begin- en einddatum")
    void weekWithRange() {
        assertEquals("week 42 (12/10 - 18/10/2026)", DocumentFormat.week("2026-W42"));
        assertEquals("week 44 (26/10 - 01/11/2026)", DocumentFormat.week("2026-W44"));
    }

    /**
     * ISO week 1 is the week holding the year's first Thursday. That is why
     * week 1 of 2026 starts back in December 2025, and 2026 has a week 53
     * running into January 2027.
     */
    @Test
    @DisplayName("weken rond nieuwjaar lopen over het jaareinde")
    void weeksAcrossNewYear() {
        assertEquals("week 1 (29/12 - 04/01/2026)", DocumentFormat.week("2026-W01"));
        assertEquals("week 53 (28/12 - 03/01/2027)", DocumentFormat.week("2026-W53"));
        assertEquals("week 1 (04/01 - 10/01/2027)", DocumentFormat.week("2027-W01"));
    }

    @Test
    @DisplayName("wat geen week is blijft ongemoeid")
    void leavesNonWeeksAlone() {
        assertEquals("", DocumentFormat.week(null));
        assertEquals("", DocumentFormat.week("   "));
        assertEquals("in overleg", DocumentFormat.week("in overleg"));
    }
}
