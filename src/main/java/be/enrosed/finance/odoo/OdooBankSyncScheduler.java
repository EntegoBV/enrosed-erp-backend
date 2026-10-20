package be.enrosed.finance.odoo;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** The minute tick only checks a persisted deadline; actual imports run at most once per 12 hours. */
@ApplicationScoped
public class OdooBankSyncScheduler {
    private static final Logger LOG=Logger.getLogger(OdooBankSyncScheduler.class);
    @Inject OdooService service;
    @Inject OdooTicaCreditnoteImportService ticaCreditnotes;
    @Inject OdooLincCostMatchService lincCosts;

    @Scheduled(every="{enrosed.odoo.bank-auto-sync.check-every}",delayed="30s",
            concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        if(!service.automaticBankSyncReady())return;
        try {
            service.syncBankIfDue().ifPresent(report->{
                if(!report.complete())LOG.warn("Automatic Odoo bank import was incomplete; the next attempt is scheduled in 30 minutes.");
            });
        } catch(OdooFailure failure) {
            // BUSY also covers an initial-row creation race across instances. No extra remote request is made.
            if(!"BUSY".equals(failure.code))LOG.warn("Automatic Odoo bank import could not finish; persisted lease and retry time prevent immediate repeats.");
        } catch(RuntimeException unavailable) {
            LOG.warn("Automatic Odoo bank import is temporarily unavailable.");
        }
        try {
            ticaCreditnotes.syncIfDue().ifPresent(report->{
                if(!report.complete())LOG.warn("Some incoming TICA statements still need review or an attachment; the persisted status shows the next check.");
            });
        } catch(OdooFailure failure) {
            if(!"BUSY".equals(failure.code))LOG.warn("Automatic TICA statement discovery in Odoo could not finish.");
        } catch(RuntimeException unavailable) {
            LOG.warn("Automatic TICA statement discovery in Odoo is temporarily unavailable.");
        }
        try {
            lincCosts.syncIfDue().ifPresent(report->{
                if(!report.complete())LOG.warn("Some Linc invoices still need a matching bank debit or review; the persisted status shows the next check.");
            });
        } catch(OdooFailure failure) {
            if(!"BUSY".equals(failure.code))LOG.warn("Automatic Linc invoice matching could not finish.");
        } catch(RuntimeException unavailable) {
            LOG.warn("Automatic Linc invoice matching is temporarily unavailable.");
        }
    }
}
