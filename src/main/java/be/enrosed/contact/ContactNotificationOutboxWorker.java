package be.enrosed.contact;

import be.enrosed.shared.mail.InternalMessageSender;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/** Durable at-least-once internal notification; the public request never waits for e-mail. */
@ApplicationScoped
public class ContactNotificationOutboxWorker {
    private static final Logger LOG = Logger.getLogger(ContactNotificationOutboxWorker.class);
    private static final int MAX_ATTEMPTS = 5;
    private final InternalMessageSender messages;

    public ContactNotificationOutboxWorker(InternalMessageSender messages) {
        this.messages = messages;
    }

    @Scheduled(identity = "contact-notification-outbox", every = "${enrosed.contact.outbox.every:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void work() {
        Claimed claimed = QuarkusTransaction.requiringNew().call(this::claim);
        if (claimed == null) return;
        Delivery result;
        try {
            messages.sendInternal("Nieuwe website-contactaanvraag " + claimed.reference(),
                    body(claimed));
            result = new Delivery(true, null);
        } catch (RuntimeException exception) {
            result = new Delivery(false, exception.getClass().getSimpleName());
        }
        Delivery completed = result;
        QuarkusTransaction.requiringNew().run(() -> finish(claimed.id(), completed));
    }

    private Claimed claim() {
        Instant now = Instant.now();
        long exhausted = ContactNotificationOutboxEntity.update(
                "status = ?1, lastError = ?2, nextAttemptAt = ?3"
                        + " where status in ?4 and attemptCount >= ?5 and nextAttemptAt <= ?3",
                ContactOutboxStatus.FAILED, "MaxAttemptsReached", now,
                List.of(ContactOutboxStatus.PENDING, ContactOutboxStatus.PROCESSING),
                MAX_ATTEMPTS);
        if (exhausted > 0) {
            LOG.errorf("%d uitgeputte contactmelding(en) zijn definitief mislukt", exhausted);
        }
        ContactNotificationOutboxEntity row = ContactNotificationOutboxEntity
                .<ContactNotificationOutboxEntity>find(
                        "status in ?1 and nextAttemptAt <= ?2 and attemptCount < ?3 order by id",
                        List.of(ContactOutboxStatus.PENDING, ContactOutboxStatus.PROCESSING),
                        now, MAX_ATTEMPTS)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (row == null) return null;
        ContactInquiryEntity inquiry = ContactInquiryEntity.findById(row.inquiryId);
        if (inquiry == null) {
            row.status = ContactOutboxStatus.FAILED;
            row.lastError = "InquiryMissing";
            return null;
        }
        row.status = ContactOutboxStatus.PROCESSING;
        row.attemptCount++;
        row.lastAttemptAt = now;
        row.nextAttemptAt = now.plusSeconds(60);
        ContactNotificationOutboxEntity.flush();
        return new Claimed(row.id, inquiry.reference, inquiry.language.name(), inquiry.topic.name(),
                inquiry.contactName, inquiry.email, inquiry.companyName, inquiry.phone,
                inquiry.message, inquiry.sourcePage);
    }

    private void finish(long id, Delivery result) {
        ContactNotificationOutboxEntity row = ContactNotificationOutboxEntity.findById(
                id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || row.status == ContactOutboxStatus.SENT) return;
        Instant now = Instant.now();
        if (result.success()) {
            row.status = ContactOutboxStatus.SENT;
            row.sentAt = now;
            row.nextAttemptAt = now;
            row.lastError = null;
            return;
        }
        row.lastError = result.error();
        if (row.attemptCount >= MAX_ATTEMPTS) {
            row.status = ContactOutboxStatus.FAILED;
            row.nextAttemptAt = now;
            LOG.errorf("Contactmelding %d is definitief mislukt na %d pogingen",
                    row.id, row.attemptCount);
        } else {
            row.status = ContactOutboxStatus.PENDING;
            row.nextAttemptAt = now.plusSeconds(Math.min(900, 15L << row.attemptCount));
            LOG.warnf("Contactmelding %d kon niet worden afgeleverd; poging %d",
                    row.id, row.attemptCount);
        }
    }

    private static String body(Claimed request) {
        return String.join("\n",
                "Referentie: " + request.reference(),
                "Taal: " + request.language(),
                "Onderwerp: " + request.topic(),
                "Naam: " + request.contactName(),
                "E-mail: " + request.email(),
                "Bedrijf: " + value(request.companyName()),
                "Telefoon: " + value(request.phone()),
                "Bronpagina: " + value(request.sourcePage()),
                "",
                "Bericht:",
                request.message());
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record Claimed(long id, String reference, String language, String topic,
                           String contactName, String email, String companyName, String phone,
                           String message, String sourcePage) {}
    private record Delivery(boolean success, String error) {}
}
