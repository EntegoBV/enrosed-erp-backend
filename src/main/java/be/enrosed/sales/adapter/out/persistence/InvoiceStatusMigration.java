package be.enrosed.sales.adapter.out.persistence;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Databases that predate invoices carry a status column typed as an enum of
 * the original quote statuses; BETAALD does not fit in it. Schema update
 * never widens such a constraint, so this widens it once by hand: H2 gets a
 * plain varchar, PostgreSQL loses the generated check constraint. Each
 * statement is safe to re-run and simply skipped where it does not apply.
 */
@ApplicationScoped
public class InvoiceStatusMigration {

    private static final Logger LOG = Logger.getLogger(InvoiceStatusMigration.class);

    private final EntityManager entities;

    public InvoiceStatusMigration(EntityManager entities) {
        this.entities = entities;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        widen("alter table sales_order alter column status varchar(32)");
        widen("alter table sales_order drop constraint if exists sales_order_status_check");
        widen("alter table sales_order alter column freightPricingStrategy varchar(24)");
        widen("alter table sales_order drop constraint if exists sales_order_freightpricingstrategy_check");
        widen("alter table sales_order drop constraint if exists sales_order_freight_pricing_strategy_check");
    }

    private void widen(String sql) {
        try {
            entities.createNativeQuery(sql).executeUpdate();
        } catch (Exception ignored) {
            LOG.debugf("Statusmigratie niet van toepassing: %s", sql);
        }
    }
}
