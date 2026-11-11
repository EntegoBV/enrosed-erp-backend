package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.shared.security.ActorRef;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts the concept quotes nothing has come from yet back on the standard pallet height, once for the
 * database, ten minutes after the start (after a failure it tries again every ten minutes).
 *
 * From 2026-11-07 to 2026-11-10 the standard was 220 cm; Emre then chose 260 cm again. A document
 * keeps the height it was made with, so whatever was sent, invoiced, advanced, split or ordered on
 * the website on 220 keeps 220, and so does every invoice. Only an untouched concept quote
 * (PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE) that stores 220 without anyone having picked it
 * moves: the 220 it was made with, or was moved to on 2026-11-07, was the standard of those days. A
 * height set by hand stays, also on a Nieuwe kopie that inherited it: PalletHeightPicks reads that
 * from each quote's history, after the lock, so every untouched concept quote on 220 is locked and
 * asked. Hand-built pallets do not hold a quote back: they are billed as they stand and were
 * measured under 220.
 *
 * Both releases read a stored height the same way, so the move is safe while the old release still
 * serves; it waits anyway, so it also finds the concepts the old release made at 220 during the
 * deploy. It runs once: a row in country_policy_version says so, and no later start moves a quote
 * again behind a seller's back. Each moved quote gets one step in its own history, which is also the
 * list to undo from; the server log names what moved and what kept 220.
 */
@ApplicationScoped
public class PalletHeightReturn {

    private static final Logger LOG = Logger.getLogger(PalletHeightReturn.class);

    /** The country_policy_version row that says this move ran on the database. */
    static final String ROLLOUT = "2026-11-10-pallet-height-260";

    private static final String AT_FORMER = "o.maxpalletheightcm = :former";

    /** A concept quote nothing has come from, on 220 cm. Whether someone set that 220 is asked per quote. */
    private static final String CANDIDATE = AT_FORMER + " and " + PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE;

    private final AtomicBoolean done = new AtomicBoolean();

    @Inject
    EntityManager entities;

    @Inject
    SalesSettings settings;

    @Inject
    PalletHeightPicks picks;

    @Inject
    SalesRepositories.Events events;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "h2")
    String dbKind;

    /** What one run decided, each document as "id number" (the last list with its type and status). */
    private record Outcome(List<String> moved, List<String> picked, List<String> inherited, List<String> others) {}

    @Scheduled(identity = "pallet-height-260", every = "${enrosed.pallet-height.return-every:10m}",
            delayed = "${enrosed.pallet-height.return-after:10m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void move() {
        if (done.get()) return;
        BigDecimal standard = settings.standardMaxHeightCm();
        BigDecimal former = SalesSettings.FORMER_STANDARD_HEIGHT_CM;
        /* A standard configured at or under 220 cm: nothing to return to, and going down could leave a
           measured pallet taller than the height. */
        if (standard.compareTo(former) <= 0) {
            done.set(true);
            return;
        }
        try {
            Outcome outcome = QuarkusTransaction.requiringNew().call(() -> {
                Number ran = (Number) entities.createNativeQuery("select count(*) from country_policy_version where version = :version")
                        .setParameter("version", ROLLOUT).getSingleResult();
                if (ran.intValue() > 0) return null;
                /* The old release may still hold a document while this one starts: wait a while, not forever. */
                if ("postgresql".equalsIgnoreCase(dbKind) || "pgsql".equalsIgnoreCase(dbKind))
                    entities.createNativeQuery("set local lock_timeout = '15s'").executeUpdate();
                /* First lock the candidates: a quote a seller is saving right now is waited for. The second
                   select is a new statement and so reads what that save committed: a quote that was just
                   sent, got an advance or an invoice, or was given another height no longer passes and keeps
                   what it has. A save that comes later waits for this one and reads 260. */
                List<Long> locked = ids(candidate("select o.id from sales_order o where " + CANDIDATE + " for update"));
                List<Long> still = locked.isEmpty() ? List.<Long>of()
                        : ids(candidate("select o.id from sales_order o where o.id in (:ids) and " + CANDIDATE + " order by o.id")
                                .setParameter("ids", locked));
                /* Only a 220 nobody set moves. The history is read here, after the lock, so a height a
                   seller just saved counts. A 220 set on the quote itself stays, and so does one a Nieuwe
                   kopie inherited; a quote whose trail cannot be followed (a source that is gone, a log row
                   that cannot be read) keeps 220 too, since nobody watches this move. */
                List<Long> moved = new ArrayList<>();
                List<Long> picked = new ArrayList<>();
                List<Long> inherited = new ArrayList<>();
                for (Long id : still) {
                    switch (picks.of(id)) {
                        case NOT_PICKED: moved.add(id); break;
                        case SET_HERE: picked.add(id); break;
                        default: inherited.add(id);
                    }
                }
                if (!moved.isEmpty()) {
                    entities.createNativeQuery("update sales_order set maxpalletheightcm = :height where id in (:ids)")
                            .setParameter("height", standard).setParameter("ids", moved).executeUpdate();
                    /* The quote's own history says why its pallet count changed. The type is one no rule
                       reads (never OPGEMAAKT: its date is a website request's age); only the words show. */
                    String step = "Max. pallethoogte " + plain(former) + " → " + plain(standard) + " cm";
                    Instant now = Instant.now();
                    for (Long id : moved) events.add(new QuoteEvent(null, id, QuoteEvent.Type.VRACHT_INGEVULD, now,
                            ActorRef.SYSTEM.displayName(), false, step, null));
                }
                entities.createNativeQuery("insert into country_policy_version (version, appliedat) values (:version, current_timestamp)")
                        .setParameter("version", ROLLOUT).executeUpdate();
                /* Everything else that stays on 220: sent, invoiced, advanced, split, ordered, and every invoice. */
                List<String> others = named(entities.createNativeQuery("select o.id, o.number, coalesce(o.doctype, 'OFFERTE'), o.status"
                                + " from sales_order o where " + AT_FORMER + " and o.deleted_at is null and not ("
                                + PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE + ") order by o.id")
                        .setParameter("former", former));
                return new Outcome(named(byId(moved)), named(byId(picked)), named(byId(inherited)), others);
            });
            done.set(true);
            if (outcome == null) return;
            LOG.infof("Pallet height: %d concept quote(s) back on the standard %s cm: %s", outcome.moved().size(), plain(standard), outcome.moved());
            if (!outcome.picked().isEmpty())
                LOG.infof("Pallet height: %d concept quote(s) keep 220 cm, set by hand: %s", outcome.picked().size(), outcome.picked());
            if (!outcome.inherited().isEmpty())
                LOG.infof("Pallet height: %d concept quote(s) keep 220 cm, copied from a quote with 220 set by hand, or with a history that cannot be followed: %s",
                        outcome.inherited().size(), outcome.inherited());
            if (!outcome.others().isEmpty())
                LOG.infof("Pallet height: %d other document(s) keep the 220 cm they were made with: %s", outcome.others().size(), outcome.others());
        } catch (RuntimeException failure) {
            /* Nothing is lost: those concepts keep counting 220 cm, and the next run tries again. */
            LOG.errorf(failure, "Pallet height not moved; the concept quotes of 7-10 November keep 220 cm until the next run");
        }
    }

    private Query candidate(String sql) {
        return entities.createNativeQuery(sql).setParameter("former", SalesSettings.FORMER_STANDARD_HEIGHT_CM);
    }

    private Query byId(List<Long> ids) {
        /* An empty list is no valid "in": ask for no row instead. */
        return entities.createNativeQuery("select o.id, o.number from sales_order o where o.id in (:ids) order by o.id")
                .setParameter("ids", ids.isEmpty() ? List.of(-1L) : ids);
    }

    private static List<Long> ids(Query query) {
        return ((List<?>) query.getResultList()).stream().map(id -> ((Number) id).longValue()).toList();
    }

    /** Each row as its columns in one string: "77 ENR-2026-0020", "80 F-2026-0014 FACTUUR CONCEPT". */
    private static List<String> named(Query query) {
        return ((List<?>) query.getResultList()).stream().map(row -> {
            StringBuilder text = new StringBuilder();
            for (Object column : (Object[]) row) text.append(text.isEmpty() ? "" : " ").append(column);
            return text.toString();
        }).toList();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
