package be.enrosed.prospect;

import be.enrosed.shared.security.AdminSessionTokenService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProspectResourceTest {
    private static final String ROOT = "/api/prospects";
    @Inject AdminSessionTokenService sessionTokens;
    private String adminToken;

    @BeforeEach
    void clearOnlyCrmTestData() {
        // Exercise the normal browser-session authentication path without hashing a password on every CRM call.
        adminToken = sessionTokens.issue("emre").token();
        QuarkusTransaction.requiringNew().run(() -> {
            ProspectActivityEntity.deleteAll();
            ProspectEntity.deleteAll();
        });
    }

    @Test
    void everyRouteRequiresAuthentication() {
        for (String suffix : List.of("", "/email-summary", "/1")) {
            given().get(ROOT + suffix).then().statusCode(401);
        }
        given().contentType("application/json").body("{}").post(ROOT).then().statusCode(401);
        given().contentType("application/json").body("{}").put(ROOT + "/1").then().statusCode(401);
        for (String suffix : List.of("/1/activities", "/1/email-reservations")) {
            given().contentType("application/json").body("{}").post(ROOT + suffix).then().statusCode(401);
        }
    }

    @Test
    @TestSecurity(user = "viewer", roles = "viewer")
    void nonAdministratorsCannotReadOrMutatePrivateContactData() {
        for (String suffix : List.of("", "/email-summary", "/1")) given().get(ROOT + suffix).then().statusCode(403);
        given().contentType("application/json").body("{}").post(ROOT).then().statusCode(403);
        given().contentType("application/json").body("{}").put(ROOT + "/1").then().statusCode(403);
        for (String suffix : List.of("/1/activities", "/1/email-reservations")) {
            given().contentType("application/json").body("{}").post(ROOT + suffix).then().statusCode(403);
        }
    }

    @Test
    void persistsNormalizesFiltersAndRejectsDuplicateContacts() {
        Map<String, Object> input = prospect("Trade %_ Flowers", " BUYER@EXAMPLE.COM ", "Trade-Group");
        input.put("countryCode", "gb");
        input.put("instagramHandle", "https://www.instagram.com/Trade_Flowers/");
        input.put("status", "QUALIFIED");
        long id = admin().body(input).post(ROOT).then().statusCode(201)
                .body("email", equalTo("buyer@example.com"))
                .body("instagramHandle", equalTo("trade_flowers"))
                .body("groupKey", equalTo("trade-group"))
                .body("countryCode", equalTo("GB"))
                .extract().jsonPath().getLong("id");
        create("Other Flowers", "other@example.com", null);
        admin().queryParam("search", "%_").queryParam("countryCode", "GB").queryParam("status", "QUALIFIED")
                .get(ROOT).then().statusCode(200).body("total", equalTo(1)).body("items[0].id", equalTo((int) id));
        admin().queryParam("search", "BUYER@").get(ROOT).then().statusCode(200).body("total", equalTo(1));
        admin().queryParam("size", 1).queryParam("page", 1).get(ROOT).then().statusCode(200)
                .body("total", equalTo(2)).body("items.size()", equalTo(1));
        admin().body(prospect("Duplicate email", "buyer@example.com", null)).post(ROOT).then().statusCode(409);
        Map<String, Object> duplicateInstagram = prospect("Duplicate Instagram", "third@example.com", null);
        duplicateInstagram.put("instagramHandle", "@TRADE_FLOWERS");
        admin().body(duplicateInstagram).post(ROOT).then().statusCode(409);
        Map<String, Object> updated = prospect("Renamed Trade", "buyer@example.com", "Trade-Group");
        updated.put("notes", "Confirmed business contact");
        admin().body(updated).put(ROOT + "/" + id).then().statusCode(200)
                .body("status", equalTo("QUALIFIED")).body("notes", equalTo("Confirmed business contact"));
        updated.put("email", "other@example.com");
        admin().body(updated).put(ROOT + "/" + id).then().statusCode(409);
        admin().get(ROOT + "/" + id).then().statusCode(200).body("prospect.email", equalTo("buyer@example.com"))
                .body("activities.size()", equalTo(0));
    }

    @Test
    void invalidInputDoesNotCreatePartialRecords() {
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                "businessName", " ", "countryCode", "ZZ", "email", "missing-at", "website", "javascript:alert(1)",
                "instagramHandle", "https://instagram.com.evil.example/name", "language", "english").entrySet()) {
            Map<String, Object> input = prospect("Invalid", "valid@example.com", null);
            input.put(invalid.getKey(), invalid.getValue());
            admin().body(input).post(ROOT).then().statusCode(400);
        }
        admin().queryParam("size", 201).get(ROOT).then().statusCode(400);
        admin().queryParam("page", -1).get(ROOT).then().statusCode(400);
        admin().queryParam("date", "wrong").get(ROOT + "/email-summary").then().statusCode(400);
        admin().get(ROOT).then().statusCode(200).body("total", equalTo(0));
        admin().get(ROOT + "/999999").then().statusCode(404);
    }

    @Test
    void externalIdRetriesAreIdempotentAndConflictingPayloadsAreRejected() {
        long id = create("Example Trade", "example@example.com", null);
        Map<String, Object> input = activity("INSTAGRAM", "INTRODUCTION", "SENT", "instagram-message-1");
        long activityId = admin().body(input).post(ROOT + "/" + id + "/activities").then().statusCode(200)
                .body("createdBy", equalTo("emre")).extract().jsonPath().getLong("id");
        admin().body(input).post(ROOT + "/" + id + "/activities").then().statusCode(200)
                .body("id", equalTo((int) activityId));
        input.put("body", "Changed message");
        admin().body(input).post(ROOT + "/" + id + "/activities").then().statusCode(409);
        long other = create("Other Trade", "other@example.com", null);
        admin().body(input).post(ROOT + "/" + other + "/activities").then().statusCode(409);
        admin().get(ROOT + "/" + id).then().statusCode(200).body("activities.size()", equalTo(1))
                .body("prospect.status", equalTo("CONTACTED")).body("prospect.lastContactAt", notNullValue());
    }

    @Test
    void instagramLikeDoesNotPretendADirectContactWasMade() {
        long id = create("Like Only", "like@example.com", null);
        admin().body(activity("INSTAGRAM", "LIKE", "COMPLETED", "like-1"))
                .post(ROOT + "/" + id + "/activities").then().statusCode(200);
        admin().get(ROOT + "/" + id).then().statusCode(200).body("prospect.status", equalTo("NEW"))
                .body("prospect.lastContactAt", nullValue()).body("prospect.lastActivityAt", notNullValue());
    }

    @Test
    void emailSummaryUsesBrusselsLocalMidnightsIncludingDst() {
        long id = create("Historic Trade", "historic@example.com", null);
        List<String> times = List.of("2025-10-25T21:59:59Z", "2025-10-25T22:00:00Z",
                "2025-10-26T22:59:59Z", "2025-10-26T23:00:00Z");
        for (int index = 0; index < times.size(); index++) {
            Map<String, Object> input = activity("EMAIL", "INTRODUCTION", "SENT", "historic-" + index);
            input.put("occurredAt", times.get(index));
            admin().body(input).post(ROOT + "/" + id + "/activities").then().statusCode(200);
        }
        admin().queryParam("date", "2025-10-26").get(ROOT + "/email-summary").then().statusCode(200)
                .body("timezone", equalTo("Europe/Brussels")).body("sent", equalTo(2))
                .body("reserved", equalTo(0)).body("remaining", equalTo(8)).body("activities.size()", equalTo(2));
    }

    @Test
    void reservationsEnforceDailyCapacityReleaseAndCompletionWithoutSending() {
        List<Long> ids = IntStream.range(0, 11).mapToObj(i -> create("Capacity " + i, "capacity" + i + "@example.com", null)).toList();
        for (int i = 0; i < 10; i++) reserve(ids.get(i), "capacity-" + i).then().statusCode(200).body("status", equalTo("RESERVED"));
        reserve(ids.getFirst(), "capacity-0").then().statusCode(200);
        admin().get(ROOT + "/email-summary").then().statusCode(200).body("sent", equalTo(0))
                .body("limit", equalTo(10)).body("reserved", equalTo(10)).body("remaining", equalTo(0));
        reserve(ids.get(10), "capacity-10").then().statusCode(409);
        finish(ids.getFirst(), "capacity-0", "CANCELLED").then().statusCode(200);
        reserve(ids.get(10), "capacity-10").then().statusCode(200);
        finish(ids.get(1), "capacity-1", "SENT").then().statusCode(200);
        finish(ids.get(1), "capacity-1", "SENT").then().statusCode(200);
        reserve(ids.get(1), "capacity-1").then().statusCode(409);
        admin().get(ROOT + "/email-summary").then().statusCode(200).body("sent", equalTo(1))
                .body("reserved", equalTo(9)).body("remaining", equalTo(0));
        Map<String, Object> directReserved = activity("EMAIL", "OUTREACH", "RESERVED", "bypass");
        admin().body(directReserved).post(ROOT + "/" + ids.getFirst() + "/activities").then().statusCode(400);
    }

    @Test
    void completedEmailSnapshotsPreventDuplicateContactAfterProspectEdits() {
        long first = create("First branch", "first@example.com", "same-group");
        long second = create("Second branch", "second@example.com", "same-group");
        reserve(first, "first-send").then().statusCode(200);
        reserve(second, "second-send").then().statusCode(409);
        finish(first, "first-send", "FAILED").then().statusCode(200);
        reserve(second, "second-send").then().statusCode(200);
        finish(second, "second-send", "SENT").then().statusCode(200)
                .body("recipientEmail", equalTo("second@example.com")).body("groupKey", equalTo("same-group"));
        admin().body(prospect("Second changed", "changed@example.com", "changed-group"))
                .put(ROOT + "/" + second).then().statusCode(200);
        long oldEmail = create("Reused contact", "second@example.com", "different-group");
        reserve(oldEmail, "would-repeat-address").then().statusCode(409);
        reserve(first, "would-repeat-group").then().statusCode(409);
    }

    @Test
    void optOutAppliesToGroupButHistoricalFactsCanStillBeRecorded() {
        Map<String, Object> excluded = prospect("Excluded", "excluded@example.com", "excluded-group");
        excluded.put("status", "DO_NOT_CONTACT");
        long id = admin().body(excluded).post(ROOT).then().statusCode(201).extract().jsonPath().getLong("id");
        long branch = create("Excluded branch", "branch@example.com", "excluded-group");
        reserve(id, "excluded-1").then().statusCode(409);
        reserve(branch, "excluded-2").then().statusCode(409);
        Map<String, Object> historical = activity("EMAIL", "INTRODUCTION", "SENT", "already-sent");
        historical.put("occurredAt", "2020-01-01T12:00:00Z");
        admin().body(historical).post(ROOT + "/" + id + "/activities").then().statusCode(200);
        admin().get(ROOT + "/" + id).then().statusCode(200).body("prospect.status", equalTo("DO_NOT_CONTACT"));
    }

    @Test
    void aReservationCannotBeReusedForAnEditedRecipient() {
        long id = create("Original recipient", "original@example.com", "original-group");
        reserve(id, "fixed-recipient").then().statusCode(200).body("recipientEmail", equalTo("original@example.com"));
        admin().body(prospect("Updated recipient", "replacement@example.com", "replacement-group"))
                .put(ROOT + "/" + id).then().statusCode(200);
        reserve(id, "fixed-recipient").then().statusCode(409);
        finish(id, "fixed-recipient", "CANCELLED").then().statusCode(200)
                .body("recipientEmail", equalTo("original@example.com")).body("groupKey", equalTo("original-group"));
        reserve(id, "new-recipient").then().statusCode(200).body("recipientEmail", equalTo("replacement@example.com"));
    }

    @Test
    void concurrentReservationsCannotExceedTen() throws Exception {
        List<Long> ids = IntStream.range(0, 13).mapToObj(i -> create("Parallel " + i, "parallel" + i + "@example.com", null)).toList();
        List<Integer> statuses = parallel(ids.size(), index -> reserve(ids.get(index), "parallel-" + index).statusCode());
        assertEquals(10, statuses.stream().filter(code -> code == 200).count(), statuses.toString());
        assertEquals(3, statuses.stream().filter(code -> code == 409).count(), statuses.toString());
        admin().get(ROOT + "/email-summary").then().statusCode(200).body("reserved", equalTo(10)).body("remaining", equalTo(0));
    }

    @Test
    void futureReservationsUseTheSendDayAndScheduledTransitionsPreserveIt() {
        LocalDate tomorrow = LocalDate.now(ProspectService.OUTREACH_ZONE).plusDays(1);
        String scheduledFor = tomorrow.atTime(9, 0).atZone(ProspectService.OUTREACH_ZONE).toInstant().toString();
        List<Long> ids = IntStream.range(0, 11).mapToObj(i -> create("Future " + i, "future" + i + "@example.com", null)).toList();
        Map<String, Object> reservation = new HashMap<>(Map.of("subject", "Planned collection", "body", "Reviewed scheduled message",
                "scheduledFor", scheduledFor));
        for (int i = 0; i < 10; i++) {
            reservation.put("externalId", "future-" + i);
            admin().body(reservation).post(ROOT + "/" + ids.get(i) + "/email-reservations").then().statusCode(200)
                    .body("occurredAt", equalTo(scheduledFor));
        }
        for (int i = 0; i < 3; i++) {
            finish(ids.get(i), "future-" + i, "SCHEDULED").then().statusCode(200)
                    .body("status", equalTo("SCHEDULED")).body("occurredAt", equalTo(scheduledFor));
        }
        finish(ids.getFirst(), "future-0", "SCHEDULED").then().statusCode(200).body("occurredAt", equalTo(scheduledFor));
        admin().queryParam("date", tomorrow.toString()).get(ROOT + "/email-summary").then().statusCode(200)
                .body("sent", equalTo(0)).body("reserved", equalTo(7)).body("scheduled", equalTo(3))
                .body("remaining", equalTo(0));
        admin().get(ROOT + "/email-summary").then().statusCode(200).body("remaining", equalTo(10));
        reservation.put("externalId", "future-10");
        admin().body(reservation).post(ROOT + "/" + ids.get(10) + "/email-reservations").then().statusCode(409);
        Map<String, Object> changedTime = new HashMap<>(Map.of("channel", "EMAIL", "type", "OUTREACH", "status", "SCHEDULED",
                "externalId", "future-3", "occurredAt", tomorrow.plusDays(1).atStartOfDay(ProspectService.OUTREACH_ZONE).toInstant().toString()));
        admin().body(changedTime).post(ROOT + "/" + ids.get(3) + "/activities").then().statusCode(409);
        finish(ids.getFirst(), "future-0", "CANCELLED").then().statusCode(200);
        admin().body(reservation).post(ROOT + "/" + ids.get(10) + "/email-reservations").then().statusCode(200);
        // If externally sent early, record the actual event truthfully on today's summary.
        finish(ids.get(1), "future-1", "SENT").then().statusCode(200);
        finish(ids.get(1), "future-1", "SENT").then().statusCode(200);
        admin().get(ROOT + "/email-summary").then().statusCode(200).body("sent", equalTo(1)).body("remaining", equalTo(9));
        admin().queryParam("date", tomorrow.toString()).get(ROOT + "/email-summary").then().statusCode(200)
                .body("scheduled", equalTo(1)).body("remaining", equalTo(1));
        reservation.put("externalId", "past-reservation");
        reservation.put("scheduledFor", Instant.now().minusSeconds(3600).toString());
        admin().body(reservation).post(ROOT + "/" + ids.getFirst() + "/email-reservations").then().statusCode(400);
    }

    @Test
    void concurrentActivityRetriesCreateOneHistoryEntry() throws Exception {
        long id = create("Parallel retry", "parallel-retry@example.com", null);
        Map<String, Object> input = activity("INSTAGRAM", "INTRODUCTION", "SENT", "same-external-id");
        List<Integer> statuses = parallel(6, index -> admin().body(input).post(ROOT + "/" + id + "/activities").statusCode());
        assertTrue(statuses.stream().allMatch(code -> code == 200), statuses.toString());
        admin().get(ROOT + "/" + id).then().statusCode(200).body("activities.size()", equalTo(1));
    }

    private static List<Integer> parallel(int count, java.util.function.IntFunction<Integer> action) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(pool.submit(() -> { start.await(); return action.apply(index); }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            return statuses;
        }
    }

    private RequestSpecification admin() {
        return given().auth().preemptive().basic("emre", adminToken).contentType("application/json");
    }

    private static Map<String, Object> prospect(String name, String email, String group) {
        Map<String, Object> input = new HashMap<>();
        input.put("businessName", name);
        input.put("countryCode", "GB");
        input.put("email", email);
        input.put("groupKey", group);
        input.put("sourceUrl", "https://example.com/wholesale");
        input.put("sourceType", "WEBSITE");
        return input;
    }

    private long create(String name, String email, String group) {
        return admin().body(prospect(name, email, group)).post(ROOT).then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private static Map<String, Object> activity(String channel, String type, String status, String externalId) {
        return new HashMap<>(Map.of("channel", channel, "type", type, "status", status,
                "externalId", externalId, "body", "Verified outreach text"));
    }

    private Response reserve(long id, String key) {
        return admin().body(Map.of("externalId", key, "subject", "Wholesale collection", "body", "Reviewed message"))
                .post(ROOT + "/" + id + "/email-reservations");
    }

    private Response finish(long id, String key, String status) {
        return admin().body(Map.of("channel", "EMAIL", "type", "OUTREACH", "status", status, "externalId", key))
                .post(ROOT + "/" + id + "/activities");
    }
}
