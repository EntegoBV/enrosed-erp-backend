package be.enrosed.contact;

import be.enrosed.push.WebPushNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/** Push once after saving, independently of the retrying team-mail outbox. */
@ApplicationScoped
public class ContactInquiryPushNotifier {
    private static final Logger LOG = Logger.getLogger(ContactInquiryPushNotifier.class);
    private final WebPushNotifier phones;

    public ContactInquiryPushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) ContactInquiryReceived event) {
        try {
            phones.notifyAll("contact-inquiry", "Nieuw contactformulier ontvangen",
                    "Bekijk het bericht bij Contactaanvragen",
                    "/contactaanvragen?open=" + event.id());
        } catch (RuntimeException exception) {
            LOG.warnf("Contactaanvraag %d: pushmelding kon niet vertrekken (%s)",
                    event.id(), exception.getClass().getSimpleName());
        }
    }
}
