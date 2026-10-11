package be.enrosed.shared.security;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class StaffContactResourceHttpTest {
    private static final String ENDPOINT = "/api/auth/accounts";
    private static final String PASSWORD = "named-auth-test-password";
    private static final String FIRST_PHONE = "+32 470 12 34 56";
    private static final String SECOND_PHONE = "+32 471 23 45 67";

    @Inject StaffContactService contacts;
    @Inject StaffContactService.Store store;

    @BeforeEach
    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> store.deleteAll());
    }

    @Test
    void configuredAccountsKeepTheirOrderAndSharePersistedContactNumbers() {
        given().auth().preemptive().basic("emre", PASSWORD).get(ENDPOINT).then().statusCode(200)
                .body("username", equalTo(List.of("emre", "berat")))
                .body("displayName", equalTo(List.of("Emre", "Berat")))
                .body("[0].phone", nullValue()).body("[1].phone", nullValue());

        save("EmRe", "  " + FIRST_PHONE + "  ").then().statusCode(200)
                .body("username", equalTo("emre")).body("phone", equalTo(FIRST_PHONE));
        save("berat", SECOND_PHONE).then().statusCode(200)
                .body("displayName", equalTo("Berat")).body("phone", equalTo(SECOND_PHONE));

        given().auth().preemptive().basic("berat", PASSWORD).get(ENDPOINT).then().statusCode(200)
                .body("phone", equalTo(List.of(FIRST_PHONE, SECOND_PHONE)));
        assertEquals(List.of("emre", "berat"), contacts.catalogContacts().stream()
                .map(StaffContactService.Contact::username).toList());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(SECOND_PHONE, store.findById("berat").phone));

        given().auth().preemptive().basic("berat", PASSWORD).get("/api/auth/me").then().statusCode(200)
                .body("phone", equalTo(SECOND_PHONE));
        given().auth().preemptive().basic("berat", PASSWORD).post("/api/auth/session").then().statusCode(200)
                .body("phone", equalTo(SECOND_PHONE));
    }

    @Test
    void clearingAContactPersistsNullAndRemovesItFromCatalogContacts() {
        save("emre", FIRST_PHONE).then().statusCode(200);
        given().auth().preemptive().basic("emre", PASSWORD).contentType("application/json")
                .body("{\"phone\":null}").put(ENDPOINT + "/emre/phone").then().statusCode(200)
                .body("phone", nullValue());
        QuarkusTransaction.requiringNew().run(() -> {
            StaffContactEntity saved = store.findById("emre");
            assertNotNull(saved);
            assertNull(saved.phone);
        });
        assertEquals(List.of(), contacts.catalogContacts());
        assertNull(new StaffContactService(store, "emre|Emre,berat|Berat").phoneFor("emre"));
        given().auth().preemptive().basic("berat", PASSWORD).get(ENDPOINT).then().statusCode(200)
                .body("[0].phone", nullValue());
    }

    @Test
    void unknownAccountsAndMalformedPhonesCannotChangeSavedDetails() {
        save("emre", FIRST_PHONE).then().statusCode(200);
        save("unknown", SECOND_PHONE).then().statusCode(404);
        for (String invalid : List.of("123456", "1234567890123456", "call me", "+32 470\n12 34 56",
                "32+470123456", "+32 470/12 34 56", "+32470123456" + "-".repeat(40))) {
            save("emre", invalid).then().statusCode(400);
        }
        assertEquals(FIRST_PHONE, contacts.phoneFor("emre"));
        assertEquals(1, store.count());
        given().auth().preemptive().basic("emre", PASSWORD).contentType("application/json")
                .body("null").put(ENDPOINT + "/emre/phone").then().statusCode(400);
    }

    @Test
    void anonymousRequestsCannotReadOrSaveAccountContactDetails() {
        given().get(ENDPOINT).then().statusCode(401);
        given().contentType("application/json").body(Map.of("phone", FIRST_PHONE))
                .put(ENDPOINT + "/emre/phone").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "viewer")
    void nonAdministratorsCannotReadOrSaveAccountContactDetails() {
        given().get(ENDPOINT).then().statusCode(403);
        given().contentType("application/json").body(Map.of("phone", FIRST_PHONE))
                .put(ENDPOINT + "/emre/phone").then().statusCode(403);
    }

    private static io.restassured.response.Response save(String username, String phone) {
        return given().auth().preemptive().basic("emre", PASSWORD).contentType("application/json")
                .body(Map.of("phone", phone)).put(ENDPOINT + "/" + username + "/phone");
    }
}
