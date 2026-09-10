package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Only unused partner quote drafts move to the invoice-only workflow; published history is immutable. */
@ApplicationScoped
public class LegacyPartnerQuoteDraftBackfill {
    private static final Logger LOG = Logger.getLogger(LegacyPartnerQuoteDraftBackfill.class);
    @Inject SalesRepositories.Orders orders;
    @Inject LegacyPartnerQuoteDrafts drafts;
    @ConfigProperty(name = "enrosed.sales.legacy-partner-draft-backfill.enabled", defaultValue = "false")
    boolean enabled;

    void onStart(@Observes @Priority(3000) StartupEvent event) {
        if (!enabled) return;
        var candidates = orders.findAll().stream()
                .filter(order -> !order.isArchived() && LegacyPartnerQuoteDrafts.untouchedDraft(order))
                .map(order -> order.id()).toList();
        int migrated = 0, skipped = 0, failed = 0;
        for (Long id : candidates) {
            try {
                var result = QuarkusTransaction.requiringNew().call(() -> {
                    var preview = drafts.preview(id);
                    if (!preview.eligible()) {
                        LOG.warnf("Partner quote draft %d retained for review: %s", id, preview.reason());
                        return null;
                    }
                    return drafts.convert(id);
                });
                if (result == null) skipped++;
                else {
                    migrated++;
                    LOG.infof("Partner quote draft %d archived; invoice references: %s", id, result.invoices());
                }
            } catch (RuntimeException failure) {
                failed++;
                LOG.errorf(failure, "Partner quote draft %d was not migrated; the whole transition was rolled back and can retry", id);
            }
        }
        LOG.infof("Partner quote draft transition: %d migrated, %d retained for review, %d failed; candidate IDs %s",
                migrated, skipped, failed, candidates);
    }
}
