package be.enrosed.sourcing.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

/** Small transactions around a provider call; no transaction or row lock spans external HTTP. */
@ApplicationScoped
public class TrackingStore {
    @Inject ObjectMapper json;
    public record Snapshot(Long shipmentId, Observation observation, Instant checkedAt, Instant updatedAt,
                           Instant changedAt, Instant nextCheckAt, String error, boolean terminal, String etag, String lastModified) {
        public Snapshot(Long shipmentId, Observation observation, Instant checkedAt, Instant updatedAt,
                        Instant changedAt, Instant nextCheckAt, String error, boolean terminal) {
            this(shipmentId, observation, checkedAt, updatedAt, changedAt, nextCheckAt, error, terminal, null, null);
        }
        /**
         * The last attempt failed. A date that could not be applied is written right after a success with the
         * same instant (ContainerTrackingService.checkDates) and is not a failed check.
         */
        public boolean checkFailed() { return error != null && !Objects.equals(checkedAt, updatedAt); }
    }

    @Transactional
    public String acquire(Instant now) {
        TrackingLease lease = TrackingLease.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (lease == null) {
            lease = new TrackingLease(); lease.id = 1L; lease.persistAndFlush();
        }
        if (lease.until != null && lease.until.isAfter(now)) return null;
        lease.token = UUID.randomUUID().toString();
        lease.until = now.plus(Duration.ofMinutes(30));
        return lease.token;
    }

    @Transactional
    public void release(String token) {
        TrackingLease lease = TrackingLease.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (lease != null && Objects.equals(lease.token, token)) { lease.token = null; lease.until = null; }
    }

    @Transactional
    public Snapshot find(String key) {
        TrackingEntity entity = TrackingEntity.findById(key);
        return entity == null ? null : snapshot(entity);
    }

    @Transactional
    public Map<String, Snapshot> all() {
        Map<String, Snapshot> result = new HashMap<>();
        for (TrackingEntity entity : TrackingEntity.<TrackingEntity>listAll()) result.put(entity.id, snapshot(entity));
        return result;
    }

    /** One ship's shared feed row as {@link VesselDirectory} reads it; observation is null while no fetch succeeded. */
    public record VesselFeed(String trackingNumber, String id, Observation observation) {}

    /**
     * The shared feed row of every ship ever linked (tracking number "IMO:&lt;imo&gt;", id the feed's own key). Each
     * order's own port-call snapshot carries that same tracking number under another id and is left out, so one
     * ship is one row. Read defensively: a row whose JSON cannot be read is skipped and nothing is thrown, because
     * an exception out of a transactional method would also break the caller's transaction.
     */
    @Transactional
    public List<VesselFeed> vesselFeeds() {
        List<VesselFeed> feeds = new ArrayList<>();
        for (TrackingEntity entity : TrackingEntity.<TrackingEntity>list("trackingNumber like ?1", "IMO:%")) {
            try {
                String imo = entity.trackingNumber.substring(4);
                if (!ContainerTrackingService.vesselFeedReference(imo).key().equals(entity.id)) continue;
                feeds.add(new VesselFeed(entity.trackingNumber, entity.id,
                        entity.observationJson == null ? null : json.readValue(entity.observationJson, Observation.class)));
            } catch (JsonProcessingException | RuntimeException unreadable) {
                /* One bad row must not hide the other ships. */
            }
        }
        return feeds;
    }

    /** Save the remote ID before fetching details: an interrupted detail fetch must never create again. */
    @Transactional
    public void registered(Reference reference, long id) { entity(reference).shipmentId = id; }

    @Transactional
    public void success(Reference reference, Observation observation, Instant now) {
        TrackingEntity entity = entity(reference);
        applySuccess(entity, observation, now, json);
    }

    @Transactional
    public void feedSuccess(Reference reference, Observation observation, Instant now, String etag, String lastModified) {
        TrackingEntity entity = entity(reference);
        applySuccess(entity, observation, now, json);
        entity.etag = etag; entity.lastModified = lastModified;
    }

    static void applySuccess(TrackingEntity entity, Observation observation, Instant now, ObjectMapper json) {
        try {
            // Provider check timestamps alone are not a cargo update. Compare only meaningful data.
            Observation businessData = new Observation(observation.status(), observation.carrier(), observation.vessel(),
                    observation.eta(), observation.location(), observation.position(), observation.route(), observation.events(), null, null, observation.dateEvidence());
            String fingerprint = hash(json.writeValueAsString(businessData));
            if (!Objects.equals(entity.fingerprint, fingerprint)) entity.changedAt = now;
            entity.observationJson = json.writeValueAsString(observation);
            entity.fingerprint = fingerprint;
            entity.checkedAt = now; entity.updatedAt = now; entity.lastError = null;
            entity.terminal = terminal(observation.status());
            entity.nextCheckAt = entity.terminal ? null : now.plus(Duration.ofHours(INTERVAL_HOURS));
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to store tracking observation", failure); }
    }

    @Transactional
    public void failed(Reference reference, String error, Instant now) {
        applyFailure(entity(reference), error, now);
    }

    static void applyFailure(TrackingEntity entity, String error, Instant now) {
        entity.checkedAt = now;
        entity.lastError = error;
        entity.nextCheckAt = now.plus(Duration.ofHours(INTERVAL_HOURS));
    }

    private TrackingEntity entity(Reference reference) {
        TrackingEntity entity = TrackingEntity.findById(reference.key());
        if (entity == null) {
            entity = new TrackingEntity(); entity.id = reference.key(); entity.trackingNumber = reference.number(); entity.persist();
        }
        return entity;
    }

    private Snapshot snapshot(TrackingEntity entity) {
        try {
            Observation data = entity.observationJson == null ? null : json.readValue(entity.observationJson, Observation.class);
            return new Snapshot(entity.shipmentId, data, entity.checkedAt, entity.updatedAt, entity.changedAt,
                    entity.nextCheckAt, entity.lastError, entity.terminal, entity.etag, entity.lastModified);
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read tracking observation", failure); }
    }
}
