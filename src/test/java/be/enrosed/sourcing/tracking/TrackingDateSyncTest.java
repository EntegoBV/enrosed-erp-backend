package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.domain.PurchaseOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;
import static org.junit.jupiter.api.Assertions.*;

class TrackingDateSyncTest {
    static final Instant NOW = Instant.parse("2026-10-26T16:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    static PurchaseOrder order(Map<String, Object> changes) {
        var values = new HashMap<String, Object>(Map.of("id", 38, "number", "PO-38", "status", "ONDERWEG",
                "trackingReference", "BL: EGLV143650155115 | EGSU0162841", "orderDate", "2026-10-01",
                "shippedOn", "2026-10-05", "expectedArrival", "2026-11-10", "departurePort", "Ningbo", "destinationPort", "Rotterdam"));
        values.putAll(changes); return JSON.convertValue(values, PurchaseOrder.class);
    }
    static DateEvidence evidence(String departure, String eta) {
        return new DateEvidence("CNNGB", "NINGBO, CHINA (CN)", "NLRTM", "ROTTERDAM (NL)", departure, eta,
                "9975791", "1419-004W", "2026-10-05", "EGSU0162841", "EGLV143650155115");
    }
    static Observation observation(DateEvidence dates, Instant checked) {
        return new Observation("CARRIER_SCHEDULE", "Evergreen", "EVER ALLY", dates.expectedArrivalDate(), null,
                null, List.of(), List.of(), checked, Instant.parse("2026-10-05T12:00:00Z"), dates);
    }
    @Test void freshExactCarrierEtaUpdatesArrivalButLoadedOnboardNeverInventsDeparture() {
        var plan = TrackingDateSync.shipmentPlan(order(Map.of()), observation(evidence(null, "2026-11-12"), NOW), NOW);
        assertEquals(LocalDate.of(2026, 11, 12), plan.expectedArrival()); assertNull(plan.shippedOn()); assertNull(plan.error());
    }
    @Test void actualOriginDepartureUsesSourceLocalDayAndNeverTurnsArrivalIntoReceipt() {
        var plan = TrackingDateSync.shipmentPlan(order(Map.of()), observation(evidence("2026-10-06T00:20:00+08:00", "2026-11-12"), NOW), NOW);
        assertEquals(LocalDate.of(2026, 10, 6), plan.shippedOn()); assertEquals(LocalDate.of(2026, 11, 12), plan.expectedArrival());
    }
    @Test void staleOrFutureSourceCheckPreservesManualDates() {
        for (Instant checked : List.of(NOW.minusSeconds(86401), NOW.plusSeconds(301))) {
            var plan = TrackingDateSync.shipmentPlan(order(Map.of()), observation(evidence(null, "2026-11-12"), checked), NOW);
            assertNull(plan.expectedArrival()); assertNull(plan.shippedOn()); assertNotNull(plan.error());
        }
    }
    @Test void wrongContainerDestinationOrVoyageDoesNotApplyPlausibleEta() {
        for (var changes : List.of(Map.<String, Object>of("trackingReference", "MSCU1234567"),
                Map.<String, Object>of("destinationPort", "Antwerp"), Map.<String, Object>of("shippedOn", "2026-09-01"))) {
            var plan = TrackingDateSync.shipmentPlan(order(changes), observation(evidence(null, "2026-11-12"), NOW), NOW);
            assertNull(plan.expectedArrival()); assertNotNull(plan.error());
        }
    }
    @Test void implausibleEtaKeepsArrivalButCanStillAcceptProvenOriginDeparture() {
        var plan = TrackingDateSync.shipmentPlan(order(Map.of()), observation(evidence("2026-10-05T10:00:00+08:00", "2026-10-01"), NOW), NOW);
        assertNull(plan.expectedArrival()); assertEquals(LocalDate.of(2026, 10, 5), plan.shippedOn()); assertNotNull(plan.error());
    }
    @Test void vesselTransitDepartureIsNotOriginDepartureAndPortTimezoneIsRespected() {
        var order = order(Map.of()); var link = new VesselLinkStore.Link(VesselLinkStore.binding(order.trackingReference()), "9975791", NOW);
        var singapore = new Event("transit", "Schip vertrokken", "Pulau Bukom", "2026-10-15T12:46:08Z", true, "Voydar", null, "DEPARTURE", "SGPUB");
        var ningbo = new Event("origin", "Schip vertrokken", "Ningbo", "2026-10-05T17:00:00Z", true, "Voydar", null, "DEPARTURE", "CNNGB");
        assertNull(TrackingDateSync.vesselDeparture(order, link, vessel(List.of(singapore)), NOW, NOW));
        assertEquals(LocalDate.of(2026, 10, 6), TrackingDateSync.vesselDeparture(order, link, vessel(List.of(singapore, ningbo)), NOW, NOW));
    }
    @Test void unknownVoyageCannotReuseHistoricalPortCallOrAmbiguousDepartures() {
        var changes = new HashMap<String, Object>(); changes.put("shippedOn", null);
        var order = order(changes); var link = new VesselLinkStore.Link(VesselLinkStore.binding(order.trackingReference()), "9975791", NOW);
        var old = new Event("old", "Schip vertrokken", "Ningbo", "2026-10-05T10:00:00Z", true, "Voydar", null, "DEPARTURE", "CNNGB");
        assertNull(TrackingDateSync.vesselDeparture(order, link, vessel(List.of(old)), NOW, NOW));
        var anchored = order(Map.of());
        var second = new Event("again", "Schip vertrokken", "Ningbo", "2026-10-06T10:00:00Z", true, "Voydar", null, "DEPARTURE", "CNNGB");
        assertNull(TrackingDateSync.vesselDeparture(anchored, link, vessel(List.of(old, second)), NOW, NOW));
    }
    private static Observation vessel(List<Event> events) {
        return new Observation("VESSEL_TRACKING", null, "EVER ALLY", null, null, null, List.of(), events, null, null);
    }
}
