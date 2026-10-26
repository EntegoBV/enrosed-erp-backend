package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

@ApplicationScoped
public class ContainerTrackingService {
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject TrackingStore store;
    @Inject ShipsGoClient provider;

    public Feed feed() {
        Map<String, TrackingStore.Snapshot> snapshots = store.all();
        return new Feed("ShipsGo", provider.configured(), INTERVAL_HOURS, trackedOrders().stream()
                .map(order -> { Reference reference = referenceFor(order);
                    return row(order, reference == null ? null : snapshots.get(reference.key())); })
                .toList());
    }

    public Row refresh(long orderId) {
        PurchaseOrder order = trackedOrders().stream().filter(value -> value.id() == orderId).findFirst()
                .orElseThrow(() -> new NotFoundException("Container met tracking niet gevonden."));
        Reference reference = referenceFor(order);
        if (reference == null || !provider.configured()) return row(order, reference == null ? null : store.find(reference.key()));
        String lease = store.acquire(Instant.now());
        if (lease != null) {
            try {
                TrackingStore.Snapshot prior = store.find(reference.key());
                // Protect provider quota from repeated taps; manual refresh is otherwise allowed after arrival.
                if (prior == null || prior.checkedAt() == null || prior.checkedAt().isBefore(Instant.now().minusSeconds(60)))
                    check(reference, prior);
            } finally { store.release(lease); }
        }
        // Re-read the purchase reference: a changed number must never display an old container's location.
        PurchaseOrder current = orders.findById(orderId).orElseThrow(NotFoundException::new);
        if (current.archivedAt() != null || current.trackingReference() == null || current.trackingReference().isBlank())
            throw new NotFoundException("Container met tracking niet gevonden.");
        Reference currentReference = referenceFor(current);
        return row(current, currentReference == null ? null : store.find(currentReference.key()));
    }

    public void syncDue() {
        if (!provider.configured()) return;
        String lease = store.acquire(Instant.now());
        if (lease == null) return;
        try {
            Map<String, Reference> unique = new LinkedHashMap<>();
            for (PurchaseOrder order : trackedOrders()) {
                if (order.status() == PurchaseOrderStatus.ONTVANGEN) continue;
                Reference reference = referenceFor(order);
                if (reference != null) unique.putIfAbsent(reference.key(), reference);
            }
            int checked = 0;
            for (Reference reference : unique.values()) {
                TrackingStore.Snapshot prior = store.find(reference.key());
                if (!due(prior, Instant.now())) continue;
                check(reference, prior);
                // 20 * (create + details + route) stays below the documented 100 requests/minute.
                if (++checked >= 20 || Thread.currentThread().isInterrupted()) break;
            }
        } finally { store.release(lease); }
    }

    static boolean due(TrackingStore.Snapshot snapshot, Instant now) {
        return snapshot == null || (!snapshot.terminal() && (snapshot.nextCheckAt() == null || !snapshot.nextCheckAt().isAfter(now)));
    }

    private void check(Reference reference, TrackingStore.Snapshot prior) {
        try {
            Long id = prior == null ? null : prior.shipmentId();
            if (id == null) { id = provider.create(reference); store.registered(reference, id); }
            store.success(reference, provider.fetch(id, reference), Instant.now());
        } catch (ShipsGoClient.TrackingFailure failure) {
            store.failed(reference, failure.getMessage(), Instant.now());
        } catch (RuntimeException failure) {
            // A single bad shipment must not stop the remaining containers. Keep previous observations intact.
            store.failed(reference, "De trackingcontrole is mislukt. De vorige gegevens blijven bewaard.", Instant.now());
        }
    }

    private List<PurchaseOrder> trackedOrders() {
        return orders.findAll().stream().filter(order -> order.archivedAt() == null && order.trackingReference() != null
                        && !order.trackingReference().isBlank())
                .sorted(Comparator.comparing(PurchaseOrder::id).reversed()).toList();
    }

    private Row row(PurchaseOrder order, TrackingStore.Snapshot snapshot) {
        Reference reference = referenceFor(order);
        Observation data = snapshot == null ? null : snapshot.observation();
        String status = data != null ? data.status() : reference == null ? "UNSUPPORTED" : !provider.configured()
                ? "UNCONFIGURED" : snapshot != null && snapshot.error() != null ? "ERROR" : "PENDING";
        String error = snapshot == null ? null : snapshot.error();
        if (reference == null) error = "Controleer het containernummer of de master-B/L van de rederij. Gebruik BL: of BOOKING: voor een bevestigd B/L- of boekingsnummer.";
        else if (!provider.configured()) error = "Koppel een ShipsGo API-sleutel om automatisch elke zes uur te controleren.";
        Instant next = snapshot == null ? null : snapshot.nextCheckAt();
        if (!provider.configured() || order.status() == PurchaseOrderStatus.ONTVANGEN || (snapshot != null && snapshot.terminal())) next = null;
        return new Row(order.id(), order.number(), order.alias(), order.trackingReference(), order.status().name(),
                status, label(status), data == null ? null : data.carrier(), data == null ? null : data.vessel(),
                data == null ? null : data.eta(), data == null ? null : data.location(), data == null ? null : data.position(),
                data == null ? List.of() : data.route(), data == null ? List.of() : data.events(),
                snapshot == null ? null : snapshot.checkedAt(), snapshot == null ? null : snapshot.updatedAt(), next,
                snapshot == null ? null : snapshot.changedAt(), error, sourceLink(order.trackingReference()),
                data == null ? null : data.providerCheckedAt(), data == null ? null : data.providerUpdatedAt());
    }

    static Reference referenceFor(PurchaseOrder order) {
        Reference parsed = reference(order.trackingReference());
        if (parsed == null) return null;
        return referenceForOrder(parsed, order.id());
    }

    static Reference referenceForOrder(Reference parsed, long orderId) {
        // Container numbers are reused on later voyages. The stable purchase identity isolates those voyages
        // while a correction of a departure date or retry keeps the same paid provider registration.
        return new Reference(hash(parsed.key() + ":ORDER:" + orderId), parsed.number(), parsed.container());
    }
}
