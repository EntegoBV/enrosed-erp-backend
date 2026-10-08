package be.enrosed.inventory.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The count and the closing read dates on the Belgian calendar, wherever the server runs. */
public final class InventoryClock {

    public static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private InventoryClock() {}

    /** The instant a closing date ends: 00:00 in Brussels of the day after it. */
    public static Instant cutoffAt(LocalDate closingDate) {
        return closingDate.plusDays(1).atStartOfDay(BRUSSELS).toInstant();
    }

    public static LocalDate today() {
        return LocalDate.now(BRUSSELS);
    }
}
