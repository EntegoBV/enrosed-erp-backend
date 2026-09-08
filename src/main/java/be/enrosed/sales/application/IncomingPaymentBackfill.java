package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class IncomingPaymentBackfill {
    @Inject SalesRepositories.Orders orders;
    @Inject IncomingPaymentService incoming;
    void onStart(@Observes StartupEvent event) {
        orders.findAll().stream().filter(order -> order.isInvoice() && order.paidAt() != null).forEach(order -> {
            try { incoming.backfillOne(order.id()); }
            catch (RuntimeException failure) {
                org.jboss.logging.Logger.getLogger(IncomingPaymentBackfill.class)
                        .errorf(failure, "Could not migrate historical receipt for sales order %d; review this document", order.id());
            }
        });
    }
}
