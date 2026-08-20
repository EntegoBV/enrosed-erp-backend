package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Provenance and health of one external freight benchmark.
 *
 * Values marked INDEX_POINTS are deliberately not freight prices. The
 * frontend uses the metric and reference kind to prevent an index from ever
 * being presented as a port-specific USD quote.
 */
public record MarketSourceStatus(
        String code,
        String label,
        String scope,
        String metric,
        String referenceKind,
        String sourceName,
        String sourceUrl,
        String termsUrl,
        boolean automatedAccessAuthorized,
        String state,
        String detail,
        Instant lastCheckedAt,
        Instant lastSuccessfulAt,
        LocalDate latestPublishedOn,
        BigDecimal latestValue
) {}
