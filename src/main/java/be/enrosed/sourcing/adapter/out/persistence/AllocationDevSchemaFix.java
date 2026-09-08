package be.enrosed.sourcing.adapter.out.persistence;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * The developer's H2 file created the allocation columns as native ENUM
 * types, and schema update never widens them: the hand-made split
 * (MANUAL) would be refused there. Once, in dev only, the four columns
 * become plain varchar. PostgreSQL gets the same through its migration file.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class AllocationDevSchemaFix {

    private static final Logger LOG = Logger.getLogger(AllocationDevSchemaFix.class);

    @Inject
    EntityManager entities;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        for (String column : new String[] {"allocFreight", "allocOrigin", "allocDestination", "allocExtra", "alloc_separate", "paymentTerms"}) {
            try {
                entities.createNativeQuery("alter table purchase_order alter column " + column + " varchar(40)").executeUpdate();
            } catch (RuntimeException failure) {
                LOG.debugf("allocation column %s left as is: %s", column, failure.getMessage());
            }
        }
    }
}
