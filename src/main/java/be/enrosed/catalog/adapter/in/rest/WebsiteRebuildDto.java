package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.WebsiteRebuildStatus;

import java.time.Instant;

public record WebsiteRebuildDto(
        WebsiteRebuildStatus status,
        Instant queuedAt,
        Instant lastAttemptAt,
        Instant hookAcceptedAt,
        Instant liveAt,
        Instant nextAttemptAt,
        String currentRevision,
        String liveRevision,
        String lastError
) {}
