package be.enrosed.shared;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Calendar arithmetic for operational deadlines.
 *
 * <p>ENROSED plans transport and internal follow-up on Monday through Friday.
 * Public holidays are deliberately not guessed here: they differ per country
 * and can still be adjusted manually in the planner.</p>
 */
public final class BusinessDays {

    private BusinessDays() {}

    /** Adds a non-negative number of working days; the start day is day zero. */
    public static LocalDate add(LocalDate start, int days) {
        Objects.requireNonNull(start, "start");
        if (days < 0) throw new IllegalArgumentException("Werkdagen kunnen niet negatief zijn");

        LocalDate result = start;
        int remaining = days;
        while (remaining > 0) {
            result = result.plusDays(1);
            if (isBusinessDay(result)) remaining--;
        }
        return result;
    }

    /** Returns the next working day, never the supplied date itself. */
    public static LocalDate next(LocalDate date) {
        return add(date, 1);
    }

    /** Keeps a weekday unchanged and moves Saturday/Sunday to Monday. */
    public static LocalDate onOrNext(LocalDate date) {
        Objects.requireNonNull(date, "date");
        LocalDate result = date;
        while (!isBusinessDay(result)) result = result.plusDays(1);
        return result;
    }

    public static boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
