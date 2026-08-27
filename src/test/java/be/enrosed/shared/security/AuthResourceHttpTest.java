package be.enrosed.shared.security;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AuthResourceHttpTest {

    private static final String SHARED_TEST_PASSWORD = "named-auth-test-password";

    @Test
    void bothNamedAccountsReceiveTheirServerCanonicalIdentity() {
        given()
                .auth().preemptive().basic("EmRe", SHARED_TEST_PASSWORD)
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("username", equalTo("emre"))
                .body("displayName", equalTo("Emre"))
                .body("roles", hasItem("admin"))
                .body("$", not(hasKey("password")))
                .body("$", not(hasKey("passwordHash")));

        given()
                .auth().preemptive().basic("berat", SHARED_TEST_PASSWORD)
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("username", equalTo("berat"))
                .body("displayName", equalTo("Berat"));
    }

    @Test
    void genericLegacyAccountAndWrongCredentialsStayUnauthorized() {
        given()
                .auth().preemptive().basic("enrosedadmin", SHARED_TEST_PASSWORD)
                .when().get("/api/auth/me")
                .then().statusCode(401);

        given()
                .auth().preemptive().basic("emre", "wrong-password")
                .when().get("/api/auth/me")
                .then().statusCode(401);

        given()
                .auth().preemptive().basic("unknown", SHARED_TEST_PASSWORD)
                .when().get("/api/auth/me")
                .then().statusCode(401);
    }

    @Test
    void pingRemainsPublic() {
        given().when().get("/api/auth/ping")
                .then().statusCode(200).body("status", equalTo("ok"));
    }

    @Test
    void passwordCanBeExchangedForAUserBoundExpiringSessionKey() {
        String token = given()
                .auth().preemptive().basic("EmRe", SHARED_TEST_PASSWORD)
                .when().post("/api/auth/session")
                .then().statusCode(200)
                .body("username", equalTo("emre"))
                .body("displayName", equalTo("Emre"))
                .body("roles", hasItem("admin"))
                .body("token", startsWith("enr1."))
                .body("$", not(hasKey("password")))
                .extract().path("token");

        given()
                .auth().preemptive().basic("emre", token)
                .when().get("/api/auth/me")
                .then().statusCode(200)
                .body("displayName", equalTo("Emre"));

        given()
                .auth().preemptive().basic("berat", token)
                .when().get("/api/auth/me")
                .then().statusCode(401);
    }
}
