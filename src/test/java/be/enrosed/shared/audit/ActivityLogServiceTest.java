package be.enrosed.shared.audit;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActivityLogServiceTest {

    private static final String SHARED_TEST_PASSWORD = "named-auth-test-password";

    @Inject
    ActivityLogService activities;

    @Test
    @TestTransaction
    @TestSecurity(user = "Emre", roles = "admin")
    void successfulActionsAreAppendOnlyFilteredAndCursorPaged() {
        ActivityLogEntity.deleteAll();

        ActivityDto first = activities.record(ActivityLogService.ACTION_CREATED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "41", "PO-41", "Inkooporder aangemaakt");
        ActivityDto second = activities.record(ActivityLogService.ACTION_UPDATED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "41", "PO-41", "Inkooporder bijgewerkt");
        ActivityDto third = activities.record(ActivityLogService.ACTION_CREATED,
                "PRODUCT", "7", "ROSE-7", "Product aangemaakt");

        ActivityPageDto firstPage = activities.list(null, null, null, null, 2);
        assertEquals(List.of(third.id(), second.id()),
                firstPage.items().stream().map(ActivityDto::id).toList());
        assertEquals(second.id(), firstPage.nextBefore());

        ActivityPageDto secondPage = activities.list(null, null, null, firstPage.nextBefore(), 2);
        assertEquals(List.of(first.id()), secondPage.items().stream().map(ActivityDto::id).toList());
        assertNull(secondPage.nextBefore());

        ActivityPageDto filtered = activities.list("EMRE", "purchase_order", "41", null, 1000);
        assertEquals(List.of(second.id(), first.id()),
                filtered.items().stream().map(ActivityDto::id).toList());
        assertTrue(filtered.items().stream().allMatch(item -> item.actor().username().equals("emre")));
        assertTrue(filtered.items().stream().allMatch(item -> item.actor().displayName().equals("Emre")));
    }

    @Test
    void activityEndpointIsAuthenticatedAndCredentialFree() {
        given()
                .auth().preemptive().basic("emre", SHARED_TEST_PASSWORD)
                .queryParam("limit", 1)
                .when().get("/api/activity")
                .then()
                .statusCode(200)
                .body("$", hasKey("items"))
                .body("$", hasKey("nextBefore"))
                .body("$", not(hasKey("password")))
                .body("$", not(hasKey("passwordHash")));

        given().when().get("/api/activity").then().statusCode(401);
    }
}
