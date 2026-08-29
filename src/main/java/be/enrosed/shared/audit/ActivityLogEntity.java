package be.enrosed.shared.audit;

import be.enrosed.shared.security.ActorRef;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** One immutable operational fact. No credentials or complete request payloads belong here. */
@Entity
@Table(name = "activity_log", indexes = {
        @Index(name = "idx_activity_log_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_activity_log_actor", columnList = "actor_username"),
        @Index(name = "idx_activity_log_entity", columnList = "entity_type, entity_id")
})
public class ActivityLogEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(name = "actor_username", nullable = false, length = 64)
    public String actorUsername;

    @Column(name = "actor_display_name", nullable = false, length = 100)
    public String actorDisplayName;

    @Column(nullable = false, length = 64)
    public String action;

    @Column(name = "entity_type", nullable = false, length = 64)
    public String entityType;

    @Column(name = "entity_id", length = 100)
    public String entityId;

    @Column(name = "entity_label", length = 255)
    public String entityLabel;

    @Column(nullable = false, length = 500)
    public String summary;

    @Column(name = "changes_json", length = 16000)
    public String changesJson;

    ActivityDto toDto(java.util.List<ActivityChangeDto> changes) {
        return new ActivityDto(id, occurredAt,
                new ActorRef(actorUsername, actorDisplayName), action, entityType,
                entityId, entityLabel, summary, ActivityCategory.forEntityType(entityType), changes);
    }
}
