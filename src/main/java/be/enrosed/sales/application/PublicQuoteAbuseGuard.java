package be.enrosed.sales.application;

import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.SubmissionResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/** Small in-process safety net; edge/WAF limits can still be stricter in production. */
@ApplicationScoped
public class PublicQuoteAbuseGuard {
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, CachedSubmission> submissions = new HashMap<>();

    public synchronized void checkPreview(String remoteKey) {
        check("preview:" + hash(remoteKey), 60, Duration.ofMinutes(1));
    }

    public synchronized void checkSubmit(String remoteKey, String email) {
        /* Independent windows prevent rotating fake email addresses to evade the IP cap. */
        check("submit-ip:" + hash(remoteKey), 5, Duration.ofHours(1));
        check("submit-email:" + hash(email == null ? "" : email.trim().toLowerCase()),
                5, Duration.ofHours(1));
    }

    /**
     * Optional Idempotency-Key gets a 24-hour replay window. Without it, an
     * identical payload is suppressed briefly to absorb accidental double taps.
     * Only hashes and the customer-safe response stay in memory.
     */
    public synchronized SubmissionResponse submitOnce(String idempotencyKey, String payloadFingerprint,
                                                       Supplier<SubmissionResponse> action) {
        Instant now = Instant.now();
        submissions.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        boolean explicit = idempotencyKey != null && !idempotencyKey.isBlank();
        if (explicit && !idempotencyKey.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw new PublicQuoteValidationException(
                    Map.of("idempotencyKey", "INVALID"));
        }
        String payloadHash = hash(payloadFingerprint);
        String cacheKey = explicit ? hash("key:" + idempotencyKey)
                : hash("payload:" + payloadHash);
        CachedSubmission cached = submissions.get(cacheKey);
        if (cached != null) {
            if (explicit && !cached.payloadHash.equals(payloadHash)) {
                throw new PublicQuoteValidationException(
                        Map.of("idempotencyKey", "CONFLICT"));
            }
            return cached.response;
        }
        SubmissionResponse response = action.get();
        submissions.put(cacheKey, new CachedSubmission(payloadHash, response,
                now.plus(explicit ? Duration.ofHours(24) : Duration.ofMinutes(2))));
        return response;
    }

    private void check(String key, int limit, Duration duration) {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().getValue().resetAt.isAfter(now)) iterator.remove();
        }
        Window current = windows.get(key);
        if (current == null || !current.resetAt.isAfter(now)) {
            windows.put(key, new Window(1, now.plus(duration)));
            return;
        }
        if (current.count >= limit) {
            long retry = Math.max(1, Duration.between(now, current.resetAt).toSeconds());
            throw new PublicQuoteRateLimitException(retry);
        }
        windows.put(key, new Window(current.count + 1, current.resetAt));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Window(int count, Instant resetAt) {}
    private record CachedSubmission(String payloadHash, SubmissionResponse response,
                                    Instant expiresAt) {}
}
