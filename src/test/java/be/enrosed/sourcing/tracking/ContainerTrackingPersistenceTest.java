package be.enrosed.sourcing.tracking;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ContainerTrackingPersistenceTest {
    @Inject TrackingStore store;
    @Inject EntityManager entities;
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test @TestTransaction
    void sharedLeaseAndSavedRemoteIdSurvivePersistenceAndFailure() {
        String lease = store.acquire(NOW);
        assertNotNull(lease);
        assertNull(store.acquire(NOW.plusSeconds(1)));
        store.release("another-request");
        assertNull(store.acquire(NOW.plusSeconds(2)));
        store.release(lease);
        assertNotNull(store.acquire(NOW.plusSeconds(3)));

        Reference reference = reference("MSCU1234567");
        store.registered(reference, 123L);
        var data = new Observation("SAILING", "MSC", "TEST", null, "Op zee",
                new Position(50, 3, "VESSEL", null), List.of(new Point(50, 3)), List.of(), NOW, NOW);
        store.success(reference, data, NOW);
        entities.flush(); entities.clear();
        assertEquals(123L, store.find(reference.key()).shipmentId());
        assertEquals(data, store.find(reference.key()).observation());
        store.failed(reference, "Tijdelijk onbereikbaar", NOW.plusSeconds(21600));
        entities.flush(); entities.clear();
        assertEquals(data, store.find(reference.key()).observation());
        assertEquals(NOW, store.find(reference.key()).updatedAt());
        assertEquals(NOW.plusSeconds(43200), store.find(reference.key()).nextCheckAt());
    }

    @Test void trackingIsStaffOnlyAndUnconfiguredStateDoesNotFetchAnything() {
        given().get("/api/container-tracking").then().statusCode(401);
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .get("/api/container-tracking").then().statusCode(200)
                .body("provider", equalTo("ShipsGo"), "configured", equalTo(false), "intervalHours", equalTo(6));
        given().post("/api/container-tracking/999999/refresh").then().statusCode(401);
    }
}
