package be.enrosed.sales.adapter.out.persistence;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

/**
 * Databases that predate invoices and staffel freight carry enum-typed or
 * check-constrained status columns; BETAALD and CARRIER do not fit in them.
 * Schema update never widens such a constraint, so this widens it by hand.
 *
 * Every statement runs in its own transaction: H2 and PostgreSQL each accept
 * a different subset, and one failing dialect-specific statement must not
 * poison the ones that do apply. Each is safe to re-run.
 */
@ApplicationScoped
public class InvoiceStatusMigration {

    private static final Logger LOG = Logger.getLogger(InvoiceStatusMigration.class);

    private final EntityManager entities;

    public InvoiceStatusMigration(EntityManager entities) {
        this.entities = entities;
    }

    void onStart(@Observes StartupEvent event) {
        /* H2 spelling. */
        widen("alter table sales_order alter column status varchar(32)");
        widen("alter table sales_order alter column freightPricingStrategy varchar(24)");
        /* PostgreSQL spelling. */
        widen("alter table sales_order alter column status type varchar(32)");
        widen("alter table sales_order alter column freightpricingstrategy type varchar(24)");
        /* The generated check constraints, under every name Hibernate used. */
        widen("alter table sales_order drop constraint if exists sales_order_status_check");
        widen("alter table sales_order drop constraint if exists sales_order_freightpricingstrategy_check");
        widen("alter table sales_order drop constraint if exists sales_order_freight_pricing_strategy_check");
        widen("alter table sales_order drop constraint if exists sales_order_doctype_check");
        widen("alter table quote_event drop constraint if exists quote_event_type_check");
    }

    private void widen(String sql) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    entities.createNativeQuery(sql).executeUpdate());
            LOG.debugf("Statusmigratie uitgevoerd: %s", sql);
        } catch (Exception ignored) {
            LOG.debugf("Statusmigratie niet van toepassing: %s", sql);
        }
    }
}
