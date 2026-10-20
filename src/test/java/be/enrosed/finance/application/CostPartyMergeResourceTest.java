package be.enrosed.finance.application;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CostPartyMergeResourceTest {
    @Inject CostPartyService service;

    @Test
    void anonymousRequestsCannotReadOrChangeMergeRelationships() {
        given().get("/api/cost-parties/1/aliases").then().statusCode(401);
        given().contentType("application/json").body(Map.of("targetId", 2)).post("/api/cost-parties/1/merge").then().statusCode(401);
        given().contentType("application/json").body("{}").post("/api/cost-parties/1/restore").then().statusCode(401);
    }

    @Test @TestSecurity(user = "visitor", roles = "viewer")
    void staffWithoutAdminRoleCannotMergeOrRestore() {
        given().get("/api/cost-parties/1/aliases").then().statusCode(403);
        given().contentType("application/json").body(Map.of("targetId", 2)).post("/api/cost-parties/1/merge").then().statusCode(403);
        given().contentType("application/json").body("{}").post("/api/cost-parties/1/restore").then().statusCode(403);
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void adminCanMergeRetryInspectAndRestoreWithoutLosingTheOriginalEntry() {
        String suffix = UUID.randomUUID().toString();
        String oldName = "Banknaam " + suffix;
        long source = given().contentType("application/json").body(Map.of("name", oldName, "category", "OVERIG"))
                .post("/api/cost-parties").then().statusCode(201).extract().jsonPath().getLong("id");
        long target = given().contentType("application/json").body(Map.of("name", "Factuurnaam " + suffix, "category", "BOEKHOUDER"))
                .post("/api/cost-parties").then().statusCode(201).extract().jsonPath().getLong("id");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                given().contentType("application/json").body(Map.of("targetId", target)).post("/api/cost-parties/" + source + "/merge")
                        .then().statusCode(200).body("id", equalTo((int) target)).body("category", equalTo("BOEKHOUDER"))
                        .body("aliases", contains(oldName));
            }
            given().get("/api/cost-parties").then().statusCode(200)
                    .body("findAll { it.id == " + source + " }.size()", equalTo(0));
            given().get("/api/cost-parties/" + target + "/aliases").then().statusCode(200)
                    .body("size()", equalTo(1)).body("[0].party.id", equalTo((int) source))
                    .body("[0].party.name", equalTo(oldName)).body("[0].party.category", equalTo("OVERIG"))
                    .body("[0].mergedBy", equalTo("emre"));
            given().delete("/api/cost-parties/" + target).then().statusCode(409);
            given().contentType("application/json").body(Map.of("name", "Edit merged alias"))
                    .put("/api/cost-parties/" + source).then().statusCode(409);
            given().contentType("application/json").body("{}").post("/api/cost-parties/" + source + "/restore")
                    .then().statusCode(200).body("name", equalTo(oldName)).body("category", equalTo("OVERIG"));
            given().get("/api/cost-parties/" + target + "/aliases").then().statusCode(200).body("size()", equalTo(0));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                service.restore(source);
                service.delete(source);
                service.delete(target);
            });
        }
    }
}
