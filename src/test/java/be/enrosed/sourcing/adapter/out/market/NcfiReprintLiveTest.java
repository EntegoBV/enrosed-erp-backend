package be.enrosed.sourcing.adapter.out.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talks to the real reprint site. Off by default so the suite stays
 * offline; run it by hand when the connector needs a check:
 * {@code mvn test -Dtest=NcfiReprintLiveTest -Denrosed.live-market=true}.
 */
class NcfiReprintLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "enrosed.live-market", matches = "true")
    void readsTheEuropeRouteOutOfAPublishedWeek() throws Exception {
        var table = NcfiReprint.fetchWeek(LocalDate.of(2026, 8, 28)).orElseThrow();

        assertEquals(LocalDate.of(2026, 8, 28), table.currentOn());
        assertEquals(LocalDate.of(2026, 8, 21), table.previousOn());
        assertNotNull(table.europeCurrent());
        assertNotNull(table.compositeCurrent());
        assertTrue(table.europeCurrent().signum() > 0);
    }
}
