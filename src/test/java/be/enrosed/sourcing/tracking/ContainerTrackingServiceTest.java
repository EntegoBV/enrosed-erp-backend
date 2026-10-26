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

    @BeforeEach void prepare() {
        service = new ContainerTrackingService();
        service.orders = orders = mock(SourcingRepositories.PurchaseOrders.class);
        service.store = store = mock(TrackingStore.class);
        service.provider = provider = mock(ShipsGoClient.class);
        when(store.all()).thenReturn(Map.of());
    }

    @Test void feedShowsEveryTrackedLivePurchaseIncludingUnsupportedButNeverCallsProvider() {
        var unknown = order(1, "SZ260703903", PurchaseOrderStatus.ONDERWEG);
        var received = order(2, "MSCU1234567", PurchaseOrderStatus.ONTVANGEN);
        var archived = order(3, "MSCU1234567", PurchaseOrderStatus.ONDERWEG).withArchivedAt(Instant.now());
        var empty = order(4, " ", PurchaseOrderStatus.ONDERWEG);
        when(orders.findAll()).thenReturn(List.of(unknown, received, archived, empty));
        Feed feed = service.feed();
        assertEquals(2, feed.containers().size());
        assertEquals("UNCONFIGURED", feed.containers().getFirst().status());
        assertEquals("UNSUPPORTED", feed.containers().getLast().status());
        assertNull(feed.containers().getLast().position());
        verify(provider, never()).create(any()); verify(provider, never()).fetch(anyLong(), any());
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

    private PurchaseOrder order(long id, String ref, PurchaseOrderStatus status) {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(
                Map.of("id", id, "number", "PO-" + id, "trackingReference", ref, "status", status), PurchaseOrder.class);
    }
}
