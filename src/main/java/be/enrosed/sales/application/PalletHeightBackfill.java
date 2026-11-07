package be.enrosed.sales.application;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts the concept quotes nothing has come from yet on today's standard pallet height, once, ten
 * minutes after the start (after a failure it tries again every ten minutes).
 *
 * A document keeps the height it was made with (SalesSettings): a new one stores the standard, and
 * a row without a stored height was made while the standard was 260 cm, so it keeps reading 260 and
 * a sent quote, an invoice or a website order never changes. Only an untouched concept quote
 * (PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE) without hand-built pallets moves to the standard:
 * nothing of it has left the house, and no measured pallet can end up taller than the new height.
 * One with hand-built pallets gets its 260 written down, so no later run can pick it up once its
 * pallets are gone (review finding: it would be re-priced behind a seller's back). Both releases read
 * a stored height the same way, so the move is safe while the old release still serves; it waits
 * anyway, so it also finds the concepts the old release made during the deploy.
 *
 * The move belongs to the switch from 260 to 220 cm (2026-11-07): it runs once per start and not
 * after {@code enrosed.pallet-height.move-until}; after its first run no untouched concept is left
 * without a height, so later runs find nothing.
 */
@ApplicationScoped
public class PalletHeightBackfill {

    private static final Logger LOG = Logger.getLogger(PalletHeightBackfill.class);

    /** The quotes it decides: no stored height, untouched concept. */
    private static final String CANDIDATE = "o.maxpalletheightcm is null and " + PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE;
    private static final String HAND_PALLETS = "exists (select 1 from sales_pallet hand where hand.order_id = o.id)";

    private final AtomicBoolean done = new AtomicBoolean();

    @Inject
    EntityManager entities;

    @Inject
    SalesSettings settings;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "h2")
    String dbKind;

    @ConfigProperty(name = "enrosed.pallet-height.move-until", defaultValue = "2026-11-14")
    LocalDate moveUntil;

    @Scheduled(identity = "pallet-height-standard", every = "${enrosed.pallet-height.move-every:10m}",
            delayed = "${enrosed.pallet-height.move-after:10m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void move() {
        if (done.get()) return;
        if (LocalDate.now(ZoneId.of("Europe/Brussels")).isAfter(moveUntil)) {
            done.set(true);
            return;
        }
        try {
            List<List<Long>> decided = QuarkusTransaction.requiringNew().call(() -> {
                /* The old release may still hold a document while this one starts: wait a while, not forever. */
                if ("postgresql".equalsIgnoreCase(dbKind) || "pgsql".equalsIgnoreCase(dbKind))
                    entities.createNativeQuery("set local lock_timeout = '15s'").executeUpdate();
                /* First lock the candidates: a quote a seller is saving right now is waited for. The update is
                   a new statement and so reads what that save committed: a quote that just got hand pallets, an
                   advance or an invoice no longer passes the test and keeps its height. A save that comes
                   later waits for this one and reads the new height. */
                List<Long> ids = ids(entities.createNativeQuery("select o.id from sales_order o where " + CANDIDATE + " for update"));
                if (ids.isEmpty()) return List.of(List.<Long>of(), List.<Long>of());
                List<Long> moved = ids(entities.createNativeQuery("select o.id from sales_order o where o.id in (:ids) and "
                        + CANDIDATE + " and not " + HAND_PALLETS).setParameter("ids", ids));
                List<Long> kept = ids(entities.createNativeQuery("select o.id from sales_order o where o.id in (:ids) and "
                        + CANDIDATE + " and " + HAND_PALLETS).setParameter("ids", ids));
                set(moved, settings.standardMaxHeightCm());
                set(kept, SalesSettings.LEGACY_MAX_HEIGHT_CM);
                return List.of(moved, kept);
            });
            done.set(true);
            if (!decided.get(0).isEmpty())
                LOG.infof("Pallet height: %d concept quote(s) moved to the standard %s cm (ids %s)", decided.get(0).size(),
                        settings.standardMaxHeightCm().stripTrailingZeros().toPlainString(), decided.get(0));
            if (!decided.get(1).isEmpty())
                LOG.infof("Pallet height: %d concept quote(s) with hand-built pallets keep 260 cm, now stored (ids %s)",
                        decided.get(1).size(), decided.get(1));
        } catch (RuntimeException failure) {
            /* Nothing is lost: those concepts keep counting 260 cm, and the next run tries again. */
            LOG.errorf(failure, "Pallet height not moved; untouched concept quotes keep 260 cm until the next run");
        }
    }

    private static List<Long> ids(jakarta.persistence.Query query) {
        return ((List<?>) query.getResultList()).stream().map(id -> ((Number) id).longValue()).toList();
    }

    private void set(List<Long> ids, java.math.BigDecimal height) {
        if (ids.isEmpty()) return;
        entities.createNativeQuery("update sales_order set maxpalletheightcm = :height where id in (:ids)")
                .setParameter("height", height).setParameter("ids", ids).executeUpdate();
    }
}
