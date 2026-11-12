package be.enrosed.partnerportal.application;

/**
 * A partner password sign-in that was not checked at all: too many attempts from this network
 * (429) or no bcrypt slot free in time (503). Not a wrong password, so not a 401: the login page
 * then says it cannot sign in right now instead of blaming the password. Thrown from the identity
 * provider; Quarkus hands it to the REST layer's exception mappers
 * (adapter.in.rest.PartnerSignInUnavailableMapper).
 */
public class PartnerSignInUnavailableException extends RuntimeException {

    private final int status;
    private final long retryAfterSeconds;

    private PartnerSignInUnavailableException(int status, long retryAfterSeconds, String message) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static PartnerSignInUnavailableException limited(long retryAfterSeconds) {
        long seconds = Math.max(1, retryAfterSeconds);
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new PartnerSignInUnavailableException(429, seconds, "Te veel aanmeldpogingen; probeer het over "
                + minutes + (minutes == 1 ? " minuut" : " minuten") + " opnieuw");
    }

    public static PartnerSignInUnavailableException busy() {
        return new PartnerSignInUnavailableException(503, 5, "Even geduld: probeer het zo opnieuw");
    }

    public int status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
