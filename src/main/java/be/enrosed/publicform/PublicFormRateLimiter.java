package be.enrosed.publicform;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class PublicFormRateLimiter {
    private final PublicFormHasher hasher;
    private final PublicFormLockService locks;

    public PublicFormRateLimiter(PublicFormHasher hasher, PublicFormLockService locks) {
        this.hasher = hasher;
        this.locks = locks;
    }

    /** Attempts are committed independently, even when later form validation fails. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void checkIp(PublicFormAction action, String clientIdentity) {
        Instant now = Instant.now();
        long window = action.windowSeconds();
        Instant startedAt = Instant.ofEpochSecond((now.getEpochSecond() / window) * window);
        Instant resetAt = startedAt.plusSeconds(window);
        checkBuckets(List.of(bucket(action, "IP",
                clientIdentity == null ? "unavailable" : clientIdentity,
                action.ipLimit(), startedAt)), now, resetAt);
    }

    OptionalEmailAttempt emailAttempt(PublicFormAction action, String email, Instant now) {
        if (action.emailLimit() <= 0 || email == null || email.isBlank()) {
            return OptionalEmailAttempt.empty();
        }
        long window = action.windowSeconds();
        Instant startedAt = Instant.ofEpochSecond((now.getEpochSecond() / window) * window);
        Instant resetAt = startedAt.plusSeconds(window);
        return new OptionalEmailAttempt(bucket(action, "EMAIL",
                email.strip().toLowerCase(Locale.ROOT), action.emailLimit(), startedAt),
                resetAt);
    }

    /** Caller owns the bucket stripe lock and the surrounding business transaction. */
    void consumeLocked(OptionalEmailAttempt attempt, Instant now) {
        if (attempt.isEmpty()) return;
        checkBucketsLocked(List.of(attempt.bucket()), now, attempt.resetAt());
    }

    private void checkBuckets(List<Bucket> requested, Instant now, Instant resetAt) {
        locks.lock(requested.stream().map(Bucket::stripe).toList());
        checkBucketsLocked(requested, now, resetAt);
    }

    private void checkBucketsLocked(List<Bucket> requested, Instant now, Instant resetAt) {
        long retryAfter = 0;
        for (Bucket bucket : requested) {
            PublicFormRateBucketEntity row = PublicFormRateBucketEntity.findById(bucket.id());
            int count = row == null ? 0 : row.requestCount;
            if (count >= bucket.limit()) {
                retryAfter = Math.max(retryAfter,
                        Math.max(1, resetAt.getEpochSecond() - now.getEpochSecond()));
            }
        }
        if (retryAfter > 0) throw new PublicFormRateLimitException(retryAfter);
        for (Bucket bucket : requested) {
            PublicFormRateBucketEntity row = PublicFormRateBucketEntity.findById(bucket.id());
            if (row == null) {
                row = new PublicFormRateBucketEntity();
                row.id = bucket.id();
                row.action = bucket.action();
                row.keyType = bucket.keyType();
                row.keyHash = bucket.keyHash();
                row.windowStartedAt = bucket.startedAt();
                row.requestCount = 1;
                row.expiresAt = resetAt;
                row.persist();
            } else {
                row.requestCount++;
            }
        }
    }

    @Scheduled(every = "${enrosed.public-forms.cleanup.every:1h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        PublicFormRateBucketEntity.delete("expiresAt < ?1", Instant.now());
        PublicFormSubmissionEntity.delete("expiresAt < ?1", Instant.now());
    }

    private Bucket bucket(PublicFormAction action, String keyType, String value, int limit,
                          Instant startedAt) {
        String keyHash = hasher.hash(action.name() + ":" + keyType, value);
        String id = hasher.hash("rate-bucket",
                action.name() + "|" + keyType + "|" + keyHash + "|" + startedAt.getEpochSecond());
        return new Bucket(id, action.name(), keyType, keyHash, limit, startedAt,
                PublicFormLockService.stripe(id));
    }

    private record Bucket(String id, String action, String keyType, String keyHash,
                          int limit, Instant startedAt, int stripe) {}

    static final class OptionalEmailAttempt {
        private final Bucket bucket;
        private final Instant resetAt;

        private OptionalEmailAttempt(Bucket bucket, Instant resetAt) {
            this.bucket = bucket;
            this.resetAt = resetAt;
        }

        static OptionalEmailAttempt empty() {
            return new OptionalEmailAttempt(null, null);
        }

        boolean isEmpty() {
            return bucket == null;
        }

        int stripe() {
            return bucket == null ? -1 : bucket.stripe();
        }

        private Bucket bucket() {
            return bucket;
        }

        private Instant resetAt() {
            return resetAt;
        }
    }
}
