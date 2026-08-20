package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;

/**
 * Estimates when a line can be delivered.
 *
 * The count runs in working days: from the next working day, plus the
 * destination country's transit time. Saturday and Sunday do not count,
 * because nothing drives then.
 *
 * With insufficient stock, no date comes out. Inventing an estimate for
 * something still on its way from China is worse than no estimate: the
 * customer counts on it. In that case the seller fills in a delivery week
 * by hand, or leaves the field empty until the container is booked.
 */
@ApplicationScoped
public class DeliveryCalculator {

    public record Estimate(
            LocalDate earliestDate,
            String week,
            boolean fromStock,
            Integer shortfall,
            String explanation
    ) {}

    public Estimate estimate(Country country, int quantity, int stockQuantity) {
        return estimate(country, quantity, stockQuantity, true);
    }

    public Estimate estimate(
            Country country, int quantity, int stockQuantity, boolean inventoryKnown) {
        int transitDays = country == null ? 0 : Math.max(0, country.transitDays());

        if (quantity <= 0) {
            return new Estimate(null, null, false, 0, "Geen aantal ingevuld");
        }

        if (!inventoryKnown) {
            return new Estimate(null, null, false, null, "Voorraad nog niet bevestigd");
        }

        if (stockQuantity >= quantity) {
            LocalDate date = addWorkingDays(nextWorkingDay(LocalDate.now()), transitDays);
            return new Estimate(date, weekOf(date), true, 0,
                    "Uit voorraad, " + transitDays + " werkdag(en) transit naar "
                            + (country == null ? "de bestemming" : country.name()));
        }

        int shortfall = quantity - Math.max(0, stockQuantity);
        return new Estimate(null, null, false, shortfall,
                "Onvoldoende voorraad: " + shortfall + " stuks te kort. "
                        + "Vul zelf een leverweek in zodra de container geboekt is.");
    }

    /** Today when it is a working day, otherwise the next Monday. */
    public LocalDate nextWorkingDay(LocalDate from) {
        LocalDate date = from.plusDays(1);
        while (isWeekend(date)) {
            date = date.plusDays(1);
        }
        return date;
    }

    public LocalDate addWorkingDays(LocalDate from, int days) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (!isWeekend(date)) added++;
        }
        return date;
    }

    /** ISO week notation, like "2026-W34"; that is how logistics talks. */
    public String weekOf(LocalDate date) {
        if (date == null) return null;
        WeekFields weekFields = WeekFields.ISO;
        return String.format("%d-W%02d",
                date.get(weekFields.weekBasedYear()),
                date.get(weekFields.weekOfWeekBasedYear()));
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
