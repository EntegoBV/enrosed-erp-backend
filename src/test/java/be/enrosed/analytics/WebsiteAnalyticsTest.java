package be.enrosed.analytics;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
