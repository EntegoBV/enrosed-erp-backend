package be.enrosed.sales.application;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Gives every document without a pallet count rule its rule, ten minutes after the start and every
 * ten minutes after that: MIXED to a concept quote nothing has come from yet, STRICT to any other.
 * A new document is always made with its rule (Orders.create), so only rows from before the rule
 * existed are ever without one: the concept quotes the migration
 * (docs/migrations/2026-11-04/sales-order-pallet-count-rule-postgresql.sql) leaves to this job, what
 * the old release made or sent while the deploy ran (a quote it sent then is strict by now), and
 * every row when the migration was not listed. Until then a row without a rule counts strict.
 */
@ApplicationScoped
public class PalletCountRuleBackfill {

    private static final Logger LOG = Logger.getLogger(PalletCountRuleBackfill.class);

    /**
     * A concept quote nothing has come from yet: no document type or OFFERTE, status CONCEPT, not
     * deleted, never sent, no website order, no advance invoice, no invoice or other document made
     * from it, no delivery split, no partner advance snapshot. The migration's test, word for word:
     * keep the two alike. Plain SQL that PostgreSQL and the developer's H2 file both read.
     * PalletHeightBackfill moves such quotes to the standard pallet height with the same test.
     */
    static final String UNTOUCHED_CONCEPT_QUOTE = "(o.doctype is null or o.doctype = 'OFFERTE') and o.status = 'CONCEPT'"
            + " and o.deleted_at is null and o.sentat is null"
            + " and not exists (select 1 from sales_web_order w where w.sales_order_id = o.id)"
            + " and not exists (select 1 from sales_advance_billing b where b.quote_id = o.id)"
            + " and not exists (select 1 from sales_order i where i.sourcequoteid = o.id)"
            + " and not exists (select 1 from sales_split_group g where g.root_order_id = o.id or g.later_order_id = o.id)"
            + " and not exists (select 1 from partner_advance_quote_snapshot p where p.sales_order_id = o.id)";

    @Inject
    EntityManager entities;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "h2")
    String dbKind;

    /* Not at start-up: Railway keeps the old deployment serving until this one passes its health check
       (healthcheckTimeout 300 s), and the old release sends, invoices and advances quotes without knowing
       the rule. Ten minutes after the start only this release serves; until then a row without a rule
       reads STRICT, as the old release bills it. Only rows without a rule are touched, so every run after
       the first changes nothing. */
    @Scheduled(identity = "pallet-count-rule", every = "${enrosed.pallet-rule.decide-every:10m}",
            delayed = "${enrosed.pallet-rule.decide-after:10m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void decide() {
        try {
            int[] given = QuarkusTransaction.requiringNew().call(() -> {
                /* The old release may still hold a document while this one starts: wait a while, not forever. */
                if ("postgresql".equalsIgnoreCase(dbKind) || "pgsql".equalsIgnoreCase(dbKind))
                    entities.createNativeQuery("set local lock_timeout = '15s'").executeUpdate();
                int mixed = entities.createNativeQuery("update sales_order o set palletcountrule = 'MIXED'"
                        + " where o.palletcountrule is null and " + UNTOUCHED_CONCEPT_QUOTE).executeUpdate();
                int strict = entities.createNativeQuery(
                        "update sales_order set palletcountrule = 'STRICT' where palletcountrule is null").executeUpdate();
                return new int[] {mixed, strict};
            });
            if (given[0] + given[1] > 0)
                LOG.infof("Pallet count rule given: %d concept quote(s) MIXED, %d other document(s) STRICT", given[0], given[1]);
        } catch (RuntimeException failure) {
            /* Nothing is lost: those rows keep counting strict, and the next run tries again. */
            LOG.errorf(failure, "Pallet count rule not given; documents without one keep counting strict until the next run");
        }
    }
}
