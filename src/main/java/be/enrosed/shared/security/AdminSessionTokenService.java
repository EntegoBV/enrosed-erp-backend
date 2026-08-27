package be.enrosed.shared.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues signed, expiring staff session keys so the browser never has to keep
 * the shared staff password. A token is bound to one canonical username and
 * becomes invalid when the signing secret (or fallback password hash) changes.
 */
@ApplicationScoped
public class AdminSessionTokenService {

    private static final String VERSION = "enr1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    @ConfigProperty(name = "enrosed.admin.password-hash")
    String adminPasswordHash;

    @ConfigProperty(name = "enrosed.admin.session-secret", defaultValue = "")
    String configuredSessionSecret;

    @ConfigProperty(name = "enrosed.admin.session-ttl-days", defaultValue = "90")
    long sessionTtlDays;

    public IssuedSession issue(String username) {
        return issueAt(username, Instant.now());
    }

    public boolean isSessionToken(String value) {
        return value != null && value.startsWith(VERSION + ".");
    }

    public boolean verify(String username, String token) {
        return verifyAt(username, token, Instant.now());
    }

    IssuedSession issueAt(String username, Instant now) {
        String canonical = ActorRef.canonicalUsername(username);
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("Een sessie vereist een gebruikersnaam");
        }
        if (sessionTtlDays < 1 || sessionTtlDays > 365) {
            throw new IllegalStateException("Session TTL must be between 1 and 365 days");
        }

        Instant expiresAt = now.plus(Duration.ofDays(sessionTtlDays));
        String nonce = randomNonce();
        String signature = signature(canonical, expiresAt.getEpochSecond(), nonce);
        return new IssuedSession(
                String.join(".", VERSION, Long.toString(expiresAt.getEpochSecond()), nonce, signature),
                expiresAt);
    }

    boolean verifyAt(String username, String token, Instant now) {
        String canonical = ActorRef.canonicalUsername(username);
        if (canonical.isBlank() || token == null) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[2].isBlank() || parts[3].isBlank()) {
            return false;
        }

        final long expiresAt;
        final byte[] suppliedSignature;
        try {
            expiresAt = Long.parseLong(parts[1]);
            suppliedSignature = DECODER.decode(parts[3]);
        } catch (IllegalArgumentException invalidToken) {
            return false;
        }

        final byte[] expectedSignature;
        try {
            expectedSignature = DECODER.decode(signature(canonical, expiresAt, parts[2]));
        } catch (IllegalArgumentException invalidSignature) {
            return false;
        }

        return MessageDigest.isEqual(expectedSignature, suppliedSignature)
                && expiresAt > now.getEpochSecond();
    }

    private String randomNonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private String signature(String username, long expiresAt, String nonce) {
        String payload = String.join("\n", VERSION, username, Long.toString(expiresAt), nonce);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret(), HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign admin session", failure);
        }
    }

    private byte[] signingSecret() {
        String value = configuredSessionSecret == null || configuredSessionSecret.isBlank()
                ? adminPasswordHash
                : configuredSessionSecret;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Admin session signing secret is missing");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record IssuedSession(String token, Instant expiresAt) {}
}
