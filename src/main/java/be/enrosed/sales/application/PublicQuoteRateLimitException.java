package be.enrosed.sales.application;

public class PublicQuoteRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public PublicQuoteRateLimitException(long retryAfterSeconds) {
        super("Too many quote requests");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
