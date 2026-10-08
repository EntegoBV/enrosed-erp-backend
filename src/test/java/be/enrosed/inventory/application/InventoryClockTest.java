package be.enrosed.inventory.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InventoryClockTest {

    @Test
    void theZoneIsBrussels() {
        assertEquals(ZoneId.of("Europe/Brussels"), InventoryClock.BRUSSELS);
    }

    @Test
    void aClosingDateEndsAtMidnightInBrusselsOfTheNextDay() {
        // The worked example of the valuation: 31/12/2026 ends on 01/01/2027 00:00 in Brussels.
        assertEquals(Instant.parse("2026-12-31T23:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 12, 31)));
        assertEquals(LocalDate.of(2027, 1, 1),
                InventoryClock.cutoffAt(LocalDate.of(2026, 12, 31)).atZone(InventoryClock.BRUSSELS).toLocalDate());
    }

    @Test
    void theCutoffFollowsSummerTime() {
        // An extended financial year can close in summer: Brussels is then two hours ahead of UTC.
        assertEquals(Instant.parse("2026-06-30T22:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 6, 30)));
        // The night the clock moves forward: 28 March still ends on winter time, 29 March on summer time.
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 3, 28)));
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 3, 29)));
        // And back: 25 October 2026 lasts 25 hours.
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 10, 24)));
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), InventoryClock.cutoffAt(LocalDate.of(2026, 10, 25)));
    }

    @Test
    void todayIsTheBelgianDate() {
        LocalDate before = LocalDate.now(InventoryClock.BRUSSELS);
        LocalDate today = InventoryClock.today();
        LocalDate after = LocalDate.now(InventoryClock.BRUSSELS);
        assertFalse(today.isBefore(before));
        assertFalse(today.isAfter(after));
    }
}
