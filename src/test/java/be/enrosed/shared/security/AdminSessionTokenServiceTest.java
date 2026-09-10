package be.enrosed.shared.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSessionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void tokenIsBoundToCanonicalUserAndExpiry() {
        AdminSessionTokenService service = service();
        var issued = service.issueAt("EmRe", NOW);

        assertTrue(service.verifyAt("emre", issued.token(), NOW.plusSeconds(1)));
        assertFalse(service.verifyAt("berat", issued.token(), NOW.plusSeconds(1)));
        assertFalse(service.verifyAt("emre", issued.token(), issued.expiresAt()));
    }

    @Test
    void tamperingAndSigningSecretChangesInvalidateToken() {
        AdminSessionTokenService service = service();
        String token = service.issueAt("emre", NOW).token();
        String[] parts = token.split("\\.", -1);
        byte[] signature = Base64.getUrlDecoder().decode(parts[3]);
        // Changing the last base64 character can affect unused bits only; change a real signature byte.
        signature[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        String tampered = String.join(".", parts);

        assertFalse(service.verifyAt("emre", tampered, NOW.plusSeconds(1)));
        parts = token.split("\\.", -1);
        parts[1] = Long.toString(Long.parseLong(parts[1]) + 60);
        assertFalse(service.verifyAt("emre", String.join(".", parts), NOW.plusSeconds(1)),
                "changing the signed expiry must fail even while the original token is valid");
        service.configuredSessionSecret = "a-new-secret";
        assertFalse(service.verifyAt("emre", token, NOW.plusSeconds(1)));
    }

    @Test
    void tokensUseFreshNoncesAndTtlIsBounded() {
        AdminSessionTokenService service = service();
        assertNotEquals(service.issueAt("emre", NOW).token(), service.issueAt("emre", NOW).token());

        service.sessionTtlDays = 0;
        assertThrows(IllegalStateException.class, () -> service.issueAt("emre", NOW));
        service.sessionTtlDays = 366;
        assertThrows(IllegalStateException.class, () -> service.issueAt("emre", NOW));
    }

    @Test
    void explicitFallbackSentinelUsesThePasswordHashAsSigningMaterial() {
        AdminSessionTokenService service = service();
        service.configuredSessionSecret = "use-password-hash";
        String token = service.issueAt("emre", NOW).token();

        assertTrue(service.verifyAt("emre", token, NOW.plusSeconds(1)));
        service.adminPasswordHash = "rotated-password-hash";
        assertFalse(service.verifyAt("emre", token, NOW.plusSeconds(1)));
    }

    private static AdminSessionTokenService service() {
        AdminSessionTokenService service = new AdminSessionTokenService();
        service.adminPasswordHash = "fallback-hash";
        service.configuredSessionSecret = "test-session-secret";
        service.sessionTtlDays = 90;
        return service;
    }
}
