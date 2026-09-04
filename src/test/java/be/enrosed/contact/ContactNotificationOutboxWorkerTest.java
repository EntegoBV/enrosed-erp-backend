package be.enrosed.contact;

import be.enrosed.shared.mail.InternalMessageSender;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@QuarkusTest
class ContactNotificationOutboxWorkerTest {
    @Inject ContactInquiryService contacts;
    @Inject ContactNotificationOutboxWorker worker;
    @InjectMock InternalMessageSender messages;

    @Test
    void exhaustedNotificationDoesNotStarveLaterPendingMail() {
        ContactNotificationOutboxEntity first = outbox(create("First message for retry"));
        doThrow(new IllegalStateException("test transport outage"))
                .when(messages).sendTeamNotice(any());
        for (int attempt = 0; attempt < 5; attempt++) {
            makeDue(first.id);
            worker.work();
        }
        assertEquals(ContactOutboxStatus.FAILED, status(first.id));
        verify(messages, times(5)).sendTeamNotice(any());

        reset(messages);
        ContactNotificationOutboxEntity second = outbox(create("Second message can proceed"));
        worker.work();

        assertEquals(ContactOutboxStatus.SENT, status(second.id));
        assertEquals(ContactOutboxStatus.FAILED, status(first.id));
        verify(messages).sendTeamNotice(any());
    }

    @Test
    void activeFifthAttemptIsNotMarkedFailedBeforeItsLeaseExpires() {
        ContactNotificationOutboxEntity active = outbox(create("Active fifth attempt"));
        QuarkusTransaction.requiringNew().run(() -> {
            ContactNotificationOutboxEntity row = ContactNotificationOutboxEntity.findById(active.id);
            row.status = ContactOutboxStatus.PROCESSING;
            row.attemptCount = 5;
            row.nextAttemptAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        });
        ContactNotificationOutboxEntity later = outbox(create("Later pending message"));

        worker.work();

        assertEquals(ContactOutboxStatus.PROCESSING, status(active.id));
        assertEquals(ContactOutboxStatus.SENT, status(later.id));
    }

    private String create(String message) {
        return contacts.submit(ContactInquiryServiceTest.request(message, "Buyer BV")).reference();
    }

    private static ContactNotificationOutboxEntity outbox(String reference) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ContactInquiryEntity inquiry = ContactInquiryEntity.find(
                    "reference", reference).firstResult();
            return ContactNotificationOutboxEntity.<ContactNotificationOutboxEntity>find(
                    "inquiryId", inquiry.id).firstResult();
        });
    }

    private static void makeDue(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            ContactNotificationOutboxEntity row = ContactNotificationOutboxEntity.findById(id);
            row.nextAttemptAt = Instant.EPOCH;
        });
    }

    private static ContactOutboxStatus status(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                ContactNotificationOutboxEntity.<ContactNotificationOutboxEntity>findById(id).status);
    }
}
