package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.domain.PurchaseOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContainerArrivalRemindersTest {
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static PurchaseOrder order(String eta, Map<String, Object> changes) {
        Map<String, Object> values = new HashMap<>(Map.of("id", 38L, "number", "PO-38", "alias", "Testcontainer",
                "status", "ONDERWEG", "trackingReference", "EGSU0162841", "expectedArrival", eta, "destinationPort", "Rotterdam"));
        values.putAll(changes);
        return new ObjectMapper().findAndRegisterModules().convertValue(values, PurchaseOrder.class);
    }

    @Test void thresholdsCoverOnlyTenAndThreeDayWindowsWithoutHistoricalReplay() {
        assertNull(ContainerArrivalReminders.threshold(order("2026-11-06", Map.of()), NOW));
        assertEquals(10, ContainerArrivalReminders.threshold(order("2026-11-05", Map.of()), NOW));
        assertEquals(10, ContainerArrivalReminders.threshold(order("2026-10-30", Map.of()), NOW));
        assertEquals(3, ContainerArrivalReminders.threshold(order("2026-10-29", Map.of()), NOW));
        assertEquals(3, ContainerArrivalReminders.threshold(order("2026-10-26", Map.of()), NOW));
        assertNull(ContainerArrivalReminders.threshold(order("2026-10-25", Map.of()), NOW));
    }

    @Test void eligibilityExcludesConceptReceivedArchivedAndMissingTracking() {
        for (var changes : java.util.List.of(Map.<String,Object>of("status", "CONCEPT"), Map.<String,Object>of("status", "ONTVANGEN"),
                Map.<String,Object>of("archivedAt", NOW.toString()), Map.<String,Object>of("receivedOn", "2026-10-25"),
                Map.<String,Object>of("trackingReference", "  "))) {
            assertNull(ContainerArrivalReminders.preview(order("2026-10-29", changes), NOW));
        }
        assertNotNull(ContainerArrivalReminders.preview(order("2026-10-29", Map.of("status", "BESTELD")), NOW));
    }

    @Test void brusselsCalendarDayAndQuietHoursSurviveWinterSummerOffsets() {
        assertEquals(3, ContainerArrivalReminders.threshold(order("2026-10-30", Map.of()), Instant.parse("2026-10-26T23:05:00Z")));
        assertFalse(ContainerArrivalReminders.wakingHours(Instant.parse("2026-10-26T07:59:59Z")));
        assertTrue(ContainerArrivalReminders.wakingHours(Instant.parse("2026-10-26T08:00:00Z")));
        assertFalse(ContainerArrivalReminders.wakingHours(Instant.parse("2026-10-26T20:00:00Z")));
        assertTrue(ContainerArrivalReminders.wakingHours(Instant.parse("2026-07-26T07:00:00Z")));
        assertFalse(ContainerArrivalReminders.wakingHours(Instant.parse("2026-07-26T06:59:59Z")));
    }

    @Test void identityIgnoresEtaChangesButNotPurchaseVoyageOrThreshold() {
        var current = order("2026-10-29", Map.of());
        String id = ContainerArrivalReminders.id(current, 3);
        assertEquals(id, ContainerArrivalReminders.id(order("2026-10-30", Map.of()), 3));
        assertNotEquals(id, ContainerArrivalReminders.id(current, 10));
        assertNotEquals(id, ContainerArrivalReminders.id(order("2026-10-29", Map.of("id", 40L)), 3));
        assertNotEquals(id, ContainerArrivalReminders.id(order("2026-10-29", Map.of("trackingReference", "new voyage")), 3));
    }

    @Test void previewUsesPlanningWordingAndPurchaseDeepLink() {
        var preview = ContainerArrivalReminders.preview(order("2026-10-26", Map.of()), NOW);
        assertEquals("Container vandaag verwacht", preview.title());
        assertEquals("/purchasing/38?tracking=1", preview.url());
        assertTrue(preview.detail().contains("Rotterdam op 26/10/2026 volgens de huidige planning"));
        assertEquals(3, preview.thresholdDays());
        assertFalse(ContainerArrivalReminders.preview(order("2026-10-26", Map.of("destinationPort", " ")), NOW).detail().contains("null"));
    }

    @Test void presentationEditsAndAdditionalBillDoNotRepeatContainerReminder() {
        String identity = ContainerArrivalReminders.id(order("2026-10-29", Map.of()), 3);
        for (String reference : new String[] { "egsu0162841", "  EGSU0162841  ", "EGSU  0162841", "EGSU-0162841",
                "egsu - 0162841", "EGSU0162841 · B/L EGLV143650155115", "B/L: EGLV143650155115 · EGSU0162841" }) {
            assertEquals(identity, ContainerArrivalReminders.id(order("2026-10-29", Map.of("trackingReference", reference)), 3), reference);
        }
        assertNotEquals(identity, ContainerArrivalReminders.id(order("2026-10-29", Map.of("trackingReference", "EGHU9194893")), 3));
        assertNotEquals(identity, ContainerArrivalReminders.id(order("2026-10-29", Map.of("trackingReference", "EGSU0162842")), 3), "invalid check digit must not alias a valid box");
    }

    @Test void explicitBillBookingAndFallbackNormalizationKeepDifferentShipmentsDistinct() {
        assertEquals(ContainerArrivalReminders.shipmentIdentity("BL:EGLV143650155115"), ContainerArrivalReminders.shipmentIdentity(" b/l: EGLV 143650155115 "));
        assertEquals(ContainerArrivalReminders.shipmentIdentity("BL:EGLV143650155115"), ContainerArrivalReminders.shipmentIdentity("B/L:EGLV143650155115"));
        assertNotEquals(ContainerArrivalReminders.shipmentIdentity("BL:EGLV143650155115"), ContainerArrivalReminders.shipmentIdentity("BL:EGLV143668686040"));
        assertEquals(ContainerArrivalReminders.shipmentIdentity("BOOKING: abc123456"), ContainerArrivalReminders.shipmentIdentity(" booking: ABC 123456 "));
        assertEquals(ContainerArrivalReminders.shipmentIdentity("Internal   reference  12"), ContainerArrivalReminders.shipmentIdentity(" internal reference 12 "));
    }
}
