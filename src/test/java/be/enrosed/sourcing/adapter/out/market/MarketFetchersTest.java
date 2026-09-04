package be.enrosed.sourcing.adapter.out.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Properties;

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
    void ncfiReprintPicksTheWeeklyDataPdfDespiteTheLeadingSpace() {
        String html = """
                <a href=" https://www.hellenicshippingnews.com/wp-content/uploads/2026/08/Ningbo-Containerized-Freight-Index-Weekly-Commentary-2026-8.8-8.14.pdf">Download PDF</a>
                Below find the NCFI Weekly Index Data chart:
                <a href=" https://www.hellenicshippingnews.com/wp-content/uploads/2026/08/NCFI-Weekly-Index-Data-2026-8.8-8.14.pdf">Download PDF</a>
                """;

        assertEquals("https://www.hellenicshippingnews.com/wp-content/uploads/2026/08/"
                + "NCFI-Weekly-Index-Data-2026-8.8-8.14.pdf", NcfiReprint.dataPdfLink(html));
        assertNull(NcfiReprint.dataPdfLink("<a href=\"/x/Weekly-Commentary.pdf\">only commentary</a>"));
        assertEquals("https://www.hellenicshippingnews.com/"
                + "ningbo-containerized-freight-index-report-14-august-2026/",
                NcfiReprint.articleUrl(LocalDate.of(2026, 8, 14)));
    }

    @Test
    void ncfiReprintReadsEuropeAndCompositeWithBothWeekDates() {
        var table = NcfiReprint.parseTable("""
                Route
                Previous Index Current Index Weekly
                Growth(%) (2026-08-07) (2026-08-14)
                Composite Index 2423.15 2487.44 2.65% #
                Europe 2051.07 1980.30 -3.45% #
                W. Mediterranean 2269.56 2166.22 -4.55% #
                """);

        assertEquals(LocalDate.of(2026, 8, 7), table.previousOn());
        assertEquals(LocalDate.of(2026, 8, 14), table.currentOn());
        assertEquals(new BigDecimal("2487.44"), table.compositeCurrent());
        assertEquals(new BigDecimal("2423.15"), table.compositePrevious());
        assertEquals(new BigDecimal("1980.30"), table.europeCurrent());
        assertEquals(new BigDecimal("2051.07"), table.europePrevious());

        var observations = NcfiFetcher.europeObservations(table);
        assertEquals(2, observations.size());
        assertEquals(LocalDate.of(2026, 8, 14), observations.get(0).publishedOn());
        assertEquals(new BigDecimal("1980.30"), observations.get(0).value());
        assertEquals(LocalDate.of(2026, 8, 7), observations.get(1).publishedOn());

        assertEquals(new BigDecimal("2487.44"),
                NcfiCompositeFetcher.compositeFor(table, LocalDate.of(2026, 8, 14)));
        assertEquals(new BigDecimal("2423.15"),
                NcfiCompositeFetcher.compositeFor(table, LocalDate.of(2026, 8, 7)));
        assertNull(NcfiCompositeFetcher.compositeFor(table, LocalDate.of(2026, 8, 21)));
    }

    @Test
    void ncfiReprintNeverTurnsAnotherRowIntoTheEuropeRoute() {
        var table = NcfiReprint.parseTable("""
                Previous Index (2026-08-07) Current Index (2026-08-14)
                Composite Index 2,423.15 2,487.44 2.65%
                E. Mediterranean 1720.53 1645.23 -4.38%
                """);

        assertEquals(new BigDecimal("2487.44"), table.compositeCurrent());
        assertNull(table.europeCurrent());
        assertTrue(NcfiFetcher.europeObservations(table).isEmpty());
        assertNull(NcfiReprint.parseTable("no dated table here"));
    }

    @Test
    void ncfiReprintReadsTheTableOutOfARealPdf() throws Exception {
        byte[] pdf;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(40, 740);
                content.showText("Route   Previous Index (2026-08-21)   Current Index (2026-08-28)   Weekly Growth(%)");
                content.newLineAtOffset(0, -18);
                content.showText("Composite Index   2510.20   2455.10   -2.20%");
                content.newLineAtOffset(0, -18);
                content.showText("Europe   1930.55   1875.40   -2.86%");
                content.endText();
            }
            var out = new java.io.ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }

        var table = NcfiReprint.parseTable(NcfiReprint.extractText(pdf));

        assertEquals(LocalDate.of(2026, 8, 28), table.currentOn());
        assertEquals(new BigDecimal("1875.40"), table.europeCurrent());
        assertEquals(new BigDecimal("1930.55"), table.europePrevious());
        assertEquals(new BigDecimal("2455.10"), table.compositeCurrent());
    }

    @Test
    void ncfiCompositeStillReadsOlderReprintsFromTheirText() {
        assertEquals(new BigDecimal("2487.4"), NcfiCompositeFetcher.parsePoints(
                "<p>Ningbo Containerized Freight Index (NCFI) issued by Ningbo Shipping Exchange "
                + "(NBSE) quotes 2487.4 points,</p>"));
        assertNull(NcfiCompositeFetcher.parsePoints(
                "<p>Ningbo Containerized Freight Index (NCFI) issued by Ningbo Shipping Exchange "
                + "(NBSE) quotes <a>Download PDF</a></p>"));
    }

    @Test
    void ncfiRecognizesProviderChallengeInsteadOfTreatingItAsMissingPublication() {
        assertTrue(NcfiFetcher.isProviderChallenge("""
                <!doctype html><html><head><title>Challenge Validation</title></head>
                <body>Request validation</body></html>
                """));
        assertFalse(NcfiFetcher.isProviderChallenge("""
                <html><head><title>Ningbo Containerized Freight Index Report</title></head></html>
                """));
        assertTrue(MarketSourceTracker.providerAccessRequired(
                "IllegalStateException: Provider challenge received; configure the authorized feed"));
        assertFalse(MarketSourceTracker.providerAccessRequired(
                "IllegalStateException: No recent NCFI reprint with the Europe route found"));
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

    @Test
    void ncfiHistoryTopUpWalksBackOneWeekAtATimeWithinABudget() {
        var candidates = NcfiFetcher.historyCandidateWeeks();

        assertEquals(32, candidates.size());
        assertEquals(java.util.List.of(1, 2, 3, 4, 5, 6),
                candidates.subList(0, NcfiFetcher.HISTORY_REQUEST_BUDGET));
        assertEquals(candidates.size(), new HashSet<>(candidates).size());
        assertTrue(candidates.stream().allMatch(weeks -> weeks >= 1 && weeks <= 32));
        assertEquals(26, NcfiFetcher.HISTORY_TARGET);
    }

    @Test
    void providerConnectorsAreEnabledByDefaultButKeepEnvironmentOverrides() throws Exception {
        Properties properties = new Properties();
        try (var stream = MarketFetchersTest.class.getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }

        assertEquals("${DREWRY_AUTOMATED_ACCESS_AUTHORIZED:true}", properties.getProperty(
                "enrosed.market.drewry.automated-access-authorized"));
        assertEquals("${NCFI_AUTOMATED_ACCESS_AUTHORIZED:true}", properties.getProperty(
                "enrosed.market.ncfi.automated-access-authorized"));
        assertEquals("${CCFI_AUTOMATED_ACCESS_AUTHORIZED:true}", properties.getProperty(
                "enrosed.market.ccfi.automated-access-authorized"));
    }
}
