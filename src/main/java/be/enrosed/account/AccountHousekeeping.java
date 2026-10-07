package be.enrosed.account;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Retention without a scheduled job: callers run this after their own transaction has
 * returned, never from inside one and never from a GET.
 */
@ApplicationScoped
public class AccountHousekeeping {
    private static final Logger LOG = Logger.getLogger(AccountHousekeeping.class);
    private static final Duration TOKEN_GRACE = Duration.ofDays(30);
    private static final Duration UNDECIDED_OR_REJECTED = Duration.ofDays(180);
    private static final Duration APPROVED = Duration.ofDays(365);

    private Clock clock = Clock.systemUTC();

    /** Runs in a transaction of its own; a failure is logged and never reaches the caller. */
    public void purge() {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                Instant now = clock.instant();
                CustomerSessionEntity.delete("expiresAt < ?1", now);
                CustomerAccountTokenEntity.delete("expiresAt < ?1", now.minus(TOKEN_GRACE));
                CustomerLoginRequestEntity.delete("status = 'REJECTED' and decidedAt < ?1",
                        now.minus(UNDECIDED_OR_REJECTED));
                CustomerLoginRequestEntity.delete("status = 'APPROVED' and decidedAt < ?1",
                        now.minus(APPROVED));
                CustomerLoginRequestEntity.delete("status = 'PENDING' and createdAt < ?1",
                        now.minus(UNDECIDED_OR_REJECTED));
            });
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Opruimen van websitelogins en login-aanvragen mislukt");
        }
    }

    /** Tests pin the moment the retention rules are judged at. */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
