package be.enrosed.publicform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@ApplicationScoped
public class PublicFormIdempotencyService {
    private static final Duration EXPLICIT_TTL = Duration.ofHours(24);
    private static final Duration IMPLICIT_TTL = Duration.ofMinutes(2);
    private final PublicFormHasher hasher;
    private final PublicFormLockService locks;
    private final PublicFormRateLimiter rateLimiter;
    private final ObjectMapper json;

    public PublicFormIdempotencyService(PublicFormHasher hasher,
                                        PublicFormLockService locks,
                                        PublicFormRateLimiter rateLimiter,
                                        ObjectMapper json) {
        this.hasher = hasher;
        this.locks = locks;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    /** Successful replay is checked before a single-use Turnstile token is validated again. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> Optional<T> replay(PublicFormPurpose purpose, String key, String fingerprint,
                                  Class<T> responseType) {
        Resolved resolved = resolve(purpose, key, fingerprint);
        PublicFormSubmissionEntity row = PublicFormSubmissionEntity.findById(resolved.id());
        if (row == null || !row.expiresAt.isAfter(Instant.now())) return Optional.empty();
        requireSamePayload(row, resolved);
        return Optional.of(read(row.responseJson, responseType));
    }

    /** Idempotency row and business records commit or roll back together. */
    @Transactional
    public <T> T execute(PublicFormPurpose purpose, String key, String fingerprint,
                         Class<T> responseType, Supplier<T> action) {
        return executeAccepted(purpose, key, fingerprint, null, null, responseType, action);
    }

    /**
     * Rechecks the result and consumes the accepted e-mail attempt under one globally ordered
     * set of database lock stripes. Concurrent retries therefore create and count exactly once.
     */
    @Transactional
    public <T> T executeAccepted(PublicFormPurpose purpose, String key, String fingerprint,
                                 PublicFormAction rateAction, String email,
                                 Class<T> responseType, Supplier<T> action) {
        Resolved resolved = resolve(purpose, key, fingerprint);
        Instant now = Instant.now();
        PublicFormRateLimiter.OptionalEmailAttempt emailAttempt = rateAction == null
                ? PublicFormRateLimiter.OptionalEmailAttempt.empty()
                : rateLimiter.emailAttempt(rateAction, email, now);
        java.util.ArrayList<Integer> stripes = new java.util.ArrayList<>();
        stripes.add(PublicFormLockService.stripe(resolved.id()));
        if (!emailAttempt.isEmpty()) stripes.add(emailAttempt.stripe());
        locks.lock(stripes);
        PublicFormSubmissionEntity row = PublicFormSubmissionEntity.findById(resolved.id());
        if (row != null && row.expiresAt.isAfter(now)) {
            requireSamePayload(row, resolved);
            return read(row.responseJson, responseType);
        }
        rateLimiter.consumeLocked(emailAttempt, now);
        T response = action.get();
        boolean isNew = row == null;
        if (row == null) {
            row = new PublicFormSubmissionEntity();
            row.id = resolved.id();
        }
        row.purpose = purpose.name();
        row.payloadHash = resolved.payloadHash();
        row.responseJson = write(response);
        row.createdAt = now;
        row.expiresAt = now.plus(resolved.explicit() ? EXPLICIT_TTL : IMPLICIT_TTL);
        if (isNew) row.persist();
        return response;
    }

    private Resolved resolve(PublicFormPurpose purpose, String key, String fingerprint) {
        boolean explicit = key != null && !key.isBlank();
        String cleanedKey;
        if (explicit) {
            cleanedKey = key.strip();
            if (!cleanedKey.matches("[A-Za-z0-9._:-]{8,100}")) {
                throw new PublicFormValidationException(Map.of("idempotencyKey", "INVALID"));
            }
        } else {
            cleanedKey = "payload:" + hasher.hash("implicit-payload", fingerprint);
        }
        String payloadHash = hasher.hash("payload:" + purpose.name(), fingerprint);
        String id = hasher.hash("idempotency:" + purpose.name(), cleanedKey);
        return new Resolved(id, payloadHash, explicit);
    }

    private static void requireSamePayload(PublicFormSubmissionEntity row, Resolved resolved) {
        if (!row.payloadHash.equals(resolved.payloadHash())) {
            throw new PublicFormValidationException(Map.of("idempotencyKey", "CONFLICT"));
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored public form response is invalid", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Public form response could not be stored", exception);
        }
    }

    private record Resolved(String id, String payloadHash, boolean explicit) {}
}
