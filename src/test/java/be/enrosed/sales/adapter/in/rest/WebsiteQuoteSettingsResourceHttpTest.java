package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.WebsiteQuoteSettingsEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class WebsiteQuoteSettingsResourceHttpTest {
    private static final String ENDPOINT = "/api/website/quote-settings";
    @Inject EntityManager entities;

    @BeforeEach
    @AfterEach
    void removeTestSetting() {
        QuarkusTransaction.requiringNew().run(() -> {
            WebsiteQuoteSettingsEntity settings = entities.find(WebsiteQuoteSettingsEntity.class, 1L);
            if (settings != null) entities.remove(settings);
        });
    }

    @Test
    void startsVisibleThenPersistsHiddenAndRestoresVisible() {
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .get(ENDPOINT).then().statusCode(200).header("Cache-Control", "no-store")
                .body("pricesVisible", equalTo(true));
        for (boolean visible : new boolean[]{false, true}) {
            given().auth().preemptive().basic("emre", "named-auth-test-password").contentType("application/json")
                    .body("{\"pricesVisible\":" + visible + "}").put(ENDPOINT)
                    .then().statusCode(200).body("pricesVisible", equalTo(visible));
            given().auth().preemptive().basic("berat", "named-auth-test-password")
                    .get(ENDPOINT).then().statusCode(200).body("pricesVisible", equalTo(visible));
        }
    }

    @Test
    void theAnonymousCatalogueAnnouncesWithheldPricesWithItsUsualCacheHeader() {
        for (boolean visible : new boolean[]{false, true}) {
            given().auth().preemptive().basic("emre", "named-auth-test-password").contentType("application/json")
                    .body("{\"pricesVisible\":" + visible + "}").put(ENDPOINT).then().statusCode(200);
            for (String channel : new String[]{"WEBSITE", "ORDER_APP", "CATALOGUE"}) {
                String body = given().queryParam("channel", channel).queryParam("language", "EN")
                        .get("/api/v1/public/catalog/families").then().statusCode(200)
                        .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                        .body("pricesVisible", equalTo(visible))
                        .extract().asString();
                if (!visible) assertFalse(body.contains("\"amount\""), channel);
                String legacy = given().queryParam("channel", channel).queryParam("language", "EN")
                        .get("/api/v1/public/catalog").then().statusCode(200)
                        .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                        .extract().asString();
                if (!visible) assertFalse(legacy.matches("(?s).*\"salesPriceEur\":\\s*[0-9].*"), channel);
            }
        }
    }

    @Test
    void anonymousUsersCannotReadOrChangeTheSetting() {
        given().get(ENDPOINT).then().statusCode(401);
        given().contentType("application/json").body("{\"pricesVisible\":false}")
                .put(ENDPOINT).then().statusCode(401);
    }

    @Test
    void missingValueCannotAccidentallySwitchPricesOff() {
        for (String body : new String[]{"{}", "{\"pricesVisible\":null}", "null"}) {
            given().auth().preemptive().basic("emre", "named-auth-test-password").contentType("application/json")
                    .body(body).put(ENDPOINT).then().statusCode(400);
        }
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .get(ENDPOINT).then().statusCode(200).body("pricesVisible", equalTo(true));
    }
}
