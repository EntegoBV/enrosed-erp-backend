package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;

/**
 * Schat wanneer een regel geleverd kan worden.
 *
 * Er wordt gerekend in werkdagen: vanaf de eerstvolgende werkdag, plus de
 * transittijd van het bestemmingsland. Zaterdag en zondag tellen niet mee,
 * want er rijdt dan niets.
 *
 * Staat er niet genoeg op voorraad, dan komt er geen datum uit. Een schatting
 * verzinnen voor iets dat nog uit China moet komen is erger dan geen
 * schatting: de klant rekent erop. In dat geval vult de verkoper zelf een
 * leverweek in, of laat hij het veld leeg tot de container geboekt is.
 */
@ApplicationScoped
public class DeliveryCalculator {

    public record Estimate(
            LocalDate earliestDate,
            String week,
            boolean fromStock,
            int shortfall,
            String explanation
    ) {}

    public Estimate estimate(Country country, int quantity, int stockQuantity) {
        int transitDays = country == null ? 0 : Math.max(0, country.transitDays());

        if (quantity <= 0) {
            return new Estimate(null, null, false, 0, "Geen aantal ingevuld");
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

    /** Vandaag als het een werkdag is, anders de eerstvolgende maandag. */
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

    /** ISO-weeknotatie, zoals "2026-W34"; zo praat de logistiek. */
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
