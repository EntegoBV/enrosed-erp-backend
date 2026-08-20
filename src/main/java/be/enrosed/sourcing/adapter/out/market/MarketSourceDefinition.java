package be.enrosed.sourcing.adapter.out.market;

/** Immutable provenance carried from provider to API. */
public record MarketSourceDefinition(
        String code,
        String label,
        String scope,
        String metric,
        String referenceKind,
        String sourceName,
        String sourceUrl,
        String termsUrl
) {}
