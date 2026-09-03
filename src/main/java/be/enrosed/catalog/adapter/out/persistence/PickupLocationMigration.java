package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

/**
 * Public pickup points arrived as two NOT NULL columns on stock_location.
 *
 * Schema update cannot add a NOT NULL column without a default to a table
 * that already holds rows: H2 and PostgreSQL both refuse, Hibernate logs a
 * warning and carries on, and the first query then fails on the missing
 * column. This adds the two control columns with their defaults, so an
 * existing database - the developer's local H2 file included - heals itself
 * at start-up. Every statement is rerunnable and runs in its own transaction.
 */
@ApplicationScoped
public class PickupLocationMigration {

    private static final Logger LOG = Logger.getLogger(PickupLocationMigration.class);

    private final EntityManager entities;

    public PickupLocationMigration(EntityManager entities) {
        this.entities = entities;
    }

    /* Other start-up observers read stock_location; the columns must exist before they run. */
    void onStart(@Observes @Priority(1) StartupEvent event) {
        apply("alter table stock_location add column if not exists"
                + " public_pickup_point boolean default false not null");
        apply("alter table stock_location add column if not exists"
                + " public_pickup_position integer default 0 not null");
        apply("update stock_location set public_pickup_point = false where public_pickup_point is null");
        apply("update stock_location set public_pickup_position = 0 where public_pickup_position is null");
    }

    private void apply(String sql) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    entities.createNativeQuery(sql).executeUpdate());
            LOG.debugf("Afhaalpuntmigratie uitgevoerd: %s", sql);
        } catch (Exception ignored) {
            LOG.debugf("Afhaalpuntmigratie niet van toepassing: %s", sql);
        }
    }
}
