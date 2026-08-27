package be.enrosed.sales.application;

import be.enrosed.push.WebPushNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

/** Sends one staff-only, non-PII notification after the complete ERP draft commits. */
@ApplicationScoped
public class WebsiteQuotePushNotifier {
    private final WebPushNotifier phones;

    public WebsiteQuotePushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            phones.notifyAll("sale-quote",
                    "Nieuwe websiteaanvraag " + ready.reference(),
                    "Klaar voor beoordeling in Verkoop",
                    "/sales/" + ready.orderId());
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved quote. */
        }
    }

    public record Ready(long orderId, String reference) {}
}
