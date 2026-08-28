package be.enrosed.publicform;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class PublicFormConfigurationResourceHttpTest {
    @Test
    void anonymousClientsCanMintPurposeBoundFormStartToken() {
        given().queryParam("purpose", "CONTACT")
                .when().get("/api/v1/public/forms/configuration")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("purpose", equalTo("CONTACT"))
                .body("formToken", notNullValue())
                .body("minimumSubmitAt", notNullValue())
                .body("expiresAt", notNullValue());
    }

    @Test
    void unknownPurposeIsRejectedWithoutCaching() {
        given().queryParam("purpose", "NEWSLETTER")
                .when().get("/api/v1/public/forms/configuration")
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("fieldErrors.purpose", equalTo("UNSUPPORTED"));
    }
}
