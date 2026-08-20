package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.MarketSourceStateEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Database-backed once-per-UTC-day claim for provider checks.
 *
 * The conditional update is atomic across application nodes. On a source's
 * first check, the primary key makes concurrent inserts exclusive; the losing
 * transaction is caught by {@link MarketSourceTracker} and performs no HTTP
 * request. A separate transaction is intentional so such a collision cannot
 * poison an endpoint or scheduler transaction.
 */
@ApplicationScoped
class MarketSourceDailyClaim {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean claim(String code, Instant now) {
        Instant startOfToday = now.atZone(ZoneOffset.UTC)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        long updated = MarketSourceStateEntity.update(
                "lastCheckedAt = ?1 where code = ?2 "
                + "and (lastCheckedAt is null or lastCheckedAt < ?3)",
                now, code, startOfToday);
        if (updated == 1) return true;
        if (MarketSourceStateEntity.count("code = ?1", code) > 0) return false;

        MarketSourceStateEntity state = new MarketSourceStateEntity();
        state.code = code;
        state.lastCheckedAt = now;
        state.persistAndFlush();
        return true;
    }
}
