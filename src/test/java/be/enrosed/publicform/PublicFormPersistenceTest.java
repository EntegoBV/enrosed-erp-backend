package be.enrosed.publicform;

import be.enrosed.contact.ContactDtos;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PublicFormPersistenceTest {
    @Inject PublicFormIdempotencyService idempotency;
    @Inject PublicFormHasher hasher;
    @Inject PublicFormLockService locks;

    @Test
    void concurrentSameKeyCreatesAndConsumesEmailQuotaExactlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String key = "contact-" + suffix;
        String fingerprint = "payload-" + suffix;
        String email = suffix + "@example.com";
        AtomicInteger actions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ContactDtos.Response> call = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return idempotency.executeAccepted(PublicFormPurpose.CONTACT, key, fingerprint,
                        PublicFormAction.CONTACT_SUBMIT, email, ContactDtos.Response.class, () -> {
                            int invocation = actions.incrementAndGet();
                            try {
                                Thread.sleep(75);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            return new ContactDtos.Response("CNT-EXACT-" + invocation, "RECEIVED");
                        });
            };
            Future<ContactDtos.Response> first = executor.submit(call);
            Future<ContactDtos.Response> second = executor.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, actions.get());
        String emailHash = hasher.hash("CONTACT_SUBMIT:EMAIL", email);
        PublicFormRateBucketEntity bucket = QuarkusTransaction.requiringNew().call(() ->
                PublicFormRateBucketEntity.<PublicFormRateBucketEntity>find(
                        "action = ?1 and keyType = ?2 and keyHash = ?3",
                        PublicFormAction.CONTACT_SUBMIT.name(), "EMAIL", emailHash).firstResult());
        assertEquals(1, bucket.requestCount);

        QuarkusTransaction.requiringNew().run(() -> {
            PublicFormRateBucketEntity.delete("keyHash", emailHash);
            PublicFormSubmissionEntity.delete("payloadHash",
                    hasher.hash("payload:CONTACT", fingerprint));
        });
    }

    @Test
    void sameExplicitKeyWithDifferentPayloadIsRejectedWithoutSecondAction() {
        String suffix = UUID.randomUUID().toString();
        String key = "contact-" + suffix;
        String email = suffix + "@example.com";
        AtomicInteger actions = new AtomicInteger();
        idempotency.executeAccepted(PublicFormPurpose.CONTACT, key, "first-" + suffix,
                PublicFormAction.CONTACT_SUBMIT, email, ContactDtos.Response.class,
                () -> new ContactDtos.Response("CNT-" + actions.incrementAndGet(), "RECEIVED"));

        PublicFormValidationException exception = assertThrows(
                PublicFormValidationException.class,
                () -> idempotency.executeAccepted(PublicFormPurpose.CONTACT, key,
                        "different-" + suffix, PublicFormAction.CONTACT_SUBMIT, email,
                        ContactDtos.Response.class,
                        () -> new ContactDtos.Response("CNT-" + actions.incrementAndGet(),
                                "RECEIVED")));
        assertEquals("CONFLICT", exception.fieldErrors().get("idempotencyKey"));
        assertEquals(1, actions.get());

        QuarkusTransaction.requiringNew().run(() -> {
            PublicFormRateBucketEntity.delete("keyHash",
                    hasher.hash("CONTACT_SUBMIT:EMAIL", email));
            PublicFormSubmissionEntity.delete("id",
                    hasher.hash("idempotency:CONTACT", key));
        });
    }

    @Test
    @TestTransaction
    void partialLockSeedFailsFastWithMigrationInstruction() {
        PublicFormLockEntity.deleteById(0);
        IllegalStateException exception = assertThrows(
                IllegalStateException.class, locks::validateSeedRows);
        assertTrue(exception.getMessage().contains("0..63"));
    }
}
