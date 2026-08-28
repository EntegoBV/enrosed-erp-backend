package be.enrosed.publicform;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collection;

@ApplicationScoped
public class PublicFormLockService {
    @ConfigProperty(name = "enrosed.public-forms.require-preseeded-locks",
            defaultValue = "false")
    boolean requirePreseededLocks;

    /** H2/local safety; production migration pre-seeds the same fixed rows. */
    @Transactional
    void seed(@Observes StartupEvent ignored) {
        long count = PublicFormLockEntity.count();
        if (count == 0 && !requirePreseededLocks) {
            for (int id = 0; id < PublicFormLockEntity.STRIPES; id++) {
                new PublicFormLockEntity(id).persist();
            }
            PublicFormLockEntity.flush();
        }
        validateSeedRows();
    }

    void validateSeedRows() {
        long count = PublicFormLockEntity.count();
        long inRange = PublicFormLockEntity.count("id >= ?1 and id < ?2",
                0, PublicFormLockEntity.STRIPES);
        if (count != PublicFormLockEntity.STRIPES || inRange != PublicFormLockEntity.STRIPES) {
            throw new IllegalStateException("Public form lock migration is incomplete; apply "
                    + "docs/migrations/2026-08-28/public-form-intake-postgresql.sql "
                    + "to pre-seed exactly lock IDs 0..63 before starting or scaling the service");
        }
    }

    void lock(Collection<Integer> stripes) {
        stripes.stream().distinct().sorted().forEach(stripe -> {
            PublicFormLockEntity row = PublicFormLockEntity.findById(
                    stripe, LockModeType.PESSIMISTIC_WRITE);
            if (row == null) throw new IllegalStateException("Public form lock rows are not seeded");
        });
    }

    static int stripe(String hash) {
        return Integer.parseInt(hash.substring(0, 4), 16) % PublicFormLockEntity.STRIPES;
    }
}
