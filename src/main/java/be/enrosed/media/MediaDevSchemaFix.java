package be.enrosed.media;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * The developer's H2 file keeps its schema across restarts, and Hibernate's
 * schema update never widens the native ENUM column it once created for the
 * media target type. Every new target type would fail there until the column
 * is a plain varchar, which this does once, in dev only. PostgreSQL gets the
 * same change through its migration file.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class MediaDevSchemaFix {

    private static final Logger LOG = Logger.getLogger(MediaDevSchemaFix.class);

    @Inject
    EntityManager entities;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        for (String table : new String[] {"media_link", "media_legacy_source"}) {
            try {
                entities.createNativeQuery("alter table " + table + " alter column target_type varchar(30)").executeUpdate();
            } catch (RuntimeException failure) {
                LOG.debugf("media target type column on %s left as is: %s", table, failure.getMessage());
            }
        }
    }
}
