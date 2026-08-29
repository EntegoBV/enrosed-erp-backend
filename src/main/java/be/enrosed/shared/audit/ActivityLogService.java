package be.enrosed.shared.audit;

import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes and reads the append-only operational log. */
@ApplicationScoped
public class ActivityLogService {

    private static final Logger LOG = Logger.getLogger(ActivityLogService.class);

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
    public static final String ACTION_PHOTO_ADDED = "PHOTO_ADDED";
    public static final String ACTION_PHOTO_DELETED = "PHOTO_DELETED";
    public static final String ACTION_PHOTO_REORDERED = "PHOTO_REORDERED";
    public static final String ACTION_COSTS_APPLIED = "COSTS_APPLIED";
    public static final String ACTION_DUPLICATED = "DUPLICATED";
    public static final String ACTION_IDENTITY_FINALIZED = "IDENTITY_FINALIZED";

    public static final String ENTITY_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String ENTITY_PRODUCT_FAMILY = "PRODUCT_FAMILY";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CHANGES = 40;
    private static final int MAX_CHANGE_LABEL_LENGTH = 100;
    private static final int MAX_CHANGE_VALUE_LENGTH = 300;
    private static final int MAX_CHANGES_JSON_LENGTH = 16_000;
    private static final ActivityChangeDto TRUNCATED_DETAILS = new ActivityChangeDto(
            "activity.detailsTruncated", "Meer wijzigingen niet weergegeven", null, null);
    private static final TypeReference<List<ActivityChangeDto>> CHANGE_LIST = new TypeReference<>() {};

    private record StoredChanges(List<ActivityChangeDto> items, String json) {}

    private final CurrentActor currentActor;
    private final ObjectMapper json;

    public ActivityLogService(CurrentActor currentActor, ObjectMapper json) {
        this.currentActor = currentActor;
        this.json = json;
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
        return record(action, entityType, entityId, entityLabel, summary, List.of());
    }

    @Transactional
    public ActivityDto record(String action, String entityType, String entityId,
                              String entityLabel, String summary,
                              List<ActivityChangeDto> changes) {
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
        StoredChanges storedChanges = storedChanges(safeChanges(changes));
        entry.changesJson = storedChanges.json();
        entry.persistAndFlush();
        return entry.toDto(storedChanges.items());
    }

    /** Reads newest first. The opaque cursor is the last event id returned. */
    public ActivityPageDto list(String actor, String entityType, String entityId,
                                Long before, int requestedLimit) {
        return list(actor, null, entityType, entityId, before, requestedLimit);
    }

    /** Reads newest first and optionally narrows the feed to one business category. */
    public ActivityPageDto list(String actor, String category, String entityType, String entityId,
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
        ActivityCategory categoryFilter = ActivityCategory.fromQuery(category);
        if (categoryFilter != null) {
            if (categoryFilter == ActivityCategory.OTHER) {
                conditions.add("entityType not in :knownEntityTypes");
                parameters.put("knownEntityTypes", ActivityCategory.knownEntityTypes());
            } else {
                conditions.add("entityType in :categoryEntityTypes");
                parameters.put("categoryEntityTypes", categoryFilter.entityTypes());
            }
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
        List<ActivityDto> items = rows.stream().limit(limit).map(this::toDto).toList();
        Long nextBefore = hasMore && !items.isEmpty() ? items.getLast().id() : null;
        return new ActivityPageDto(items, nextBefore);
    }

    private ActivityDto toDto(ActivityLogEntity row) {
        return row.toDto(readChanges(row.changesJson));
    }

    private List<ActivityChangeDto> safeChanges(List<ActivityChangeDto> changes) {
        if (changes == null || changes.isEmpty()) return List.of();
        List<ActivityChangeDto> safe = new ArrayList<>(Math.min(changes.size(), MAX_CHANGES));
        boolean truncated = false;
        for (ActivityChangeDto change : changes) {
            if (change == null) continue;
            if (safe.size() == MAX_CHANGES) {
                truncated = true;
                break;
            }
            safe.add(new ActivityChangeDto(
                    safeField(change.field()),
                    requiredDisplay(change.label(), "change label", MAX_CHANGE_LABEL_LENGTH),
                    optionalDisplay(change.beforeValue(), MAX_CHANGE_VALUE_LENGTH),
                    optionalDisplay(change.afterValue(), MAX_CHANGE_VALUE_LENGTH)));
        }
        if (truncated) safe.set(MAX_CHANGES - 1, TRUNCATED_DETAILS);
        return List.copyOf(safe);
    }

    /** Fits the persisted representation, not just its pre-JSON source strings. */
    private StoredChanges storedChanges(List<ActivityChangeDto> changes) {
        if (changes.isEmpty()) return new StoredChanges(List.of(), null);
        String encoded = writeChanges(changes);
        if (encoded.length() <= MAX_CHANGES_JSON_LENGTH) {
            return new StoredChanges(changes, encoded);
        }

        for (int keep = changes.size() - 1; keep >= 0; keep--) {
            List<ActivityChangeDto> compact = new ArrayList<>(changes.subList(0, keep));
            compact.add(TRUNCATED_DETAILS);
            List<ActivityChangeDto> immutable = List.copyOf(compact);
            encoded = writeChanges(immutable);
            if (encoded.length() <= MAX_CHANGES_JSON_LENGTH) {
                return new StoredChanges(immutable, encoded);
            }
        }

        // The fixed marker is deliberately tiny, but keep the audit action usable even if its
        // serialized representation ever changes unexpectedly.
        return new StoredChanges(List.of(), null);
    }

    private String writeChanges(List<ActivityChangeDto> changes) {
        if (changes.isEmpty()) return null;
        try {
            return json.writeValueAsString(changes);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid activity changes", exception);
        }
    }

    private List<ActivityChangeDto> readChanges(String changesJson) {
        if (changesJson == null || changesJson.isBlank()) return List.of();
        try {
            return List.copyOf(json.readValue(changesJson, CHANGE_LIST));
        } catch (JsonProcessingException | RuntimeException exception) {
            LOG.warnf("Ignoring unreadable activity change details: %s", exception.getMessage());
            return List.of();
        }
    }

    private static String safeField(String value) {
        String cleaned = required(value, "change field", 80);
        if (!cleaned.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid activity change field");
        }
        return cleaned;
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

    private static String requiredDisplay(String value, String field, int maxLength) {
        String cleaned = optionalDisplay(value, maxLength);
        if (cleaned == null) throw new IllegalArgumentException("Missing activity " + field);
        return cleaned;
    }

    /** Display metadata must never make an otherwise valid business write fail. */
    private static String optionalDisplay(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip();
        if (cleaned.length() <= maxLength) return cleaned;
        if (maxLength <= 1) return "…";
        int end = maxLength - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))
                && Character.isLowSurrogate(cleaned.charAt(end))) {
            end--;
        }
        return cleaned.substring(0, end) + "…";
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
