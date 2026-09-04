package be.enrosed.analytics;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
}
