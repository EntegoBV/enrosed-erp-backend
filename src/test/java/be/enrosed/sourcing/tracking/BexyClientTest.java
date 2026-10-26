package be.enrosed.sourcing.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BexyClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BexyClient client = client();
    private static final String REFERENCE = "EGSU0162841 · B/L EGLV143650155115";
    private BexyClient client() { var value = new BexyClient(); value.json = json; return value; }

    @Test void requiresAnExplicitUnambiguousCarrierBillAndValidContainer() {
        assertEquals("EGLV143650155115", BexyClient.query(REFERENCE).bl());
        assertEquals("EGSU0162841", BexyClient.query(REFERENCE).box());
        assertNotNull(BexyClient.query("BL: EGLV 143650155115"));
        assertEquals("EGSU0162841", BexyClient.query("EGSU 0162841 B/L EGLV143650155115").box());
        assertEquals("EGSU0162841", BexyClient.query("EGSU-0162841 B/L EGLV143650155115").box());
        for (String raw : new String[] { null, "", "EGSU0162841", "BL: 143650155115", "EGLV1436501551159",
                "EGSU0162842 EGLV143650155115", "EGSU0162841 EGHU9194893 EGLV143650155115",
                "EGLV143650155115 EGLV143668686040", "https://example.com/EGLV143650155115/" + "x".repeat(500) })
            assertNull(BexyClient.query(raw), String.valueOf(raw));
    }

    @Test void checksIsoContainerCheckDigits() {
        for (String box : new String[] { "EGSU0162841", "EGHU9194893", "CAIU9141871" }) assertTrue(BexyClient.validBox(box));
        for (String box : new String[] { "EGSU0162842", "EGSA0162841", "00000000000" }) assertFalse(BexyClient.validBox(box));
    }

    @Test void carriesFreshnessAndExactRouteWithoutTurningLoadedOnBoardIntoDeparture() throws Exception {
        var data = client.parse(shipment(), REFERENCE);
        assertEquals(Instant.parse("2026-10-26T15:51:54Z"), data.providerCheckedAt());
        assertEquals("2026-11-12", data.dateEvidence().expectedArrivalDate());
        assertEquals("2026-10-05", data.dateEvidence().voyageStartedOn());
        assertEquals("NINGBO, CHINA (CN)", data.dateEvidence().originName());
        assertEquals("ROTTERDAM (NL)", data.dateEvidence().destinationName());
        assertNull(data.dateEvidence().actualDepartureAt());
        assertNull(data.position());
        assertEquals("Evergreen via bexy", data.events().getFirst().source());
    }

    @Test void wrongBillOrContainerOrMissingAsOfCannotUpdateDates() throws Exception {
        ObjectNode wrongBill = shipment(); wrongBill.put("carrier_bill_of_lading", "EGLV 143668686040");
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(wrongBill, REFERENCE));
        ObjectNode wrongBox = shipment(); ((ObjectNode) wrongBox.path("containers").get(0)).put("container_no", "EGHU9194893");
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(wrongBox, REFERENCE));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(wrongBox, "EGSU 0162841 EGLV143650155115"));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(wrongBox, "EGSU-0162841 EGLV143650155115"));
        ObjectNode noFreshness = shipment(); noFreshness.remove("data_as_of");
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(noFreshness, REFERENCE));
    }

    @Test void inlandOrFallbackOrInvalidEtasCannotBecomePortArrival() throws Exception {
        ObjectNode inland = shipment(); ((ObjectNode) inland.path("transit")).put("scheduled_transit_to", "place_of_delivery");
        assertNull(client.parse(inland, REFERENCE).dateEvidence().expectedArrivalDate());
        ObjectNode fallback = shipment(); fallback.put("estimated_arrival_source", "last_planned_arrival");
        assertNull(client.parse(fallback, REFERENCE).dateEvidence().expectedArrivalDate());
        ObjectNode invalid = shipment(); invalid.put("estimated_arrival", "2026-02-30T00:00:00Z");
        assertNull(client.parse(invalid, REFERENCE).dateEvidence().expectedArrivalDate());
    }

    @Test void severalContainersRequireTheExplicitSelectedContainer() throws Exception {
        ObjectNode multiple = shipment();
        multiple.withArray("containers").add(json.createObjectNode().put("container_no", "EGHU9194893"));
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(multiple, "EGLV143650155115"));
        assertEquals("EGSU0162841", client.parse(multiple, REFERENCE).dateEvidence().containerNumber());
        multiple.withArray("containers").add(multiple.path("containers").get(0).deepCopy());
        assertThrows(ShipsGoClient.TrackingFailure.class, () -> client.parse(multiple, REFERENCE));
    }

    private ObjectNode shipment() throws Exception {
        // Fictitious source envelope with representative field semantics; never inserted into ERP.
        return (ObjectNode) json.readTree("""
            {"carrier_bill_of_lading":"EGLV 143650155115","vessel_voyage":"EXAMPLE SHIP 001W",
             "port_of_loading":{"location":"NINGBO, CHINA (CN)"},
             "port_of_discharge":{"location":"ROTTERDAM (NL)"},
             "estimated_departure":"2026-10-05T00:00:00+00:00",
             "estimated_arrival":"2026-11-12T00:00:00+00:00","estimated_arrival_source":"carrier_eta",
             "data_as_of":"2026-10-26T15:51:54+00:00",
             "transit":{"scheduled_transit_to":"port_of_discharge"},
             "containers":[{"container_no":"EGSU0162841","current_status":"Loaded (FCL) on EXAMPLE SHIP at NINGBO",
             "last_event_date":"2026-10-05T00:00:00+00:00"}],
             "tracking_url":"https://bexy.io/track/example-shipment"}
            """);
    }
}
