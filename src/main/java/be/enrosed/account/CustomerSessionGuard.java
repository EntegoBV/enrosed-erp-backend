package be.enrosed.account;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The one door to everything a logged-in customer may see. It returns a valid session
 * or throws; it never answers "anonymous", so a caller cannot fall back by accident.
 */
@ApplicationScoped
public class CustomerSessionGuard {
    private static final String SCHEME = "Bearer ";

    public record CustomerSession(long sessionId, long accountId, long customerId, String email) {}

    private final CustomerAccountService accounts;

    public CustomerSessionGuard(CustomerAccountService accounts) {
        this.accounts = accounts;
    }

    public CustomerSession require(HttpServerRequest request) {
        String token = bearerToken(request);
        if (token == null) throw invalid();
        return accounts.resolveSession(token).orElseThrow(CustomerSessionGuard::invalid);
    }

    /** The session token of the request, or null when the header is not a customer bearer. */
    static String bearerToken(HttpServerRequest request) {
        String header = request == null ? null : request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) return null;
        String token = header.substring(SCHEME.length());
        /* The shape is checked before any database access. */
        return AccountTokens.isSessionToken(token) ? token : null;
    }

    private static CustomerUnauthorizedException invalid() {
        return new CustomerUnauthorizedException(CustomerUnauthorizedException.SESSION_INVALID);
    }
}
