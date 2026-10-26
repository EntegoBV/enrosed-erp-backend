package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContainerTrackingServiceTest {
    private ContainerTrackingService service;
    private SourcingRepositories.PurchaseOrders orders;
    private TrackingStore store;
    private ShipsGoClient provider;
    private VesselLinkStore links;
    private VoydarClient voydar;
    private BexyClient bexy;
    private TrackingDateSync dateSync;

    @BeforeEach void prepare() {
        service = new ContainerTrackingService();
        service.orders = orders = mock(SourcingRepositories.PurchaseOrders.class);
        service.store = store = mock(TrackingStore.class);
        service.provider = provider = mock(ShipsGoClient.class);
        service.links = links = mock(VesselLinkStore.class);
        service.voydar = voydar = mock(VoydarClient.class);
        service.bexy = bexy = mock(BexyClient.class);
        service.dateSync = dateSync = mock(TrackingDateSync.class);
        when(links.all()).thenReturn(Map.of());
        when(store.all()).thenReturn(Map.of());
        when(orders.findById(anyLong())).thenAnswer(call -> orders.findAll().stream()
                .filter(order -> order.id().equals(call.getArgument(0))).findFirst());
    }

    @Test void feedShowsEveryTrackedLivePurchaseIncludingUnsupportedButNeverCallsProvider() {
        var unknown = order(1, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        var received = order(2, "MSCU1234567", PurchaseOrderStatus.ONTVANGEN);
        var archived = order(3, "MSCU1234567", PurchaseOrderStatus.ONDERWEG).withArchivedAt(Instant.now());
        var empty = order(4, " ", PurchaseOrderStatus.ONDERWEG);
        when(orders.findAll()).thenReturn(List.of(unknown, received, archived, empty));
        Feed feed = service.feed();
        assertEquals(2, feed.containers().size());
        assertTrue(feed.configured()); assertFalse(feed.shipsGoConfigured());
        assertEquals("UNCONFIGURED", feed.containers().getFirst().status());
        assertEquals("UNSUPPORTED", feed.containers().getLast().status());
        assertNull(feed.containers().getLast().position());
        verify(provider, never()).create(any()); verify(provider, never()).fetch(anyLong(), any());
    }

    @Test void accountlessSchedulerUsesPublicFeedWithoutShipsGoAndDoesNotRepollSameVesselWithinFifteenMinutes() {
        var active = order(1, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        var received = order(2, "another-ref", PurchaseOrderStatus.ONTVANGEN);
        var linkedAt = Instant.now().minusSeconds(100);
        var link = new VesselLinkStore.Link(VesselLinkStore.binding(active.trackingReference()), "9811000", linkedAt);
        when(orders.findAll()).thenReturn(List.of(active, received));
        when(links.find(active)).thenReturn(link);
        when(store.acquire(any())).thenReturn("lease");
        var feedRef = ContainerTrackingService.vesselFeedReference(link.imo());
        var raw = new Observation("VESSEL_TRACKING", null, "TEST VESSEL", null, "Port",
                null, List.of(), List.of(new Event("old", "Schip aangekomen", "Old port", linkedAt.minusSeconds(1).toString(), true),
                new Event("new", "Schip vertrokken", "Port", linkedAt.plusSeconds(1).toString(), true)), null, null);
        when(store.find(feedRef.key())).thenReturn(new TrackingStore.Snapshot(null, raw, Instant.now().minusSeconds(50),
                Instant.now().minusSeconds(50), linkedAt, Instant.now().plusSeconds(1000), null, false));
        service.syncDue();
        verify(provider, never()).create(any()); verify(provider, never()).fetch(anyLong(), any());
        verify(voydar, never()).fetch(anyString(), any());
        var observation = org.mockito.ArgumentCaptor.forClass(Observation.class);
        verify(store).success(eq(ContainerTrackingService.trackingReference(active, link)), observation.capture(), any());
        assertEquals(List.of("new"), observation.getValue().events().stream().map(Event::id).toList());
        assertNull(observation.getValue().eta()); assertNull(observation.getValue().position());
    }

    @Test void accountlessSchedulerFetchesDueVesselAndPersistsSixHourSnapshot() {
        var active = order(1, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        var link = new VesselLinkStore.Link(VesselLinkStore.binding(active.trackingReference()), "9811000", Instant.now().minusSeconds(30));
        when(orders.findAll()).thenReturn(List.of(active)); when(links.find(active)).thenReturn(link);
        when(store.acquire(any())).thenReturn("lease");
        var feedRef = ContainerTrackingService.vesselFeedReference(link.imo());
        var data = VoydarClient.empty(link.imo());
        when(store.find(feedRef.key())).thenReturn(null,
                new TrackingStore.Snapshot(null, data, Instant.now(), Instant.now(), Instant.now(), Instant.now().plusSeconds(21600), null, false));
        when(voydar.fetch(link.imo(), null)).thenReturn(new VoydarClient.Result(data, "etag", null));
        service.syncDue();
        verify(voydar).fetch(link.imo(), null);
        verify(store).feedSuccess(eq(feedRef), eq(data), any(), eq("etag"), isNull());
        verify(store).success(eq(ContainerTrackingService.trackingReference(active, link)), any(), any());
    }

    @Test void globalDisableStopsBothSourcesButRetainsLinkedVesselAndSavedData() {
        var active = order(1, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        var link = new VesselLinkStore.Link(VesselLinkStore.binding(active.trackingReference()), "9811000", Instant.now());
        when(orders.findAll()).thenReturn(List.of(active)); when(links.find(active)).thenReturn(link);
        when(links.all()).thenReturn(Map.of(1L, link));
        service.enabled = false;
        service.syncDue();
        Row refreshed = service.refresh(1);
        assertEquals("9811000", refreshed.vesselImo()); assertFalse(refreshed.automatic());
        assertTrue(refreshed.error().contains("staat uit")); assertFalse(service.feed().configured());
        verify(store, never()).acquire(any());
        verify(voydar, never()).fetch(anyString(), any());
        verify(provider, never()).fetch(anyLong(), any()); verify(provider, never()).create(any());
    }

    @Test void scheduledRefreshSkipsReceivedAndUnsupportedAndKeepsRemoteIdentityOnRetry() {
        var active = order(1, "MSCU1234567", PurchaseOrderStatus.ONDERWEG);
        var received = order(2, "MSCU2222222", PurchaseOrderStatus.ONTVANGEN);
        var unknown = order(3, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        when(orders.findAll()).thenReturn(List.of(active, received, unknown));
        when(provider.configured()).thenReturn(true);
        when(store.acquire(any())).thenReturn("lease");
        Reference reference = ContainerTrackingService.referenceFor(active);
        when(store.find(reference.key())).thenReturn(new TrackingStore.Snapshot(123L, null,
                Instant.now().minusSeconds(30000), null, null, Instant.now().minusSeconds(1), "Old error", false));
        when(provider.fetch(123L, reference)).thenThrow(new ShipsGoClient.TrackingFailure("Niet bereikbaar"));
        service.syncDue();
        verify(provider, never()).create(any());
        verify(provider).fetch(123L, reference);
        verify(store).failed(eq(reference), eq("Niet bereikbaar"), any());
        verify(store).release("lease");
    }

    @Test void disconnectedRefreshKeepsPreviouslySavedObservation() {
        var active = order(1, "MSCU1234567", PurchaseOrderStatus.ONDERWEG);
        when(orders.findAll()).thenReturn(List.of(active));
        Reference ref = ContainerTrackingService.referenceFor(active);
        var position = new Position(51.1, 3.4, "VESSEL", null);
        var observation = new Observation("SAILING", "MSC", "TEST", null, "Op zee", position, List.of(), List.of(), null, null);
        when(store.find(ref.key())).thenReturn(new TrackingStore.Snapshot(123L, observation,
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), null, false));
        Row result = service.refresh(1);
        assertEquals(position, result.position());
        assertNotNull(result.error());
        assertNull(result.nextCheckAt());
        verify(store, never()).acquire(any());
    }

    @Test void accountlessCarrierDatesHaveOwnScheduleAndPersistRejectedWriteReason() {
        var active = order(38, "BL: EGLV143650155115 | EGSU0162841", PurchaseOrderStatus.ONDERWEG);
        when(orders.findAll()).thenReturn(List.of(active));
        when(store.acquire(any())).thenReturn("lease");
        var observation = new Observation("CARRIER_SCHEDULE", "Evergreen", "EVER ALLY", "2026-11-12",
                null, null, List.of(), List.of(), Instant.now(), null);
        when(bexy.fetch(active)).thenReturn(observation);
        when(dateSync.shipment(eq(active), eq(observation), any(), eq("Evergreen via bexy"))).thenReturn("Handmatige wijziging blijft behouden.");
        service.syncDue();
        var dates = ContainerTrackingService.dateReference(active);
        verify(bexy).fetch(active);
        verify(store).success(eq(dates), eq(observation), any());
        verify(store).failed(eq(dates), eq("Handmatige wijziging blijft behouden."), any());
        verify(provider, never()).create(any()); verify(voydar, never()).fetch(anyString(), any());
    }

    @Test void refreshReturnsCanonicalDatesAfterSourceSyncAndGlobalDisableAlsoStopsDateSource() {
        var active = order(38, "BL: EGLV143650155115 | EGSU0162841", PurchaseOrderStatus.ONDERWEG);
        var changed = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(
                Map.of("id", 38, "number", "PO-38", "trackingReference", active.trackingReference(), "status", "ONDERWEG",
                        "expectedArrival", "2026-11-12", "shippedOn", "2026-10-05"), PurchaseOrder.class);
        when(orders.findAll()).thenReturn(List.of(active));
        doReturn(Optional.of(changed)).when(orders).findById(38L);
        service.enabled = false;
        service.syncDue();
        Row row = service.refresh(38);
        assertEquals(java.time.LocalDate.of(2026, 11, 12), row.orderExpectedArrival());
        assertEquals(java.time.LocalDate.of(2026, 10, 5), row.orderShippedOn());
        verifyNoInteractions(bexy); verifyNoInteractions(dateSync); verify(store, never()).acquire(any());
    }

    @Test void refreshRereadsSavedHeaderAfterTheSourceWasApplied() {
        var active = order(38, "BL: EGLV143650155115 | EGSU0162841", PurchaseOrderStatus.ONDERWEG);
        var changed = TrackingDateSyncTest.order(Map.of("expectedArrival", "2026-11-12"));
        when(orders.findAll()).thenReturn(List.of(active));
        doReturn(Optional.of(active), Optional.of(changed)).when(orders).findById(38L);
        when(store.acquire(any())).thenReturn("lease");
        var data = TrackingDateSyncTest.observation(TrackingDateSyncTest.evidence(null, "2026-11-12"), Instant.now());
        when(bexy.fetch(active)).thenReturn(data);
        Row row = service.refresh(38);
        verify(dateSync).shipment(eq(active), eq(data), any(), eq("Evergreen via bexy"));
        assertEquals(java.time.LocalDate.of(2026, 11, 12), row.orderExpectedArrival());
    }

    @Test void dateSnapshotIsIsolatedByOrderAndShipmentReference() {
        var first = order(38, "BL: EGLV143650155115 | EGSU0162841", PurchaseOrderStatus.ONDERWEG);
        var next = order(39, first.trackingReference(), PurchaseOrderStatus.ONDERWEG);
        var corrected = order(38, "BL: EGLV143650155116 | EGSU0162841", PurchaseOrderStatus.ONDERWEG);
        assertNotEquals(ContainerTrackingService.dateReference(first), ContainerTrackingService.dateReference(next));
        assertNotEquals(ContainerTrackingService.dateReference(first), ContainerTrackingService.dateReference(corrected));
    }

    @Test void carrierOnlyRowShowsSuccessfulCheckSeparatelyFromCachedSourceAge() {
        var active = TrackingDateSyncTest.order(Map.of("expectedArrival", "2026-11-12"));
        when(orders.findAll()).thenReturn(List.of(active));
        Instant fetched = Instant.now(), sourceChecked = fetched.minusSeconds(7200);
        var data = TrackingDateSyncTest.observation(TrackingDateSyncTest.evidence(null, "2026-11-12"), sourceChecked);
        var snapshot = new TrackingStore.Snapshot(null, data, fetched, fetched, fetched, fetched.plusSeconds(21600), null, false);
        when(store.all()).thenReturn(Map.of(ContainerTrackingService.dateReference(active).key(), snapshot));
        Row row = service.feed().containers().getFirst();
        assertEquals("CARRIER_SCHEDULE", row.status()); assertEquals("Evergreen", row.carrier()); assertEquals("EVER ALLY", row.vessel());
        assertEquals(fetched, row.checkedAt()); assertEquals(fetched, row.updatedAt()); assertEquals(fetched, row.changedAt());
        assertEquals(sourceChecked, row.dateSourceCheckedAt()); assertNull(row.providerCheckedAt()); assertNull(row.position());
        assertNull(row.error()); assertEquals("Evergreen via bexy", row.dateSource());
    }

    private PurchaseOrder order(long id, String ref, PurchaseOrderStatus status) {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(
                Map.of("id", id, "number", "PO-" + id, "trackingReference", ref, "status", status), PurchaseOrder.class);
    }
}
