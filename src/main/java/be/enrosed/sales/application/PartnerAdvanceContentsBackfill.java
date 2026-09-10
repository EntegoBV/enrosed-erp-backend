package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Add cargo context to existing advances, after any legacy quote-to-invoice transition. */
@ApplicationScoped
public class PartnerAdvanceContentsBackfill {
    private static final Logger LOG = Logger.getLogger(PartnerAdvanceContentsBackfill.class);
    @Inject SalesRepositories.Orders orders;
    @Inject PartnerAdvanceContents contents;
    @ConfigProperty(name = "enrosed.sales.advance-contents-backfill.enabled", defaultValue = "false") boolean enabled;

    void onStart(@Observes @Priority(3100) StartupEvent event) {
        if (!enabled) return;
        var candidates = orders.findAll().stream().filter(order -> order.isInvoice() && order.isPartnerAdvance())
                .filter(order -> contents.find(order).isEmpty()).map(order -> order.id()).toList();
        for (Long id : candidates) {
            try {
                QuarkusTransaction.requiringNew().run(() -> contents.capture(orders.findById(id).orElseThrow()));
                LOG.infof("Advance invoice %d cargo snapshot captured; financial lines unchanged", id);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "Advance invoice %d cargo snapshot was not captured; financial document unchanged", id);
            }
        }
    }
}
