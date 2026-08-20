package be.enrosed.sourcing.adapter.out.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketFetchersTest {

    @Test
    void ccfiSelectsEuropeAndKeepsProviderPublicationDates() throws Exception {
        var observations = CcfiFetcher.parseEurope("""
                {"data":{"currentDate":"2026-08-07","lastDate":"2026-07-31",
                  "lineDataList":[
                    {"properties":{"lineName_EN":"JAPAN"},
                     "currentContent":941.44,"lastContent":932.45},
                    {"properties":{"lineName_EN":"EUROPE"},
                     "currentContent":2416.45,"lastContent":2481.69}
                  ]}}
                """);

        assertEquals(2, observations.size());
        assertEquals(LocalDate.of(2026, 8, 7), observations.get(0).publishedOn());
        assertEquals(new BigDecimal("2416.45"), observations.get(0).value());
        assertEquals(LocalDate.of(2026, 7, 31), observations.get(1).publishedOn());
    }

    @Test
    void ncfiReadsExactNingboEuropeRouteNotComposite() {
        var observations = NcfiFetcher.parseEurope("""
                <table>
                  <tr><th>Route</th><th>07-08-2026</th><th>31-07-2026</th>
                      <th>Weekly change (%)</th></tr>
                  <tr><td>Composite index</td><td>1,999.00</td><td>1,888.00</td><td>5.88</td></tr>
                  <tr><td>Ningbo - Europe</td><td>2,051.07</td><td>2,093.01</td><td>-2.00</td></tr>
                </table>
                """);

        assertEquals(2, observations.size());
        assertEquals(LocalDate.of(2026, 8, 7), observations.get(0).publishedOn());
        assertEquals(new BigDecimal("2051.07"), observations.get(0).value());
        assertEquals(new BigDecimal("2093.01"), observations.get(1).value());
    }

    @Test
    void ncfiRejectsACompositeOnlyPage() {
        assertTrue(NcfiFetcher.parseEurope("""
                Route 07-08-2026 31-07-2026 Weekly change (%)
                Composite index 1,999.00 1,888.00 5.88
                """).isEmpty());
    }

    @Test
    void drewryParserNeverTurnsAnotherRouteIntoShanghaiRotterdam() {
        assertEquals(new BigDecimal("2345"),
                DrewryWciFetcher.parseShanghaiRotterdam(
                        "Shanghai to Rotterdam &nbsp; $2,345 per 40ft"));
        assertNull(DrewryWciFetcher.parseShanghaiRotterdam(
                "Shanghai to Los Angeles $2,345 per 40ft"));
    }

    @Test
    void dailyThrottleAllowsOneAttemptPerUtcCalendarDay() {
        Instant checked = Instant.parse("2026-08-20T08:00:00Z");

        assertFalse(MarketSourceTracker.dailyCheckDue(
                checked, Instant.parse("2026-08-20T23:59:59Z")));
        assertTrue(MarketSourceTracker.dailyCheckDue(
                checked, Instant.parse("2026-08-21T00:00:00Z")));
        assertTrue(MarketSourceTracker.dailyCheckDue(
                null, Instant.parse("2026-08-20T08:00:00Z")));
    }

    @Test
    void undatedProviderValueIsStoredAtMostWeekly() {
        LocalDate last = LocalDate.of(2026, 8, 14);

        assertFalse(MarketSourceTracker.weeklyObservationDue(
                last, LocalDate.of(2026, 8, 20)));
        assertTrue(MarketSourceTracker.weeklyObservationDue(
                last, LocalDate.of(2026, 8, 21)));
    }
}
