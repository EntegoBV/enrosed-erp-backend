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
    @Inject ArrivalMoveStore moves;
    @ConfigProperty(name = "enrosed.tracking.enabled", defaultValue = "true") boolean enabled = true;
    /** The date lookup answered without data for a bill it had data for: its dates are no longer followed. */
    static final String DATES_GONE = "De datumbron gaf bij de laatste controle geen gegevens.";

    public Feed feed() {
        Map<String, TrackingStore.Snapshot> snapshots = store.all();
        Map<Long, VesselLinkStore.Link> linked = links.all();
        Map<Long, ArrivalMoveStore.Move> moved = moves.all();
        return new Feed("Voydar / ShipsGo", enabled, INTERVAL_HOURS, trackedOrders().stream()
                .map(order -> {
                    VesselLinkStore.Link link = VesselLinkStore.matching(order, linked.get(order.id()));
                    Reference reference = trackingReference(order, link);
                    Reference dates = dateReference(order);
                    return row(order, link, reference == null ? null : snapshots.get(reference.key()), dates == null ? null : snapshots.get(dates.key()),
                            moved.get(order.id()), null);
                }).toList(), provider.configured());
    }

    /** The same feed for one purchase order: an order page asks for its own row, not for every container. */
    public Feed feed(long orderId) {
        PurchaseOrder order = orders.findById(orderId).filter(ContainerTrackingService::tracked).orElse(null);
        return new Feed("Voydar / ShipsGo", enabled, INTERVAL_HOURS, order == null ? List.of() : List.of(stored(order, null)),
                provider.configured());
    }

    public Row setVessel(long orderId, String imo, String expectedReference) {
        links.save(orderId, imo, expectedReference);
        return refresh(orderId);
    }

    /**
     * A manual check. The answer always says what happened (refreshOutcome): CHECKED a source was asked, COOLDOWN
     * the last check is too recent (manualCheckAt says from when it can run), BUSY the scheduled run holds the
     * lease, OFF tracking is switched off, NO_SOURCE nothing is linked that could be asked.
     */
    public Row refresh(long orderId) {
        PurchaseOrder order = trackedOrders().stream().filter(value -> value.id() == orderId).findFirst()
                .orElseThrow(() -> new NotFoundException("Container met tracking niet gevonden."));
        VesselLinkStore.Link link = links.find(order);
        Reference reference = trackingReference(order, link), dates = dateReference(order);
        String outcome;
        if (!enabled) outcome = "OFF";
        else if (dates == null && !(reference != null && (link != null || provider.configured()))) outcome = "NO_SOURCE";
        else {
            String lease = store.acquire(Instant.now());
            if (lease == null) outcome = "BUSY";
            else {
                boolean ran = false;
                try {
                    if (reference != null && (link != null || provider.configured())) {
                        TrackingStore.Snapshot prior = store.find(reference.key());
                        long minimumSeconds = link == null ? MANUAL_SECONDS_PAID : MANUAL_SECONDS_FREE;
                        if (prior == null || prior.checkedAt() == null || prior.checkedAt().isBefore(Instant.now().minusSeconds(minimumSeconds))) {
                            if (link == null) check(order, reference, prior);
                            else checkVessel(order, link, reference);
                            ran = true;
                        }
                    }
                    PurchaseOrder forDates = dateSync.reread(orderId).orElse(null);
                    dates = forDates == null ? null : dateReference(forDates);
                    if (dates != null) {
                        TrackingStore.Snapshot priorDates = store.find(dates.key());
                        if (priorDates == null || priorDates.checkedAt() == null || priorDates.checkedAt().isBefore(Instant.now().minusSeconds(MANUAL_SECONDS_FREE))) {
                            checkDates(forDates, dates);
                            ran = true;
                        }
                    }
                } finally { store.release(lease); }
                outcome = ran ? "CHECKED" : "COOLDOWN";
            }
        }
        // Read again in its own transaction: the answer must show the dates this check just wrote.
        PurchaseOrder current = dateSync.reread(orderId).orElseThrow(() -> new NotFoundException("Container met tracking niet gevonden."));
        if (!tracked(current)) throw new NotFoundException("Container met tracking niet gevonden.");
        return stored(current, outcome);
    }

    /** The row from what is stored now; no source is asked. */
    private Row stored(PurchaseOrder order, String refreshOutcome) {
        VesselLinkStore.Link link = links.find(order);
        Reference reference = trackingReference(order, link), dates = dateReference(order);
        return row(order, link, reference == null ? null : store.find(reference.key()),
                dates == null ? null : store.find(dates.key()), moves.find(order.id()), refreshOutcome);
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
                PurchaseOrder forDates = dateSync.reread(order.id()).orElse(null);
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
            // The same instant as the success above: Snapshot.checkFailed() reads that as "date not applied".
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

    private static boolean tracked(PurchaseOrder order) {
        return order.archivedAt() == null && order.trackingReference() != null && !order.trackingReference().isBlank();
    }

    private List<PurchaseOrder> trackedOrders() {
        return orders.findAll().stream().filter(ContainerTrackingService::tracked)
                .sorted(Comparator.comparing(PurchaseOrder::id).reversed()).toList();
    }

    private Row row(PurchaseOrder order, VesselLinkStore.Link link, TrackingStore.Snapshot snapshot, TrackingStore.Snapshot dateSnapshot,
                    ArrivalMoveStore.Move move, String refreshOutcome) {
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
        /* The last attempt failed, also when no source answered yet (the row's error then holds guidance, not the
           failure). "No record yet" from the date lookup is waiting while it never answered (a fresh bill): it stays
           the dateError line. Once it gave data, an answer without data is a failed check like any other. A received
           order is never checked again, so a failure there is history, not news. */
        boolean received = order.status() == PurchaseOrderStatus.ONTVANGEN || order.receivedOn() != null;
        boolean mainActive = link != null || (reference != null && provider.configured());
        boolean waiting = dateSnapshot != null && dateSnapshot.updatedAt() == null && BexyClient.NO_DATA_YET.equals(dateSnapshot.error());
        TrackingStore.Snapshot failed = received ? null : mainActive && snapshot != null && snapshot.checkFailed() ? snapshot
                : dateSnapshot != null && dateSnapshot.checkFailed() && !waiting ? dateSnapshot : null;
        String failure = failed == null ? null : failed == dateSnapshot && BexyClient.NO_DATA_YET.equals(failed.error()) ? DATES_GONE : failed.error();
        // One text on the panel: the small date line says nothing the failure notice already says.
        if (failure != null && failed == dateSnapshot) dateError = failure;
        Integer movedDays = received ? null : ArrivalMoveStore.days(move, order.expectedArrival());
        Instant manual = null;
        if (automatic) {
            Instant now = Instant.now();
            Instant main = !mainActive ? null : snapshot == null || snapshot.checkedAt() == null ? Instant.EPOCH
                    : snapshot.checkedAt().plusSeconds(link == null ? MANUAL_SECONDS_PAID : MANUAL_SECONDS_FREE);
            Instant date = dateReference(order) == null ? null : dateSnapshot == null || dateSnapshot.checkedAt() == null ? Instant.EPOCH
                    : dateSnapshot.checkedAt().plusSeconds(MANUAL_SECONDS_FREE);
            Instant earliest = main == null ? date : date == null || main.isBefore(date) ? main : date;
            if (earliest != null && earliest.isAfter(now)) manual = earliest;
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
                dateSource, evidence == null ? null : evidence.providerCheckedAt(), dateError,
                recognise(order.trackingReference()), failure, failed == null ? null : failed.checkedAt(), failed == null ? null : failed.updatedAt(),
                movedDays == null ? null : move.from(), movedDays == null ? null : move.at(), movedDays, manual, refreshOutcome);
    }

    static Reference dateReference(PurchaseOrder order) {
        String identity = BexyClient.identity(order);
        return identity == null ? null : new Reference(hash("BEXY:ORDER:" + order.id() + ":" + identity), "BEXY:" + order.id(), false);
    }

    static Reference trackingReference(PurchaseOrder order, VesselLinkStore.Link link) {
        return link == null ? referenceFor(order) : new Reference(vesselKey(order.id(), link.referenceHash(), link.imo(), link.linkedAt()), "IMO:" + link.imo(), false);
    }
    /** The key of an order's own port-call snapshot. It holds the link's reference hash: VesselLinkStore.rebind moves the snapshot with it. */
    static String vesselKey(long orderId, String referenceHash, String imo, Instant linkedAt) {
        return hash("VOYDAR:ORDER:" + orderId + ":" + referenceHash + ":" + imo + ":" + linkedAt);
    }
    static Reference vesselFeedReference(String imo) { return new Reference(hash("VOYDAR:FEED:" + imo), "IMO:" + imo, false); }
    /**
     * Port calls from the day before the departure day on. shippedOn is the origin port's own day (Ningbo is
     * eight hours ahead of UTC), so UTC midnight of that day cut off the very departure that set the date.
     */
    static Instant cutoff(PurchaseOrder order, VesselLinkStore.Link link) {
        return order.shippedOn() == null ? link.linkedAt() : order.shippedOn().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
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
