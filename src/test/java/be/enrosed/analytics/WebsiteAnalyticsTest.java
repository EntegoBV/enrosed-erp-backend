package be.enrosed.analytics;

import be.enrosed.analytics.WebsiteAnalyticsDtos.Report;
import be.enrosed.analytics.WebsiteAnalyticsDtos.SourceRow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Page views posted by the website's edge function become the Analyses report. */
@QuarkusTest
class WebsiteAnalyticsTest {

    private static final String VISITOR = "9f2c4d8e1a7b3c5d9f2c4d8e1a7b3c5d";

    @Inject
    WebsiteVisitService service;

    @Test
    void visitsAreCountedAndReported() {
        for (String path : List.of("/", "/de/products/", "/products/glass-dome/?utm_source=newsletter")) {
            Map<String, Object> beacon = new HashMap<>();
            beacon.put("path", path);
            beacon.put("visitor", VISITOR);
            beacon.put("country", "DE");
            beacon.put("city", "Berlin");
            beacon.put("referrer", "https://www.google.com/");
            beacon.put("screenWidth", 390);
            given().contentType("application/json").body(beacon)
                    .when().post("/api/public/analytics/visits")
                    .then().statusCode(204);
        }

        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get("/api/analytics/website?days=7")
                .then().statusCode(200)
                .body("totals.visits", greaterThanOrEqualTo(3))
                .body("totals.visitors", greaterThanOrEqualTo(1))
                .body("pages.path", hasItem("/products/glass-dome/"))
                .body("pages.kind", hasItem("PRODUCT"))
                .body("countries.country", hasItem("DE"))
                .body("cities.city", hasItem("Berlin"))
                .body("sources.source", hasItem("Google"))
                .body("devices.device", hasItem("MOBILE"))
                .body("locales.locale", hasItem("de"));
    }

    @Test
    void junkIsRefusedAndTheReportNeedsAnAccount() {
        given().contentType("application/json").body(Map.of("path", "no-slash", "visitor", VISITOR))
                .when().post("/api/public/analytics/visits").then().statusCode(400);
        given().contentType("application/json").body(Map.of("path", "/", "visitor", "not-a-hash"))
                .when().post("/api/public/analytics/visits").then().statusCode(400);
        given().when().get("/api/analytics/website").then().statusCode(401);
    }

    @Test
    void ourOwnTownsNeverCount() {
        String visitor = "5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e";
        for (String city : List.of("Mol", "GEEL", "Tessenderlo", "Arendonk")) {
            Map<String, Object> beacon = new HashMap<>();
            beacon.put("path", "/nl/own-town-check/");
            beacon.put("visitor", visitor);
            beacon.put("country", "BE");
            beacon.put("city", city);
            given().contentType("application/json").body(beacon)
                    .when().post("/api/public/analytics/visits")
                    .then().statusCode(204);
        }
        String body = given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get("/api/analytics/website?days=7")
                .then().statusCode(200)
                .body("excludedCities", hasItem("Mol"))
                .extract().asString();
        assertFalse(body.contains("own-town-check"), "a visit from our own town is not stored");
        assertFalse(body.contains("\"Tessenderlo\",\"country\""), body);
        assertTrue(WebsiteVisitService.cityKey("Mól ").equals("mol"));
    }

    @Test
    void aiAssistantsAndTheMergedTownNeverCountAndOldRowsCanBePurged() {
        String visitor = "9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c";
        for (String referrer : List.of("https://chatgpt.com/c/abc", "https://www.claude.ai/chat/1", "https://gemini.google.com/app")) {
            Map<String, Object> beacon = new HashMap<>();
            beacon.put("path", "/nl/ai-check/");
            beacon.put("visitor", visitor);
            beacon.put("country", "NL");
            beacon.put("city", "Utrecht");
            beacon.put("referrer", referrer);
            given().contentType("application/json").body(beacon)
                    .when().post("/api/public/analytics/visits")
                    .then().statusCode(204);
        }
        Map<String, Object> campaign = new HashMap<>();
        campaign.put("path", "/nl/ai-campaign-check/");
        campaign.put("visitor", visitor);
        campaign.put("country", "NL");
        campaign.put("city", "Utrecht");
        campaign.put("utmSource", "chatgpt.com");
        given().contentType("application/json").body(campaign)
                .when().post("/api/public/analytics/visits")
                .then().statusCode(204);
        Map<String, Object> merged = new HashMap<>();
        merged.put("path", "/nl/merged-town-check/");
        merged.put("visitor", visitor);
        merged.put("country", "BE");
        merged.put("city", "Tessenderlo-Ham");
        given().contentType("application/json").body(merged)
                .when().post("/api/public/analytics/visits")
                .then().statusCode(204);

        String body = given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get("/api/analytics/website?days=7")
                .then().statusCode(200)
                .extract().asString();
        assertFalse(body.contains("ai-check"), "a page opened from an AI assistant is not stored");
        assertFalse(body.contains("ai-campaign-check"), "a campaign source naming an AI assistant is not stored");
        assertFalse(body.contains("merged-town-check"), "the merged municipality counts as our own town");

        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().delete("/api/analytics/website/excluded")
                .then().statusCode(200)
                .body("removed", org.hamcrest.Matchers.greaterThanOrEqualTo(0));
    }

    @Test
    void ourOwnDevicesAndTheErpNeverCount() {
        String visitor = "7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a";
        Map<String, Object> optedOut = new HashMap<>();
        optedOut.put("path", "/nl/own-device-check/");
        optedOut.put("visitor", visitor);
        optedOut.put("country", "NL");
        optedOut.put("city", "Utrecht");
        optedOut.put("internal", true);
        given().contentType("application/json").body(optedOut)
                .when().post("/api/public/analytics/visits")
                .then().statusCode(204);
        Map<String, Object> fromErp = new HashMap<>();
        fromErp.put("path", "/nl/own-erp-check/");
        fromErp.put("visitor", visitor);
        fromErp.put("country", "NL");
        fromErp.put("city", "Utrecht");
        fromErp.put("referrer", "https://erp.enrosed.com/website");
        given().contentType("application/json").body(fromErp)
                .when().post("/api/public/analytics/visits")
                .then().statusCode(204);
        Map<String, Object> fromPreview = new HashMap<>(fromErp);
        fromPreview.put("path", "/nl/own-preview-check/");
        fromPreview.put("referrer", "https://enrosed-website-git-main-emre.vercel.app/nl/");
        given().contentType("application/json").body(fromPreview)
                .when().post("/api/public/analytics/visits")
                .then().statusCode(204);

        String body = given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get("/api/analytics/website?days=7")
                .then().statusCode(200)
                .extract().asString();
        assertFalse(body.contains("own-device-check"), "a device that opted out is not stored");
        assertFalse(body.contains("own-erp-check"), "a page opened from the ERP is not stored");
        assertFalse(body.contains("own-preview-check"), "a page opened from a preview build is not stored");
    }

    @Test
    void sessionsTellWhereTheyBeganEndedAndHowFarTheyGot() {
        String visitor = "3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c";
        for (String path : List.of("/fr/collections/roses/", "/fr/products/session-check-dome/", "/fr/quote/")) {
            Map<String, Object> beacon = new HashMap<>();
            beacon.put("path", path);
            beacon.put("visitor", visitor);
            beacon.put("country", "FR");
            beacon.put("city", "Lyon");
            given().contentType("application/json").body(beacon)
                    .when().post("/api/public/analytics/visits")
                    .then().statusCode(204);
        }
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get("/api/analytics/website?days=1")
                .then().statusCode(200)
                .body("days", equalTo(1))
                .body("perDay.size()", equalTo(1))
                .body("perHour.size()", equalTo(24))
                .body("entryPages.path", hasItem("/fr/collections/roses/"))
                .body("exitPages.path", hasItem("/fr/quote/"))
                .body("funnel.sessions", greaterThanOrEqualTo(1))
                .body("funnel.productSessions", greaterThanOrEqualTo(1))
                .body("funnel.quoteSessions", greaterThanOrEqualTo(1))
                .body("totals.activeNow", greaterThanOrEqualTo(1))
                .body("previous.visits", greaterThanOrEqualTo(0))
                .body("generatedAt", notNullValue());
    }

    @Test
    void pagesAndSourcesAreClassified() {
        assertEquals("HOME", WebsiteVisitService.pageKind("/nl/"));
        assertEquals("PRODUCT", WebsiteVisitService.pageKind("/fr/products/glass-dome/"));
        assertEquals("PRODUCTS", WebsiteVisitService.pageKind("/products/"));
        assertEquals("COLLECTION", WebsiteVisitService.pageKind("/collections/roses/"));
        assertEquals("nl", WebsiteVisitService.localeOf("/nl/quote/", null));
        assertEquals("en", WebsiteVisitService.localeOf("/quote/", "en-GB"));
        assertEquals("google.com", WebsiteVisitService.referrerHost("https://www.google.com/search?q=roses"));
        assertNull(WebsiteVisitService.referrerHost("https://enrosed.com/nl/"));
        assertEquals("MOBILE", WebsiteVisitService.deviceFor(390));
    }

    /*
     * The three below fold rows stored on a day of their own, weeks back, and
     * read the report as it was on that day: nothing else lands in their window.
     */

    @Test
    void aSourceCountsOncePerSessionNotOncePerPage() {
        ZonedDateTime now = day(50).withHour(15);
        String google = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
        String instagram = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2";
        QuarkusTransaction.requiringNew().run(() -> {
            // From Google, then two clicks inside the site (their referrer is enrosed.com, stored as none).
            store(google, "/", now.withHour(9), "google.com");
            store(google, "/products/", now.withHour(9).withMinute(5), null);
            store(google, "/products/source-check-dome/", now.withHour(9).withMinute(10), null);
            // The same visitor back after more than half an hour: a new session, typed in.
            store(google, "/nl/", now.withHour(11), null);
            store(instagram, "/de/", now.withHour(10), "instagram.com");
            store(instagram, "/de/quote/", now.withHour(10).withMinute(2), null);
        });

        Report report = service.report(1, now);
        Map<String, Long> sources = report.sources().stream()
                .collect(Collectors.toMap(SourceRow::source, SourceRow::visits));
        assertEquals(Map.of("Google", 1L, "Instagram", 1L, "Rechtstreeks", 1L), sources,
                "the clicks inside the site are not booked as Rechtstreeks");
        assertEquals(6, report.totals().visits());
        assertEquals(3, report.totals().sessions());
        assertEquals(report.totals().sessions(), sources.values().stream().mapToLong(Long::longValue).sum(),
                "the sources add up to the sessions");
    }

    @Test
    void thePreviousWindowEndsAtTheSameClockTime() {
        ZonedDateTime now = day(80).withHour(10);
        String visitor = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3";
        QuarkusTransaction.requiringNew().run(() -> {
            store(visitor, "/clock-check/", now.withHour(9), null);
            store(visitor, "/clock-check/", now.minusDays(1).withHour(9).withMinute(30), null);
            // Yesterday after this clock time: today has not got there yet, so it does not count.
            store(visitor, "/clock-check/", now.minusDays(1).withHour(11), null);
        });

        Report report = service.report(1, now);
        assertEquals(1, report.totals().visits());
        assertEquals(1, report.previous().visits(), "yesterday until 10 o'clock, not the whole of yesterday");
        assertEquals(1, report.previous().sessions());
    }

    @Test
    void theReportListsUpToTwoHundredPages() {
        ZonedDateTime now = day(110).withHour(18);
        String visitor = "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4";
        QuarkusTransaction.requiringNew().run(() -> {
            for (int index = 0; index < 205; index++) {
                store(visitor, "/products/page-cap-" + index + "/", now.withHour(9).plusSeconds(index), null);
            }
        });

        Report report = service.report(1, now);
        assertEquals(200, report.pages().size(), "every product in every language fits; the cap was 40");
        assertEquals(205, report.kinds().stream().filter(kind -> kind.kind().equals("PRODUCT"))
                .mapToLong(WebsiteAnalyticsDtos.KindRow::visits).sum());
    }

    private static ZonedDateTime day(int daysBack) {
        return ZonedDateTime.now(WebsiteVisitService.ZONE).minusDays(daysBack).withMinute(0).withSecond(0).withNano(0);
    }

    private static void store(String visitor, String path, ZonedDateTime at, String referrerHost) {
        WebsiteVisitEntity row = new WebsiteVisitEntity();
        row.occurredAt = at.toInstant();
        row.visitor = visitor;
        row.path = path;
        row.pageKind = WebsiteVisitService.pageKind(path);
        row.locale = WebsiteVisitService.localeOf(path, null);
        row.country = "DE";
        row.city = "Berlin";
        row.referrerHost = referrerHost;
        row.device = "DESKTOP";
        row.persist();
    }
}
