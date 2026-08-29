package be.enrosed.shared.audit;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

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
                ActivityLogService.ENTITY_PURCHASE_ORDER, "41", "PO-41", "Inkooporder bijgewerkt",
                List.of(new ActivityChangeDto("status", "Status", "concept", "besteld")));
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
        assertEquals(ActivityCategory.PURCHASING, second.category());
        assertEquals(List.of(new ActivityChangeDto("status", "Status", "concept", "besteld")),
                second.changes());
        assertEquals(second.changes(), filtered.items().getFirst().changes(),
                "the API model must contain details read back from persisted JSON");

        ActivityPageDto catalogue = activities.list(null, "catalogue", null, null, null, 50);
        assertEquals(List.of(third.id()), catalogue.items().stream().map(ActivityDto::id).toList());
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "Emre", roles = "admin")
    void longDisplayValuesAreSafelyShortenedAndRoundTripThroughJson() {
        ActivityLogEntity.deleteAll();
        String longLabel = "Lang label ".repeat(30);
        String longBefore = "voor🙂".repeat(100);
        String longAfter = "na🙂".repeat(150);

        ActivityDto recorded = activities.record(ActivityLogService.ACTION_UPDATED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "72", "PO-72", "Inkooporder bijgewerkt",
                List.of(new ActivityChangeDto("line.72.quantity", longLabel, longBefore, longAfter)));

        ActivityChangeDto shortened = recorded.changes().getFirst();
        assertEquals(100, shortened.label().length());
        assertTrue(shortened.beforeValue().length() <= 300);
        assertTrue(shortened.afterValue().length() <= 300);
        assertTrue(shortened.label().endsWith("…"));
        assertTrue(shortened.beforeValue().endsWith("…"));
        assertTrue(shortened.afterValue().endsWith("…"));

        ActivityDto reloaded = activities.list(null, null,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "72", null, 10).items().getFirst();
        assertEquals(recorded.changes(), reloaded.changes());
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "Emre", roles = "admin")
    void oversizedChangeListKeepsADeterministicPrefixWithinTheDatabaseColumn() {
        ActivityLogEntity.deleteAll();
        List<ActivityChangeDto> requested = IntStream.range(0, 60)
                .mapToObj(index -> new ActivityChangeDto(
                        "field." + index,
                        ("Wijziging " + index + " ").repeat(15),
                        ("voor-" + index + "🙂").repeat(70),
                        ("na-" + index + "🙂").repeat(80)))
                .toList();

        ActivityDto recorded = activities.record(ActivityLogService.ACTION_UPDATED,
                "PRODUCT", "88", "SKU-88", "Product bijgewerkt", requested);

        ActivityLogEntity row = ActivityLogEntity.findById(recorded.id());
        assertTrue(row.changesJson.length() <= 16_000);
        assertTrue(recorded.changes().size() <= 40);
        assertEquals("field.0", recorded.changes().getFirst().field());
        assertEquals("activity.detailsTruncated", recorded.changes().getLast().field());
        assertTrue(recorded.changes().stream().noneMatch(change -> "field.40".equals(change.field())));

        ActivityDto reloaded = activities.list(null, null, "PRODUCT", "88", null, 100)
                .items().getFirst();
        assertEquals(recorded.changes(), reloaded.changes());
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

        given()
                .auth().preemptive().basic("emre", SHARED_TEST_PASSWORD)
                .queryParam("category", "does-not-exist")
                .when().get("/api/activity")
                .then().statusCode(400);
    }
}
