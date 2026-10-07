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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderPlaced(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Placed placed) {
        push(placed.orderId(), "Nieuwe websitebestelling " + placed.number(),
                "Klant kan nog wijzigen · neem in verwerking in Verkoop");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Changed changed) {
        String summary = changed.summary() == null ? "" : changed.summary().strip();
        push(changed.orderId(), "Websitebestelling " + changed.number() + " gewijzigd door de klant",
                "Versie " + changed.revision() + (summary.isEmpty() ? ""
                        : " · " + (summary.length() > 120 ? summary.substring(0, 120) : summary)));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterOrderCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Cancelled cancelled) {
        push(cancelled.orderId(), "Websitebestelling " + cancelled.number() + " geannuleerd door de klant",
                "De klant annuleerde de bestelling op de website");
    }

    private void push(long orderId, String title, String body) {
        try {
            phones.notifyAll("sale-quote", title, body, "/sales/" + orderId);
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved order. */
        }
    }

    public record Ready(long orderId, String reference) {}
}
