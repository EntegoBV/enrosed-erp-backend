package be.enrosed.account;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Gate for the customer login: staff authentication is HTTP Basic only, so a Bearer
 * credential must pass through to a @PermitAll resource untouched, while any Basic
 * credential that is not a staff login is refused before the resource runs.
 */
@QuarkusTest
class BearerOnPermitAllHttpTest {
    private static final String SESSION_SHAPED_TOKEN = "ecs1_" + "A".repeat(43);

    @Test
    void bearerCredentialReachesAPermitAllResource() {
        given().queryParam("language", "EN")
                .header("Authorization", "Bearer " + SESSION_SHAPED_TOKEN)
                .when().get("/api/v1/public/quotes/configuration")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("currency", equalTo("EUR"));
    }

    @Test
    void nonStaffBasicCredentialIsRefusedBeforeAPermitAllResource() {
        given().queryParam("language", "EN")
                .header("Authorization", "Basic Z2FyYmFnZQ==")
                .when().get("/api/v1/public/quotes/configuration")
                .then().statusCode(401);
    }
}
