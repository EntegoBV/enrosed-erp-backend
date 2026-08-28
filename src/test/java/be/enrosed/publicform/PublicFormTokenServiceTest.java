package be.enrosed.publicform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicFormTokenServiceTest {
    private static final Instant START = Instant.parse("2026-08-28T12:00:00Z");
    private final PublicFormHasher hasher = new PublicFormHasher(
            "unit-test-public-form-secret-123456");

    @Test
    void signedTokenEnforcesPurposeMinimumAgeAndExpiry() {
        String token = service(Duration.ZERO).issue(PublicFormPurpose.CONTACT, "site-key").formToken();

        assertEquals("TOO_FAST", error(service(Duration.ofSeconds(2)), token,
                PublicFormPurpose.CONTACT));
        assertDoesNotThrow(() -> service(Duration.ofSeconds(3))
                .verify(token, PublicFormPurpose.CONTACT));
        assertEquals("INVALID", error(service(Duration.ofSeconds(3)), token,
                PublicFormPurpose.QUOTE));
        assertEquals("EXPIRED", error(service(Duration.ofHours(2).plusSeconds(1)), token,
                PublicFormPurpose.CONTACT));
    }

    @Test
    void tamperingIsRejected() {
        String token = service(Duration.ZERO).issue(PublicFormPurpose.QUOTE, null).formToken();
        assertEquals("INVALID", error(service(Duration.ofSeconds(3)), token + "x",
                PublicFormPurpose.QUOTE));
    }

    private PublicFormTokenService service(Duration offset) {
        return new PublicFormTokenService(hasher,
                Clock.fixed(START.plus(offset), ZoneOffset.UTC));
    }

    private static String error(PublicFormTokenService service, String token,
                                PublicFormPurpose purpose) {
        PublicFormValidationException exception = assertThrows(
                PublicFormValidationException.class, () -> service.verify(token, purpose));
        return exception.fieldErrors().get("formToken");
    }
}
