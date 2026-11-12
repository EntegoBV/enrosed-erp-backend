package be.enrosed.partnerportal.application;

import be.enrosed.push.WebPushNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

/**
 * Tells the staff phones that a melding came in, only after its transaction committed (like
 * SalesActivityPushNotifier). The lock screen shows no names and no prices: "100 stuks · Veiling".
 */
@ApplicationScoped
public class PartnerSalePushNotifier {

    static final String KIND = "partner-sale";
    static final String TITLE_REPORTED = "Veilingverkoop gemeld";
    /** An edit of a melding (round 2): the same lock-screen line under another title. */
    static final String TITLE_CHANGED = "Veilingverkoop gewijzigd";

    private final WebPushNotifier phones;

    public PartnerSalePushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            phones.notifyAll(KIND, ready.title() == null ? TITLE_REPORTED : ready.title(),
                    ready.quantity() + (ready.quantity() == 1 ? " stuk · " : " stuks · ") + ready.kind(),
                    "/veilingverkopen?melding=" + ready.reportId(), ready.exceptUsername());
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved melding. */
        }
    }

    /**
     * Fired inside the melding's transaction. kind is Veiling, Direct or Terugkoop; exceptUsername
     * is the staff member who entered or changed it (they saw it happen), null when the partner did;
     * title "Veilingverkoop gemeld" (null) or "Veilingverkoop gewijzigd".
     */
    public record Ready(long reportId, int quantity, String kind, String exceptUsername, String title) {
        public Ready(long reportId, int quantity, String kind, String exceptUsername) {
            this(reportId, quantity, kind, exceptUsername, TITLE_REPORTED);
        }
    }
}
