package be.enrosed.sourcing.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerTrackingTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test void existingReferencesArePreservedWhileOnlyUnambiguousIdentifiersAreRegistered() {
        assertEquals("MSCU1234567", reference("mscu 1234567").number());
        assertEquals(reference("MSCU1234567"), reference("https://carrier.example/track?container=MSCU1234567"));
        assertNull(reference("SZ260703903"));
        assertNull(reference("https://example.org/internal/booking/SZ260703903"));
        assertEquals("SZ260703903", reference("BL: SZ260703903").number());
        assertEquals(reference("BL: SZ260703903"), reference("booking: sz260703903"));
        assertNull(reference("javascript:alert(1)"));
        assertNull(reference("MSCU1234567 and MSCU9999999"));
        assertFalse(reference("BL: MSCU1234567").container());
        assertEquals("https://www.track-trace.com/container", sourceLink("javascript:alert(1)"));
        assertEquals("https://www.track-trace.com/container", sourceLink("https://user:secret@example.com/track"));
    }

    @Test void aReusedContainerOnAnotherPurchaseNeverGetsThePreviousVoyagesSnapshotOrProviderIdentity() {
        var original = ContainerTrackingService.referenceForOrder(reference("MSCU1234567"), 33L);
        assertEquals(original, ContainerTrackingService.referenceForOrder(reference("mscu 1234567"), 33L));
        assertNotEquals(original.key(), ContainerTrackingService.referenceForOrder(reference("MSCU1234567"), 34L).key());
        assertNotEquals(original.key(), ContainerTrackingService.referenceForOrder(reference("MSCU9999999"), 33L).key());
    }

    @Test void currentVesselCoordinatesUseGeoJsonOrderAndNeverInventObservationTime() throws Exception {
        var shipment = json.readTree("""
          {"id":123,"status":"SAILING","carrier":{"name":"MSC"},"checked_at":"2026-10-26 09:00:00",
           "updated_at":"2026-10-26 07:00:00","route":{"port_of_discharge":{"date_of_discharge":"2026-11-01T12:00:00+01:00"}},
           "containers":[{"number":"MSCU1234567","status":"SAILING","movements":[
             {"event":"DEPA","status":"ACT","timestamp":"2026-10-20T12:00:00+08:00","location":{"name":"SHANGHAI"},"vessel":{"name":"OLD VESSEL"}},
             {"event":"ARRV","status":"EST","timestamp":"2026-11-01T12:00:00+01:00","location":{"name":"ROTTERDAM"}}]}]}
          """);
        var geo = json.readTree("""
          {"features":[{"geometry":{"type":"LineString","coordinates":[[0,0],[4.1,51.9]]},
           "properties":{"status":"CURRENT","vessel":{"name":"MSC TEST"},"current":{"coordinates":[3.2,50.1]},
            "events":{"DEPA":{"location":{"name":"SHANGHAI"}},"ARRV":{"location":{"name":"ROTTERDAM"}}}}}]}
          """);
        var observed = ShipsGoClient.parse(shipment, geo, reference("MSCU1234567"));
        assertEquals(new Position(50.1, 3.2, "VESSEL", null), observed.position());
        assertEquals("MSC TEST", observed.vessel());
        assertEquals("SHANGHAI → ROTTERDAM", observed.location());
        assertEquals("2026-11-01T11:00:00Z", observed.eta());
        assertEquals(new Point(0, 0), observed.route().getFirst());
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), observed.providerCheckedAt());
        assertFalse(observed.events().getFirst().actual());
        assertTrue(observed.events().getLast().actual());
    }

    @Test void aFuturePortAndEstimatedMovementNeverBecomeCurrentLocation() throws Exception {
        var shipment = json.readTree("""
          {"status":"SAILING","containers":[{"number":"MSCU1234567","movements":[
           {"event":"DEPA","status":"ACT","timestamp":"2026-10-20T00:00:00Z","location":{"name":"SHANGHAI"}},
           {"event":"ARRV","status":"EST","timestamp":"2026-11-01T00:00:00Z","location":{"name":"ROTTERDAM"}}]}]}
          """);
        var geo = json.readTree("""
          {"features":[{"geometry":{"type":"Point","coordinates":[4.1,51.9]},"properties":{"status":"FUTURE","location":{"name":"ROTTERDAM"}}}]}
          """);
        var observation = ShipsGoClient.parse(shipment, geo, reference("MSCU1234567"));
        assertNull(observation.position());
        assertEquals("SHANGHAI", observation.location());
    }

    @Test void portIsLabelledAsPortAndInvalidOrMismatchedCoordinatesAreRejected() throws Exception {
        var shipment = json.readTree("{\"status\":\"ARRIVED\",\"containers\":[{\"number\":\"MSCU1234567\"}]}");
        var geo = json.readTree("""
          {"features":[{"geometry":{"type":"Point","coordinates":[4.1,51.9]},"properties":{"status":"CURRENT","location":{"name":"ROTTERDAM"}}}]}
          """);
        assertEquals("LAST_EVENT", ShipsGoClient.parse(shipment, geo, reference("MSCU1234567")).position().kind());
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> ShipsGoClient.parse(shipment, geo, reference("MSCU9999999")));
        var invalid = json.readTree("{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[190,99]},\"properties\":{\"status\":\"CURRENT\"}}]}");
        assertNull(ShipsGoClient.parse(shipment, invalid, reference("MSCU1234567")).position());
    }

    @Test void unchangedChecksDoNotReportUpdatesAndFailureRetainsLastGoodLocation() {
        TrackingEntity entity = new TrackingEntity();
        var observed = sample("SAILING", NOW);
        TrackingStore.applySuccess(entity, observed, NOW, json);
        String stored = entity.observationJson;
        assertEquals(NOW, entity.changedAt);
        assertEquals(NOW.plusSeconds(21600), entity.nextCheckAt);
        TrackingStore.applySuccess(entity, sample("SAILING", NOW.plusSeconds(21600)), NOW.plusSeconds(21600), json);
        assertEquals(NOW, entity.changedAt, "A provider's check time is not a cargo change");
        Instant success = entity.updatedAt;
        String latestStored = entity.observationJson;
        TrackingStore.applyFailure(entity, "Provider unavailable", NOW.plusSeconds(43200));
        assertEquals(success, entity.updatedAt);
        assertEquals(latestStored, entity.observationJson);
        assertEquals(NOW, entity.changedAt);
        assertEquals("Provider unavailable", entity.lastError);
        TrackingStore.applySuccess(entity, sample("ARRIVED", NOW.plusSeconds(65000)), NOW.plusSeconds(65000), json);
        assertEquals(NOW.plusSeconds(65000), entity.changedAt);
        assertNull(entity.lastError);
        assertNotEquals(stored, entity.observationJson);
    }

    @Test void sixHourDeadlineSurvivesRestartAndDischargeKeepsChecking() {
        var snapshot = new TrackingStore.Snapshot(1L, sample("DISCHARGED", NOW), NOW, NOW, NOW,
                NOW.plusSeconds(21600), null, false);
        assertFalse(ContainerTrackingService.due(snapshot, NOW.plusSeconds(21599)));
        assertTrue(ContainerTrackingService.due(snapshot, NOW.plusSeconds(21600)));
        assertFalse(terminal("DISCHARGED")); assertFalse(terminal("GATE_OUT")); assertTrue(terminal("EMPTY_RETURN"));
        assertFalse(ContainerTrackingService.due(new TrackingStore.Snapshot(1L, sample("EMPTY_RETURN", NOW),
                NOW, NOW, NOW, null, null, true), NOW.plusSeconds(999999)));
    }

    private Observation sample(String status, Instant providerCheckedAt) {
        return new Observation(status, "MSC", "MSC TEST", null, "Op zee", new Position(50, 3, "VESSEL", null),
                List.of(), List.of(), providerCheckedAt, NOW);
    }
}
