package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.FreightRateEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.MarketSourceStateEntity;
import be.enrosed.sourcing.domain.MarketSourceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Daily attempt throttle plus persistent cache/failure bookkeeping.
 *
 * A source may publish weekly while the ERP is opened daily. We therefore
 * check at most once per UTC calendar day and deduplicate observations by
 * provider publication date. A failed check only updates this state row;
 * cached observations remain untouched.
 */
@ApplicationScoped
public class MarketSourceTracker {

    private static final Logger LOG = Logger.getLogger(MarketSourceTracker.class);

    private final Clock clock;

    @Inject
    MarketSourceDailyClaim dailyClaim;

    public MarketSourceTracker() {
        this(Clock.systemUTC());
    }

    MarketSourceTracker(Clock clock) {
        this.clock = clock;
    }

    static boolean dailyCheckDue(Instant lastCheckedAt, Instant now) {
        if (lastCheckedAt == null) return true;
        return lastCheckedAt.atZone(ZoneOffset.UTC).toLocalDate()
                .isBefore(now.atZone(ZoneOffset.UTC).toLocalDate());
    }

    static boolean weeklyObservationDue(LocalDate lastStoredOn, LocalDate candidateOn) {
        return lastStoredOn == null || lastStoredOn.isBefore(candidateOn.minusDays(6));
    }

    public boolean beginDailyCheck(String code) {
        Instant now = clock.instant();
        try {
            return dailyClaim.claim(code, now);
        } catch (RuntimeException exception) {
            /* A duplicate insert means another application node won the
               first-ever claim. Any other database failure must also stop
               before a licensed provider is contacted: fail closed. */
            LOG.debugf("Market source %s daily claim skipped: %s", code, exception.toString());
            return false;
        }
    }

    @Transactional
    public void store(String code, LocalDate publishedOn, BigDecimal value) {
        if (publishedOn == null || value == null || value.signum() <= 0) return;
        long duplicate = FreightRateEntity.count(
                "route = ?1 and quotedOn = ?2", code, publishedOn);
        if (duplicate > 0) return;
        FreightRateEntity observation = new FreightRateEntity();
        observation.route = code;
        observation.quotedOn = publishedOn;
        /* Historical column name retained for a safe schema migration. The
           source definition tells clients whether this value is USD or points. */
        observation.usdPerContainer = value;
        observation.persist();
    }

    /** For a provider page that exposes a value but not its publication date. */
    @Transactional
    public void storeAtMostWeekly(String code, LocalDate observedOn, BigDecimal value) {
        FreightRateEntity latest = FreightRateEntity
                .<FreightRateEntity>find(
                        "route = ?1 order by quotedOn desc, id desc", code)
                .firstResult();
        if (!weeklyObservationDue(latest == null ? null : latest.quotedOn, observedOn)) return;
        store(code, observedOn, value);
    }

    @Transactional
    public void success(String code) {
        MarketSourceStateEntity state = requiredState(code);
        state.lastSuccessfulAt = clock.instant();
        state.lastError = null;
    }

    @Transactional
    public void failure(String code, Exception exception) {
        MarketSourceStateEntity state = requiredState(code);
        String message = exception.getClass().getSimpleName();
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message += ": " + exception.getMessage();
        }
        state.lastError = message.substring(0, Math.min(message.length(), 500));
    }

    @Transactional
    public MarketSourceStatus status(MarketSourceDefinition source, boolean authorized) {
        MarketSourceStateEntity state = MarketSourceStateEntity.findById(source.code());
        FreightRateEntity latest = FreightRateEntity
                .<FreightRateEntity>find(
                        "route = ?1 order by quotedOn desc, id desc", source.code())
                .firstResult();

        String health;
        String detail;
        if (!authorized) {
            health = "LICENSE_REQUIRED";
            detail = latest == null
                    ? "Automatische controle staat uit tot provider-toestemming is vastgelegd."
                    : "Cache zichtbaar; automatische controle staat uit tot provider-toestemming is vastgelegd.";
        } else if (state != null && state.lastError != null) {
            health = latest == null ? "FAILED" : "CACHE_AFTER_FAILURE";
            detail = latest == null
                    ? "De laatste broncontrole mislukte; er is nog geen cache."
                    : "De laatste broncontrole mislukte; de laatst geldige cache blijft zichtbaar.";
        } else if (latest == null) {
            health = "NO_DATA";
            detail = "Nog geen geldige publicatie ontvangen.";
        } else if (latest.quotedOn.isBefore(LocalDate.now(clock).minusDays(10))) {
            health = "STALE";
            detail = "De laatste publicatie is ouder dan tien dagen; controleer de bron of feestdagen.";
        } else {
            health = "CURRENT";
            detail = "Laatste officiële publicatie uit de lokale cache.";
        }

        return new MarketSourceStatus(
                source.code(), source.label(), source.scope(), source.metric(),
                source.referenceKind(), source.sourceName(), source.sourceUrl(),
                source.termsUrl(), authorized, health, detail,
                state == null ? null : state.lastCheckedAt,
                state == null ? null : state.lastSuccessfulAt,
                latest == null ? null : latest.quotedOn,
                latest == null ? null : latest.usdPerContainer);
    }

    private MarketSourceStateEntity requiredState(String code) {
        MarketSourceStateEntity state = MarketSourceStateEntity.findById(code);
        if (state == null) {
            state = new MarketSourceStateEntity();
            state.code = code;
            state.lastCheckedAt = clock.instant();
            state.persist();
        }
        return state;
    }
}
