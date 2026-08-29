package be.enrosed.shared.audit;

import be.enrosed.shared.security.ActorRef;

import java.time.Instant;
import java.util.List;

/** Customer-safe, credential-free representation of one append-only activity. */
public record ActivityDto(
        Long id,
        Instant at,
        ActorRef actor,
        String action,
        String entityType,
        String entityId,
        String entityLabel,
        String summary,
        ActivityCategory category,
        List<ActivityChangeDto> changes
) {}
