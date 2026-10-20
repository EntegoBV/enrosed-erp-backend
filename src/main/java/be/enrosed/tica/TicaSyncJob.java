package be.enrosed.tica;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Instant;

@ApplicationScoped
public class TicaSyncJob {
    private static final Logger LOG = Logger.getLogger(TicaSyncJob.class);
    private final TicaService service;
    private final TicaPortalClient portal;
    private final TicaCsvParser parser;
    @ConfigProperty(name = "enrosed.tica.exhibitor-number", defaultValue = "2132") String exhibitor;

    public TicaSyncJob(TicaService service, TicaPortalClient portal, TicaCsvParser parser) {
        this.service = service; this.portal = portal; this.parser = parser;
    }

    @Scheduled(identity = "tica-portal-sync", every = "${enrosed.tica.scheduler.every:30s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        TicaService.Claim claim = service.claim(Instant.now());
        if (claim == null) return;
        try {
            String csv = portal.exportTransactions(claim.from(), claim.to());
            var rows = parser.parse(csv, exhibitor, claim.from(), claim.to());
            service.complete(claim, rows, Instant.now());
        } catch (TicaPortalException exception) {
            service.failed(claim, "TICA-portaal kon niet worden gelezen (" + exception.getCode() + "). Probeer opnieuw of controleer de serverinstellingen.");
            LOG.warnf("TICA-portaalcontrole mislukt: %s", exception.getCode());
        } catch (IllegalArgumentException exception) {
            service.failed(claim, "TICA-export afgewezen: het bestand bevat ongeldige of onverwachte verkoopgegevens.");
            LOG.warn("TICA-exportvalidatie mislukt; geen gegevens gewijzigd.");
        } catch (RuntimeException exception) {
            service.failed(claim, "TICA-synchronisatie mislukt. De laatste geslaagde gegevens zijn behouden.");
            // Do not log exceptions from a browser, response or database binding containing private source data.
            LOG.warnf("TICA-synchronisatie mislukt (%s)", exception.getClass().getSimpleName());
        }
    }
}
