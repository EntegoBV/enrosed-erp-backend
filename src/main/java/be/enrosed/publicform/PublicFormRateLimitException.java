package be.enrosed.publicform;

public class PublicFormRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public PublicFormRateLimitException(long retryAfterSeconds) {
        super("Too many public form requests");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
