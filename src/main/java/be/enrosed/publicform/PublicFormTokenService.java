package be.enrosed.publicform;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Server-minted form-age proof; client supplied timestamps are deliberately not trusted. */
@ApplicationScoped
public class PublicFormTokenService {
    static final Duration MINIMUM_AGE = Duration.ofSeconds(3);
    static final Duration MAXIMUM_AGE = Duration.ofHours(2);

    private final PublicFormHasher hasher;
    private final Clock clock;

    @Inject
    public PublicFormTokenService(PublicFormHasher hasher) {
        this(hasher, Clock.systemUTC());
    }

    PublicFormTokenService(PublicFormHasher hasher, Clock clock) {
        this.hasher = hasher;
        this.clock = clock;
    }

    public Configuration issue(PublicFormPurpose purpose, String challengeSiteKey) {
        Instant now = clock.instant();
        String payload = String.join("|", "v1", purpose.name(),
                Long.toString(now.getEpochSecond()), UUID.randomUUID().toString());
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String token = encodedPayload + "." + encode(hasher.signature(encodedPayload));
        return new Configuration(purpose, token, now.plus(MINIMUM_AGE),
                now.plus(MAXIMUM_AGE), blankToNull(challengeSiteKey));
    }

    public void verify(String token, PublicFormPurpose expectedPurpose) {
        if (token == null || token.isBlank()) {
            throw invalid("REQUIRED");
        }
        String[] tokenParts = token.split("\\.", -1);
        if (tokenParts.length != 2 || token.length() > 1_024) throw invalid("INVALID");
        byte[] signature;
        String payload;
        try {
            signature = Base64.getUrlDecoder().decode(tokenParts[1]);
            if (!hasher.signatureMatches(tokenParts[0], signature)) throw invalid("INVALID");
            payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid("INVALID");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 4 || !"v1".equals(fields[0])
                || !expectedPurpose.name().equals(fields[1])) throw invalid("INVALID");
        Instant issuedAt;
        try {
            issuedAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
            UUID.fromString(fields[3]);
        } catch (RuntimeException exception) {
            throw invalid("INVALID");
        }
        Instant now = clock.instant();
        if (now.isBefore(issuedAt.plus(MINIMUM_AGE))) throw invalid("TOO_FAST");
        if (now.isAfter(issuedAt.plus(MAXIMUM_AGE))) throw invalid("EXPIRED");
        if (issuedAt.isAfter(now.plusSeconds(30))) throw invalid("INVALID");
    }

    private static PublicFormValidationException invalid(String code) {
        return new PublicFormValidationException(Map.of("formToken", code));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record Configuration(
            PublicFormPurpose purpose,
            String formToken,
            Instant minimumSubmitAt,
            Instant expiresAt,
            String challengeSiteKey
    ) {}
}
