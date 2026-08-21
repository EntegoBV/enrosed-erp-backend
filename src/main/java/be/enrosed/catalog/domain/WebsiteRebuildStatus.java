package be.enrosed.catalog.domain;

/** Dashboard-visible states; accepting a deploy hook is deliberately not reported as live. */
public enum WebsiteRebuildStatus {
    NOT_CONFIGURED,
    QUEUED,
    TRIGGERED,
    LIVE,
    FAILED_OR_STALE
}
