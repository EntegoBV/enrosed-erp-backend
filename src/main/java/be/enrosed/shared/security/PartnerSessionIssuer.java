package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;

import java.time.Instant;

/**
 * Issues the browser key of a partner login for {@code POST /api/auth/session}; implemented by
 * the partner portal, so the staff sign-in code does not depend on it.
 */
public interface PartnerSessionIssuer {

    /** The raw token leaves this record only towards the client. */
    record Issued(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * A new session for the partner behind this identity. Refused with 403 when the identity
     * itself came from a session key: only the password buys a new key.
     */
    Issued issue(SecurityIdentity identity);
}
