package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.domain.Fair;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** The fairs as the cost form uses them: the two seeded ones, a new one from its label, switched off, deleted when unused. */
@QuarkusTest
@TestSecurity(user = "staff", roles = AdminIdentityProvider.ADMIN_ROLE)
class FairResourceTest {

    private static final String FAIRS = "/api/fairs";
    private static final String COSTS = "/api/costs";

    @Test
    void theTwoKnownFairsAreSeededAndANewOneTakesItsCodeFromItsLabel() {
        json().get(FAIRS).then().statusCode(200)
                .body("find { it.code == 'TICA' }.label", equalTo("TICA"))
                .body("find { it.code == 'TICA' }.active", equalTo(true))
                .body("find { it.code == 'EDE' }.label", equalTo("Ede"))
                .body("find { it.code == 'EDE' }.active", equalTo(true));

        String stamp = UUID.randomUUID().toString().substring(0, 8);
        String label = "Trends & Trade " + stamp;
        String code = Fair.codeOf(label);
        long id = json().body(Map.of("label", label)).post(FAIRS).then().statusCode(201)
                .body("code", equalTo(code))
                .body("label", equalTo(label))
                .body("active", equalTo(true))
                .extract().jsonPath().getLong("id");
        try {
            json().body(Map.of("label", label.toLowerCase())).post(FAIRS).then().statusCode(409)
                    .body("message", equalTo("Deze beurs bestaat al"));
            json().body(Map.of("label", "Webshop " + stamp, "code", "website")).post(FAIRS).then().statusCode(409)
                    .body("message", equalTo("Deze code is een vast verkoopkanaal"));

            json().body(Map.of("label", label + " (voorbij)", "active", false)).put(FAIRS + "/" + id).then().statusCode(200)
                    .body("code", equalTo(code))
                    .body("label", equalTo(label + " (voorbij)"))
                    .body("active", equalTo(false));
            json().get(FAIRS).then().statusCode(200).body("find { it.id == " + id + " }.active", equalTo(false));

            long costId = json().body(Map.of("date", "2026-10-12", "category", "BEURS", "description", "Stand " + stamp,
                            "amountExclEur", 10, "salesChannel", code))
                    .post(COSTS).then().statusCode(201).body("salesChannel", equalTo(code))
                    .extract().jsonPath().getLong("id");
            json().delete(FAIRS + "/" + id).then().statusCode(409)
                    .body("message", equalTo("Deze beurs wordt nog gebruikt; zet ze uit in plaats van ze te verwijderen."));
            json().delete(COSTS + "/" + costId).then().statusCode(204);

            json().delete(FAIRS + "/" + id).then().statusCode(204);
            json().get(FAIRS).then().statusCode(200).body("find { it.id == " + id + " }", nullValue());
            json().delete(FAIRS + "/" + id).then().statusCode(404);
        } finally {
            json().delete(FAIRS + "/" + id);
        }
    }

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }
}
