package be.enrosed.shared;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessDaysTest {

    @Test
    void skipsTheWeekendWhenAddingDays() {
        LocalDate friday = LocalDate.of(2026, 8, 28);

        assertEquals(LocalDate.of(2026, 8, 31), BusinessDays.add(friday, 1));
        assertEquals(LocalDate.of(2026, 9, 2), BusinessDays.add(friday, 3));
    }

    @Test
    void movesAWeekendDateToMonday() {
        assertEquals(LocalDate.of(2026, 8, 31),
                BusinessDays.onOrNext(LocalDate.of(2026, 8, 29)));
        assertEquals(LocalDate.of(2026, 8, 31),
                BusinessDays.onOrNext(LocalDate.of(2026, 8, 31)));
    }

    @Test
    void refusesNegativeDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> BusinessDays.add(LocalDate.of(2026, 8, 28), -1));
    }
}
