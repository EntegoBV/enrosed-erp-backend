package be.enrosed.shared.audit;

import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes and reads the append-only operational log. */
@ApplicationScoped
public class ActivityLogService {

    public static final String ACTION_CREATED = "CREATED";
    public static final String ACTION_UPDATED = "UPDATED";
    public static final String ACTION_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String ACTION_DELETED = "DELETED";
    public static final String ACTION_RECEIVED = "RECEIVED";
    public static final String ACTION_STOCK_BOOKED = "STOCK_BOOKED";
    public static final String ACTION_PAYMENT_ADDED = "PAYMENT_ADDED";
    public static final String ACTION_PAYMENT_DELETED = "PAYMENT_DELETED";
    public static final String ACTION_DOCUMENT_ADDED = "DOCUMENT_ADDED";
    public static final String ACTION_DOCUMENT_RENAMED = "DOCUMENT_RENAMED";
    public static final String ACTION_DOCUMENT_DELETED = "DOCUMENT_DELETED";
    public static final String ACTION_COSTS_APPLIED = "COSTS_APPLIED";
    public static final String ACTION_DUPLICATED = "DUPLICATED";
    public static final String ACTION_IDENTITY_FINALIZED = "IDENTITY_FINALIZED";

    public static final String ENTITY_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String ENTITY_PRODUCT_FAMILY = "PRODUCT_FAMILY";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final CurrentActor currentActor;

    public ActivityLogService(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    /**
     * Appends one successful business action in the caller's transaction.
     *
     * <p>The actor is intentionally not a parameter: only the authenticated
     * server identity may attribute an action. Keep the summary concise and
     * never pass credentials, request bodies, or secret-bearing objects.</p>
     */
    @Transactional
    public ActivityDto record(String action, String entityType, String entityId,
                              String entityLabel, String summary) {
        ActorRef actor = currentActor.current();
        ActivityLogEntity entry = new ActivityLogEntity();
        entry.occurredAt = Instant.now();
        entry.actorUsername = required(actor.username(), "actor username", 64);
        entry.actorDisplayName = required(actor.displayName(), "actor display name", 100);
        entry.action = code(action, "action");
        entry.entityType = code(entityType, "entity type");
        entry.entityId = optional(entityId, 100);
        entry.entityLabel = optional(entityLabel, 255);
        entry.summary = required(summary, "summary", 500);
        entry.persistAndFlush();
        return entry.toDto();
    }

    /** Reads newest first. The opaque cursor is the last event id returned. */
    public ActivityPageDto list(String actor, String entityType, String entityId,
                                Long before, int requestedLimit) {
        int limit = requestedLimit <= 0
                ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        conditions.add("id is not null");

        String actorFilter = ActorRef.canonicalUsername(actor);
        if (!actorFilter.isBlank()) {
            conditions.add("actorUsername = :actor");
            parameters.put("actor", actorFilter);
        }
        String entityTypeFilter = normalizedCode(entityType);
        if (entityTypeFilter != null) {
            conditions.add("entityType = :entityType");
            parameters.put("entityType", entityTypeFilter);
        }
        String entityIdFilter = optional(entityId, 100);
        if (entityIdFilter != null) {
            conditions.add("entityId = :entityId");
            parameters.put("entityId", entityIdFilter);
        }
        if (before != null) {
            conditions.add("id < :before");
            parameters.put("before", before);
        }

        String query = String.join(" and ", conditions) + " order by id desc";
        List<ActivityLogEntity> rows = ActivityLogEntity
                .<ActivityLogEntity>find(query, parameters)
                .range(0, limit)
                .list();
        boolean hasMore = rows.size() > limit;
        List<ActivityDto> items = rows.stream().limit(limit).map(ActivityLogEntity::toDto).toList();
        Long nextBefore = hasMore && !items.isEmpty() ? items.getLast().id() : null;
        return new ActivityPageDto(items, nextBefore);
    }

    private static String code(String value, String field) {
        String normalized = normalizedCode(value);
        if (normalized == null || normalized.length() > 64 || !normalized.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid activity " + field);
        }
        return normalized;
    }

    private static String normalizedCode(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String field, int maxLength) {
        String cleaned = optional(value, maxLength);
        if (cleaned == null) throw new IllegalArgumentException("Missing activity " + field);
        return cleaned;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip();
        if (cleaned.length() > maxLength) {
            throw new IllegalArgumentException("Activity value is too long");
        }
        return cleaned;
    }
}
