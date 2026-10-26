package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

@ApplicationScoped
public class ContainerTrackingService {
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject TrackingStore store;
    @Inject ShipsGoClient provider;
    @Inject VoydarClient voydar;
    @Inject VesselLinkStore links;
    @Inject BexyClient bexy;
    @Inject TrackingDateSync dateSync;
    @ConfigProperty(name = "enrosed.tracking.enabled", defaultValue = "true") boolean enabled = true;

    public Feed feed() {
        Map<String, TrackingStore.Snapshot> snapshots = store.all();
        Map<Long, VesselLinkStore.Link> linked = links.all();
        return new Feed("Voydar / ShipsGo", enabled, INTERVAL_HOURS, trackedOrders().stream()
                .map(order -> {
                    VesselLinkStore.Link link = VesselLinkStore.matching(order, linked.get(order.id()));
                    Reference reference = trackingReference(order, link);
                    Reference dates = dateReference(order);
                    return row(order, link, reference == null ? null : snapshots.get(reference.key()), dates == null ? null : snapshots.get(dates.key()));
                }).toList(), provider.configured());
    }

    public Row setVessel(long orderId, String imo, String expectedReference) {
        links.save(orderId, imo, expectedReference);
        return refresh(orderId);
    }

    public Row refresh(long orderId) {
        PurchaseOrder order = trackedOrders().stream().filter(value -> value.id() == orderId).findFirst()
                .orElseThrow(() -> new NotFoundException("Container met tracking niet gevonden."));
        VesselLinkStore.Link link = links.find(order);
        Reference reference = trackingReference(order, link), dates = dateReference(order);
        if (enabled && (dates != null || reference != null && (link != null || provider.configured()))) {
            String lease = store.acquire(Instant.now());
            if (lease != null) {
                try {
                    if (reference != null && (link != null || provider.configured())) {
                        TrackingStore.Snapshot prior = store.find(reference.key());
                        long minimumSeconds = link == null ? 60 : 900;
                        if (prior == null || prior.checkedAt() == null || prior.checkedAt().isBefore(Instant.now().minusSeconds(minimumSeconds))) {
                            if (link == null) check(order, reference, prior);
                            else checkVessel(order, link, reference);
                        }
                    }
                    PurchaseOrder forDates = orders.findById(orderId).orElse(null);
                    dates = forDates == null ? null : dateReference(forDates);
                    if (dates != null) {
                        TrackingStore.Snapshot priorDates = store.find(dates.key());
                        if (priorDates == null || priorDates.checkedAt() == null || priorDates.checkedAt().isBefore(Instant.now().minusSeconds(900)))
                            checkDates(forDates, dates);
                    }
                } finally { store.release(lease); }
            }
        }
        PurchaseOrder current = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Container met tracking niet gevonden."));
        if (current.archivedAt() != null || current.trackingReference() == null || current.trackingReference().isBlank())
            throw new NotFoundException("Container met tracking niet gevonden.");
        VesselLinkStore.Link currentLink = links.find(current);
        Reference currentReference = trackingReference(current, currentLink), currentDates = dateReference(current);
        return row(current, currentLink, currentReference == null ? null : store.find(currentReference.key()),
                currentDates == null ? null : store.find(currentDates.key()));
    }

    public void syncDue() {
        if (!enabled) return;
        String lease = store.acquire(Instant.now());
        if (lease == null) return;
        try {
            int checked = 0;
            for (PurchaseOrder order : trackedOrders()) {
                if (order.status() == PurchaseOrderStatus.ONTVANGEN || order.receivedOn() != null) continue;
                VesselLinkStore.Link link = links.find(order);
                Reference reference = trackingReference(order, link);
                boolean touched = false;
                if (reference != null && (link != null || provider.configured())) {
                    TrackingStore.Snapshot prior = store.find(reference.key());
                    if (due(prior, Instant.now())) {
                        if (link == null) check(order, reference, prior);
                        else checkVessel(order, link, reference);
                        touched = true;
                    }
                }
                PurchaseOrder forDates = orders.findById(order.id()).orElse(null);
                Reference dates = forDates == null || forDates.archivedAt() != null || forDates.status() == PurchaseOrderStatus.ONTVANGEN
                        || forDates.receivedOn() != null ? null : dateReference(forDates);
                if (dates != null && due(store.find(dates.key()), Instant.now())) {
                    checkDates(forDates, dates);
                    touched = true;
                }
                if ((touched && ++checked >= 20) || Thread.currentThread().isInterrupted()) break;
            }
        } finally { store.release(lease); }
    }

    private void checkDates(PurchaseOrder order, Reference reference) {
        try {
            Observation observation = bexy.fetch(order);
            Instant now = Instant.now();
            store.success(reference, observation, now);
            String reason = dateSync.shipment(order, observation, now, "Evergreen via bexy");
            if (reason != null) store.failed(reference, reason, now);
        } catch (ShipsGoClient.TrackingFailure failure) {
            store.failed(reference, failure.getMessage(), Instant.now());
        } catch (RuntimeException failure) {
            store.failed(reference, "De aankomstdatum kon niet veilig worden bijgewerkt. De opgeslagen planning blijft behouden.", Instant.now());
        }
    }

    private void checkVessel(PurchaseOrder order, VesselLinkStore.Link link, Reference reference) {
        Reference feed = vesselFeedReference(link.imo());
        TrackingStore.Snapshot cached = store.find(feed.key());
        Instant now = Instant.now();
        // One public vessel feed can serve multiple purchases. Even manual refreshes and relinking respect
        // Voydar's minimum fifteen-minute polling interval, including failed attempts.
        if (cached == null || cached.checkedAt() == null || !cached.checkedAt().isAfter(now.minusSeconds(900))) {
            try {
                VoydarClient.Result result = voydar.fetch(link.imo(), cached);
                store.feedSuccess(feed, result.observation(), now, result.etag(), result.lastModified());
            } catch (ShipsGoClient.TrackingFailure failure) {
                store.failed(feed, failure.getMessage(), now);
            } catch (RuntimeException failure) {
                store.failed(feed, "De scheepsfeed kon niet worden gecontroleerd. De vorige meldingen blijven bewaard.", now);
            }
            cached = store.find(feed.key());
        }
        if (cached == null || cached.error() != null) {
            store.failed(reference, cached == null ? "De scheepsfeed is nog niet beschikbaar." : cached.error(), now);
            return;
        }
        Observation observation = cached.observation() == null ? VoydarClient.empty(link.imo()) : cached.observation();
        store.success(reference, VoydarClient.since(observation, cutoff(order, link)), now);
        dateSync.vessel(order, link, observation, cached.checkedAt(), now);
    }

    static boolean due(TrackingStore.Snapshot snapshot, Instant now) {
        return snapshot == null || (!snapshot.terminal() && (snapshot.nextCheckAt() == null || !snapshot.nextCheckAt().isAfter(now)));
    }

    private void check(PurchaseOrder order, Reference reference, TrackingStore.Snapshot prior) {
        try {
            Long id = prior == null ? null : prior.shipmentId();
            if (id == null) { id = provider.create(reference); store.registered(reference, id); }
            Observation observation = provider.fetch(id, reference);
            Instant now = Instant.now();
            store.success(reference, observation, now);
            dateSync.shipment(order, observation, now, "ShipsGo");
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

    private Row row(PurchaseOrder order, VesselLinkStore.Link link, TrackingStore.Snapshot snapshot, TrackingStore.Snapshot dateSnapshot) {
        Reference reference = trackingReference(order, link);
        Observation data = snapshot == null ? null : snapshot.observation();
        if (link != null && data != null) data = VoydarClient.since(data, cutoff(order, link));
        String status = link != null ? "VESSEL_TRACKING" : data != null ? data.status() : reference == null ? "UNSUPPORTED" : !provider.configured()
                ? "UNCONFIGURED" : snapshot != null && snapshot.error() != null ? "ERROR" : "PENDING";
        String error = snapshot == null ? null : snapshot.error();
        if (link == null && reference == null) error = "Koppel het IMO-nummer van het schip voor gratis scheepsmeldingen. Voor containertracking: controleer het containernummer of gebruik BL: of BOOKING: voor een bevestigd nummer van de rederij.";
        else if (link == null && !provider.configured()) error = "Koppel het IMO-nummer voor gratis scheepsmeldingen. Volledige containertracking is optioneel via een ShipsGo API-sleutel.";
        if (!enabled) error = "Automatische tracking staat uit. De laatst opgeslagen gegevens blijven beschikbaar.";
        boolean automatic = enabled && (link != null || (reference != null && provider.configured()) || dateReference(order) != null)
                && order.status() != PurchaseOrderStatus.ONTVANGEN && order.receivedOn() == null && (snapshot == null || !snapshot.terminal());
        Instant next = !automatic || snapshot == null ? null : snapshot.nextCheckAt();
        if (automatic && dateSnapshot != null && dateSnapshot.nextCheckAt() != null && (next == null || dateSnapshot.nextCheckAt().isBefore(next))) next = dateSnapshot.nextCheckAt();
        String source = link != null ? "VOYDAR" : reference != null && (provider.configured() || data != null) ? "SHIPSGO" : null;
        Observation dateData = dateSnapshot == null ? null : dateSnapshot.observation();
        if (link == null && data == null && dateData != null) {
            status = "CARRIER_SCHEDULE";
            if (enabled) error = null;
        }
        String dateSource = dateData != null ? "Evergreen via bexy" : data != null && data.dateEvidence() != null ? "ShipsGo" : null;
        Observation evidence = dateData != null ? dateData : data != null && data.dateEvidence() != null ? data : null;
        String dateError = dateSnapshot == null ? null : dateSnapshot.error();
        if (dateError == null && evidence != null) {
            TrackingDateSync.Plan plan = TrackingDateSync.shipmentPlan(order, evidence, Instant.now());
            dateError = plan.error();
            if (dateError == null && plan.expectedArrival() != null && !Objects.equals(plan.expectedArrival(), order.expectedArrival()))
                dateError = "De bronplanning wijkt af van de opgeslagen aankomstdatum. De opgeslagen datum is nog niet aangepast.";
        }
        return new Row(order.id(), order.number(), order.alias(), order.trackingReference(), order.status().name(),
                status, label(status), data == null ? dateData == null ? null : dateData.carrier() : data.carrier(),
                data == null ? link != null ? "IMO " + link.imo() : dateData == null ? null : dateData.vessel() : data.vessel(),
                data == null ? null : data.eta(), data == null ? null : data.location(), data == null ? null : data.position(),
                data == null ? List.of() : data.route(), data == null ? List.of() : data.events(),
                snapshot == null ? dateSnapshot == null ? null : dateSnapshot.checkedAt() : snapshot.checkedAt(),
                snapshot == null ? dateSnapshot == null ? null : dateSnapshot.updatedAt() : snapshot.updatedAt(), next,
                snapshot == null ? dateSnapshot == null ? null : dateSnapshot.changedAt() : snapshot.changedAt(), error,
                link == null ? sourceLink(order.trackingReference()) : "https://www.voydar.com/vessel/" + link.imo(),
                data == null ? null : data.providerCheckedAt(), data == null ? null : data.providerUpdatedAt(),
                source, link == null ? null : link.imo(), automatic, order.expectedArrival(), order.shippedOn(),
                dateSource, evidence == null ? null : evidence.providerCheckedAt(), dateError);
    }

    static Reference dateReference(PurchaseOrder order) {
        String identity = BexyClient.identity(order);
        return identity == null ? null : new Reference(hash("BEXY:ORDER:" + order.id() + ":" + identity), "BEXY:" + order.id(), false);
    }

    static Reference trackingReference(PurchaseOrder order, VesselLinkStore.Link link) {
        return link == null ? referenceFor(order) : new Reference(hash("VOYDAR:ORDER:" + order.id() + ":" + link.referenceHash() + ":" + link.imo() + ":" + link.linkedAt()), "IMO:" + link.imo(), false);
    }
    static Reference vesselFeedReference(String imo) { return new Reference(hash("VOYDAR:FEED:" + imo), "IMO:" + imo, false); }
    static Instant cutoff(PurchaseOrder order, VesselLinkStore.Link link) {
        return order.shippedOn() == null ? link.linkedAt() : order.shippedOn().atStartOfDay(ZoneOffset.UTC).toInstant();
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
