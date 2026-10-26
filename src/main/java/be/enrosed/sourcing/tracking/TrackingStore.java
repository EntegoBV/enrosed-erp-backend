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
                           Instant changedAt, Instant nextCheckAt, String error, boolean terminal) {}

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

    /** Save the remote ID before fetching details: an interrupted detail fetch must never create again. */
    @Transactional
    public void registered(Reference reference, long id) { entity(reference).shipmentId = id; }

    @Transactional
    public void success(Reference reference, Observation observation, Instant now) {
        TrackingEntity entity = entity(reference);
        applySuccess(entity, observation, now, json);
    }

    static void applySuccess(TrackingEntity entity, Observation observation, Instant now, ObjectMapper json) {
        try {
            // Provider check timestamps alone are not a cargo update. Compare only meaningful data.
            Observation businessData = new Observation(observation.status(), observation.carrier(), observation.vessel(),
                    observation.eta(), observation.location(), observation.position(), observation.route(), observation.events(), null, null);
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
                    entity.nextCheckAt, entity.lastError, entity.terminal);
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read tracking observation", failure); }
    }
}
