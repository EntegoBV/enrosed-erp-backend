package be.enrosed.sales.application;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Gives every issued invoice and credit note that holds no VAT regime yet the one it is priced with
 * right now (SalesVatChoices.pin): the documents issued before 2026-11-26, what the old release
 * issued while the deploy ran, and any path that left concept without the hook. Ten minutes after
 * the start and every ten minutes after that, as PalletCountRuleBackfill: by then only this release
 * serves. Until its turn such a document is priced live, as it always was, so nothing moves by this
 * job; it only stops later changes of the customer from moving it.
 */
@ApplicationScoped
public class SalesVatPinJob {

    private static final Logger LOG = Logger.getLogger(SalesVatPinJob.class);

    @Inject SalesVatChoices choices;
    @Inject SalesOrderService sales;

    @Scheduled(identity = "sales-vat-pin", every = "${enrosed.vat-pin.every:10m}",
            delayed = "${enrosed.vat-pin.after:10m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        try {
            int pinned = pinIssued();
            if (pinned > 0) LOG.infof("VAT regime held on %d issued document(s)", pinned);
        } catch (RuntimeException failure) {
            /* Nothing is lost: those documents keep pricing live, and the next run tries again. */
            LOG.errorf(failure, "VAT regime not held on issued documents; they stay live until the next run");
        }
    }

    /** One transaction per document: one that cannot be priced never holds the others back. */
    int pinIssued() {
        List<Long> ids = QuarkusTransaction.requiringNew().call(choices::issuedWithoutPin);
        int pinned = 0;
        for (Long id : ids) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    var order = sales.get(id);
                    choices.pin(order, sales.price(order));
                });
                pinned++;
            } catch (RuntimeException failure) {
                LOG.warnf(failure, "VAT regime not held on sales document %d", id);
            }
        }
        return pinned;
    }
}
