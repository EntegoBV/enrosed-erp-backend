package be.enrosed.finance.application;

import be.enrosed.finance.domain.Fair;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * The two fairs the company already stood on before fairs had a list:
 * present from the first start, inserted once, never touched again. The
 * PostgreSQL migration inserts the same two, so either path finds them.
 */
@ApplicationScoped
public class FairSeed {

    private static final Logger LOG = Logger.getLogger(FairSeed.class);

    private final Fairs fairs;

    public FairSeed(Fairs fairs) {
        this.fairs = fairs;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        seed("TICA", "TICA");
        seed("EDE", "Ede");
    }

    private void seed(String code, String label) {
        if (fairs.findByCode(code).isPresent()) return;
        fairs.save(new Fair(null, code, label, true, Instant.now()));
        LOG.infof("Beurs %s (%s) toegevoegd aan de lijst", label, code);
    }
}
