package be.enrosed.sourcing.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;
import static org.junit.jupiter.api.Assertions.*;

class VoydarClientTest {
    private static final String IMO = "9811000";
    private static final Instant TIME = Instant.parse("2026-10-25T20:11:18.693Z");

    @Test void validatesImoChecksumAndRejectsAnythingThatCouldChangeTheRequestDestination() {
        assertTrue(VesselLinkStore.validImo(IMO)); assertTrue(VesselLinkStore.validImo("9722716"));
        for (String invalid : List.of("9811001", "0000000", "IMO 9811000", "9811000/path", "9811000?x=1", " 9811000", "９８１１０００"))
            assertFalse(VesselLinkStore.validImo(invalid), invalid);
    }

    @Test void readsOnlyPortCallsWithSourceLinksAndStableOccurrenceTimeNotFeedGenerationTime() {
        var parsed = parse(feed("2099-01-01T00:00:00Z", arrival() + entry("nameChange", "2026-10-26T00:00:00Z", "Name changed", "ignored")
                + entry("reportedDeparture", "2020-01-01T00:00:00Z", "Reported departure from Hong Kong", "Source: Hong Kong VTMS")));
        assertEquals("TEST SHIP", parsed.vessel()); assertEquals(2, parsed.events().size());
        assertEquals(TIME.toString(), parsed.events().getFirst().occurredAt());
        assertEquals(TIME, parsed.providerUpdatedAt());
        assertTrue(parsed.events().getFirst().source().contains("Voydar port-call detector"));
        assertEquals("https://www.voydar.com/port/sgpub-pulau-bukom", parsed.events().getFirst().sourceUrl());
        assertEquals("Pulau Bukom", parsed.location());
        assertNull(parsed.position()); assertNull(parsed.eta()); assertNull(parsed.carrier()); assertTrue(parsed.route().isEmpty());
        assertEquals("VESSEL_TRACKING", parsed.status());
        var voyage = VoydarClient.since(parsed, TIME.minusSeconds(1));
        assertEquals(1, voyage.events().size());
        assertTrue(VoydarClient.since(parsed, TIME.plusSeconds(1)).events().isEmpty());
        assertNull(VoydarClient.since(parsed, TIME.plusSeconds(1)).location());
    }

    @Test void departureLeftTitleIsRecognizedAndUnknownEntryTypesAreIgnored() {
        var parsed = parse(feed(TIME.toString(), entry("departure", TIME.toString(), "Left Santa Cruz de Tenerife", "Detected from AIS positions by Voydar.")));
        assertEquals("Santa Cruz de Tenerife", parsed.location());
        assertEquals("Schip vertrokken", parsed.events().getFirst().status());
        assertTrue(parse(feed(TIME.toString(), entry("unknownType", TIME.toString(), "Arrived at Somewhere", "Unknown"))).events().isEmpty());
    }

    @Test void rejectsXxeWrongVesselAndNonAtomWithoutResolvingExternalContent() {
        String xxe = "<!DOCTYPE feed [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + feed(TIME.toString(), "").replace("TEST SHIP", "&xxe;");
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> parse(xxe));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> parse("<html>Login required</html>"));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> VoydarClient.parse(feed(TIME.toString(), arrival()).getBytes(StandardCharsets.UTF_8), "9722716"));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> VoydarClient.parse(new byte[1_000_001], IMO));
    }

    @Test void emptyAnd304AreSuccessfulAndRetainKnownEventsWhileVolatileFeedTimestampIsNotAnUpdate() {
        var previous = parse(feed(TIME.toString(), arrival()));
        var empty = parse(feed("2099-01-01T00:00:00Z", ""));
        assertTrue(empty.events().isEmpty()); assertNull(empty.providerUpdatedAt());
        var merged = VoydarClient.merge(previous, empty);
        assertEquals(previous, merged);
        var snapshot = new TrackingStore.Snapshot(null, previous, TIME, TIME, TIME, TIME.plusSeconds(21600), null, false, "version", null);
        assertEquals(previous, VoydarClient.notModified(IMO, snapshot).observation());
        assertEquals("version", VoydarClient.notModified(IMO, snapshot).etag());
        assertTrue(VoydarClient.notModified(IMO, null).observation().events().isEmpty());
        var entity = new TrackingEntity();
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        TrackingStore.applySuccess(entity, previous, TIME, json);
        TrackingStore.applySuccess(entity, parse(feed("2099-01-01T00:00:00Z", arrival())), TIME.plusSeconds(21600), json);
        assertEquals(TIME, entity.changedAt);
    }

    @Test void sourceAndOrderSnapshotIdentitiesCannotMixWithShipsGoOrAnotherReference() {
        var order = new ObjectMapper().findAndRegisterModules().convertValue(java.util.Map.of("id", 33, "trackingReference", "SZ260703903"), be.enrosed.sourcing.domain.PurchaseOrder.class);
        var link = new VesselLinkStore.Link(VesselLinkStore.binding(order.trackingReference()), IMO, TIME);
        assertNotNull(VesselLinkStore.matching(order, link));
        assertNull(VesselLinkStore.matching(order, new VesselLinkStore.Link("different", IMO, TIME)));
        assertEquals(TIME, ContainerTrackingService.cutoff(order, link));
        var rowKey = ContainerTrackingService.trackingReference(order, link).key();
        assertNotEquals(ContainerTrackingService.vesselFeedReference(IMO).key(), rowKey);
        assertNotEquals(ContainerTrackingService.trackingReference(order, new VesselLinkStore.Link(link.referenceHash(), "9722716", TIME)).key(), rowKey);
    }

    private Observation parse(String xml) { return VoydarClient.parse(xml.getBytes(StandardCharsets.UTF_8), IMO); }
    private String arrival() { return entry("arrival", TIME.toString(), "Arrived at Pulau Bukom", "Detected from AIS positions by the Voydar port-call detector (v2)."); }
    private String feed(String updated, String entries) {
        return "<feed xmlns=\"http://www.w3.org/2005/Atom\"><id>tag:seadar.io,2026:/feeds/vessel/" + IMO
                + "</id><title>TEST SHIP: port calls, identity and sanctions</title><updated>" + updated + "</updated>" + entries + "</feed>";
    }
    private String entry(String type, String time, String title, String source) {
        return "<entry><id>tag:seadar.io,2026:/feeds/vessel/" + IMO + "/" + type + "/" + time + "/event</id><title>TEST SHIP: "
                + title + "</title><updated>2099-01-01T00:00:00Z</updated><link rel=\"alternate\" href=\"https://www.voydar.com/port/sgpub-pulau-bukom\"/>"
                + "<content>" + source + "</content></entry>";
    }
}
