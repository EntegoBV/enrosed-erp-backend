package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.shared.security.ActorRef;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves the concept documents that counted their pallets MIXED to PACKED, once for the database, ten
 * minutes after the start (after a failure it tries again every ten minutes).
 *
 * Emre, 2026-11-25: the pallets are loaded one after the other, each as full as it goes, and "Ja,
 * ook telling en vracht". New documents are made PACKED (PalletCountRule.forNew). Of what exists,
 * only an untouched concept quote moves (PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE: never
 * sent, nothing made from it): its count and freight can only go down (ENR-2026-0031 and
 * ENR-2026-0034 on the day). Everything else keeps MIXED: a reopened or resent quote, a concept
 * invoice, credit note or split part (each counts like its source), and everything sent,
 * accepted, invoiced or paid keeps the pallets and the freight it went out with.
 *
 * The old release reads PACKED as STRICT (PalletCountRule.stored), so this waits until only this
 * release serves. It runs once: a row in country_policy_version says so. Each moved document gets one
 * step in its own history; the server log names what moved.
 */
@ApplicationScoped
public class PalletPackedRollout {

    private static final Logger LOG = Logger.getLogger(PalletPackedRollout.class);

    /** The country_policy_version row that says this move ran on the database. */
    static final String ROLLOUT = "2026-11-25-pallet-count-packed";

    static final String CANDIDATE = "o.palletcountrule = 'MIXED' and " + PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE;

    private final AtomicBoolean done = new AtomicBoolean();

    @Inject
    EntityManager entities;

    @Inject
    SalesRepositories.Events events;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "h2")
    String dbKind;

    @Scheduled(identity = "pallet-count-packed", every = "${enrosed.pallet-packed.rollout-every:10m}",
            delayed = "${enrosed.pallet-packed.rollout-after:10m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void move() {
        if (done.get()) return;
        try {
            List<String> moved = QuarkusTransaction.requiringNew().call(() -> {
                Number ran = (Number) entities.createNativeQuery("select count(*) from country_policy_version where version = :version")
                        .setParameter("version", ROLLOUT).getSingleResult();
                if (ran.intValue() > 0) return null;
                /* The old release may still hold a document while this one starts: wait a while, not forever. */
                if ("postgresql".equalsIgnoreCase(dbKind) || "pgsql".equalsIgnoreCase(dbKind))
                    entities.createNativeQuery("set local lock_timeout = '15s'").executeUpdate();
                /* Lock the candidates first, then ask again: a quote that was just sent or made into another
                   document no longer passes and keeps MIXED. */
                List<Long> locked = ids(entities.createNativeQuery("select o.id from sales_order o where " + CANDIDATE + " for update")
                        .getResultList());
                List<Long> still = locked.isEmpty() ? List.of()
                        : ids(entities.createNativeQuery("select o.id from sales_order o where o.id in (:ids) and " + CANDIDATE + " order by o.id")
                                .setParameter("ids", locked).getResultList());
                List<String> names = new ArrayList<>();
                if (!still.isEmpty()) {
                    entities.createNativeQuery("update sales_order set palletcountrule = 'PACKED' where id in (:ids)")
                            .setParameter("ids", still).executeUpdate();
                    Instant now = Instant.now();
                    for (Long id : still) events.add(new QuoteEvent(null, id, QuoteEvent.Type.VRACHT_INGEVULD, now,
                            ActorRef.SYSTEM.displayName(), false, "Pallets zo vol mogelijk geteld (eerst de eerste vol, kantelen mag)", null));
                    for (Object row : entities.createNativeQuery("select o.id, o.number from sales_order o where o.id in (:ids) order by o.id")
                            .setParameter("ids", still).getResultList()) {
                        Object[] cells = (Object[]) row;
                        names.add(cells[0] + " " + cells[1]);
                    }
                }
                entities.createNativeQuery("insert into country_policy_version (version, appliedat) values (:version, current_timestamp)")
                        .setParameter("version", ROLLOUT).executeUpdate();
                return names;
            });
            done.set(true);
            if (moved == null) return;
            LOG.infof("Pallet count: %d concept document(s) now load their pallets as full as they go (PACKED): %s", moved.size(), moved);
        } catch (RuntimeException failure) {
            /* Nothing is lost: those documents keep counting MIXED, and the next run tries again. */
            LOG.errorf(failure, "Pallet count not moved to PACKED; the concepts keep counting MIXED until the next run");
        }
    }

    private static List<Long> ids(List<?> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) ids.add(((Number) row).longValue());
        return ids;
    }
}
