package be.enrosed.finance.adapter.in.rest;

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

/**
 * The list "Aan wie" as the screens use it: a cost teaches it a name,
 * renaming the party reaches the cost, deleting it leaves the cost alone,
 * and the cost JSON says whether it was entered incl. or excl.
 */
@QuarkusTest
@TestSecurity(user = "staff", roles = AdminIdentityProvider.ADMIN_ROLE)
class CostPartyResourceTest {

    private static final String PARTIES = "/api/cost-parties";
    private static final String COSTS = "/api/costs";

    @Test
    void aCostTeachesTheListAndRenamingThePartyRenamesTheCostButDeletingItDoesNot() {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        String name = "Accountant " + stamp;
        long costId = json().body(Map.of("date", "2026-10-12", "category", "boekhouder", "description", "Jaarrekening " + stamp,
                        "party", name, "amountExclEur", 82.64, "amountInclEur", 100, "vatPct", 21))
                .post(COSTS).then().statusCode(201)
                .body("party", equalTo(name))
                .body("amountExclEur", equalTo(82.64f))
                .body("amountInclEur", equalTo(100.0f))
                .extract().jsonPath().getLong("id");
        try {
            String learned = "find { it.name == '" + name + "' }";
            long partyId = json().get(PARTIES).then().statusCode(200)
                    .body(learned + ".category", equalTo("BOEKHOUDER"))
                    .body(learned + ".vatPct", equalTo(21.0f))
                    .body(learned + ".salesChannel", nullValue())
                    .body(learned + ".iban", nullValue())
                    .extract().jsonPath().getLong(learned + ".id");
            json().get(PARTIES + "/" + partyId).then().statusCode(200).body("name", equalTo(name));
            json().body(Map.of("name", " accountant  " + stamp)).post(PARTIES).then().statusCode(409);

            String renamed = "Accountant & Co " + stamp;
            json().body(Map.of("name", renamed, "category", "BOEKHOUDER", "vatPct", 21, "iban", "be68 5390 0754 7034",
                            "notes", "Jaarrekening"))
                    .put(PARTIES + "/" + partyId).then().statusCode(200)
                    .body("id", equalTo((int) partyId))
                    .body("name", equalTo(renamed))
                    .body("iban", equalTo("BE68539007547034"))
                    .body("notes", equalTo("Jaarrekening"));
            json().get(COSTS + "/" + costId).then().statusCode(200).body("party", equalTo(renamed));
            json().body(Map.of("name", renamed, "iban", "1234")).put(PARTIES + "/" + partyId).then().statusCode(409);

            json().delete(PARTIES + "/" + partyId).then().statusCode(204);
            json().get(PARTIES + "/" + partyId).then().statusCode(404);
            json().get(COSTS + "/" + costId).then().statusCode(200).body("party", equalTo(renamed));
        } finally {
            json().delete(COSTS + "/" + costId);
        }
    }

    @Test
    void aCostEnteredExclusiveComesBackWithoutAnInclusiveTotalAndAWrongTotalIsRefused() {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        long costId = json().body(Map.of("date", "2026-10-12", "category", "HUUR", "description", "Magazijn " + stamp,
                        "amountExclEur", 82.64, "vatPct", 21))
                .post(COSTS).then().statusCode(201)
                .body("amountExclEur", equalTo(82.64f))
                .body("amountInclEur", nullValue())
                .extract().jsonPath().getLong("id");
        json().delete(COSTS + "/" + costId).then().statusCode(204);

        json().body(Map.of("date", "2026-10-12", "category", "HUUR", "description", "Magazijn " + stamp,
                        "amountExclEur", 100, "amountInclEur", 90, "vatPct", 21))
                .post(COSTS).then().statusCode(409)
                .body("message", equalTo("Het bedrag incl. btw kan niet kleiner zijn dan excl."));
    }

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }
}
