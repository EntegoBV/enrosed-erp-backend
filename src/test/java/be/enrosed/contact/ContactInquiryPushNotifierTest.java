package be.enrosed.contact;

import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.mail.InternalMessageSender;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class ContactInquiryPushNotifierTest {
    @Inject ContactInquiryService contacts;
    @Inject PublicFormIdempotencyService idempotency;
    @Inject ContactNotificationOutboxWorker mailWorker;
    @InjectMock WebPushNotifier phones;
    @InjectMock InternalMessageSender messages;
    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> created.forEach(id -> {
            ContactNotificationOutboxEntity.delete("inquiryId", id);
            ContactInquiryEntity.deleteById(id);
        }));
    }

    @Test
    void committedSubmissionPushesOnceAndLinksToTheSavedInquiry() {
        ContactDtos.Response response = QuarkusTransaction.requiringNew().call(() -> {
            ContactDtos.Response saved = submit();
            verifyNoInteractions(phones);
            return saved;
        });
        long id = id(response);

        verify(phones).notifyAll("contact-inquiry", "Nieuw contactformulier ontvangen",
                "Bekijk het bericht bij Contactaanvragen", "/contactaanvragen?open=" + id);
        verifyNoMoreInteractions(phones);
        assertEquals(ContactOutboxStatus.PENDING, outbox(id).status);
    }

    @Test
    void rolledBackSubmissionDoesNotRingAnyDevice() {
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            submit();
            throw new IllegalStateException("Rollback the submission");
        }));
        verifyNoInteractions(phones);
    }

    @Test
    void replayedSubmissionDoesNotSendAnotherPush() {
        String key = UUID.randomUUID().toString();
        ContactDtos.Response first = idempotency.execute(PublicFormPurpose.CONTACT,
                key, "contact-push-test", ContactDtos.Response.class, this::submit);
        ContactDtos.Response replay = idempotency.execute(PublicFormPurpose.CONTACT,
                key, "contact-push-test", ContactDtos.Response.class, this::submit);

        assertEquals(first, replay);
        long id = id(first);
        verify(phones).notifyAll("contact-inquiry", "Nieuw contactformulier ontvangen",
                "Bekijk het bericht bij Contactaanvragen", "/contactaanvragen?open=" + id);
        verifyNoMoreInteractions(phones);
    }

    @Test
    void failingPushStillSavesTheInquiryAndKeepsTheTeamMailPending() {
        doThrow(new IllegalStateException("Unavailable device lookup"))
                .when(phones).notifyAll(any(), any(), any(), any());

        ContactDtos.Response response = assertDoesNotThrow(this::submit);
        assertEquals("RECEIVED", response.status());
        assertEquals(ContactOutboxStatus.PENDING, outbox(id(response)).status);
    }

    @Test
    void retryingTheTeamMailDoesNotRepeatThePhoneNotification() {
        long id = id(submit());
        doThrow(new IllegalStateException("Test mail outage"))
                .when(messages).sendTeamNotice(any());
        mailWorker.work();
        QuarkusTransaction.requiringNew().run(() ->
                ContactNotificationOutboxEntity.update("nextAttemptAt = ?1 where inquiryId = ?2",
                        Instant.EPOCH, id));
        mailWorker.work();

        verify(messages, times(2)).sendTeamNotice(any());
        verify(phones).notifyAll("contact-inquiry", "Nieuw contactformulier ontvangen",
                "Bekijk het bericht bij Contactaanvragen", "/contactaanvragen?open=" + id);
        verifyNoMoreInteractions(phones);
    }

    private ContactDtos.Response submit() {
        return contacts.submit(ContactInquiryServiceTest.request("A message about our products", "Buyer BV"));
    }

    private long id(ContactDtos.Response response) {
        long id = QuarkusTransaction.requiringNew().call(() ->
                ContactInquiryEntity.<ContactInquiryEntity>find("reference", response.reference()).firstResult().id);
        created.add(id);
        return id;
    }

    private ContactNotificationOutboxEntity outbox(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                ContactNotificationOutboxEntity.<ContactNotificationOutboxEntity>find("inquiryId", id).firstResult());
    }
}
