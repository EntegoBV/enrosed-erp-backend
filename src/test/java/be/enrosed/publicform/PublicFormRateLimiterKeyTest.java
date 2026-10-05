package be.enrosed.publicform;

import be.enrosed.account.AccountTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PublicFormRateLimiterKeyTest {
    @Inject PublicFormRateLimiter rateLimiter;
    @Inject PublicFormHasher hasher;

    private final List<String> usedKeyHashes = new ArrayList<>();

    @AfterEach
    void removeBuckets() {
        QuarkusTransaction.requiringNew().run(() ->
                usedKeyHashes.forEach(hash -> PublicFormRateBucketEntity.delete("keyHash", hash)));
    }

    @Test
    void countsPerKeyAndThrowsAtTheCallersLimit() {
        String email = key(PublicFormAction.ACCOUNT_LOGIN, "EMAIL") + "@example.com";
        track(PublicFormAction.ACCOUNT_LOGIN, "EMAIL", email);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertDoesNotThrow(() -> rateLimiter.checkKey(
                    PublicFormAction.ACCOUNT_LOGIN, "EMAIL", email, 3));
        }
        PublicFormRateLimitException limited = assertThrows(PublicFormRateLimitException.class,
                () -> rateLimiter.checkKey(PublicFormAction.ACCOUNT_LOGIN, "EMAIL", email, 3));

        assertTrue(limited.retryAfterSeconds() >= 1
                && limited.retryAfterSeconds() <= PublicFormAction.ACCOUNT_LOGIN.windowSeconds());
        assertEquals(3, bucket(PublicFormAction.ACCOUNT_LOGIN, "EMAIL", email).requestCount,
                "a refused attempt is not counted");
    }

    @Test
    void keysAreSeparatePerValuePerKeyTypeAndPerAction() {
        String value = key(PublicFormAction.ACCOUNT_LOGIN, "EMAIL");
        String other = key(PublicFormAction.ACCOUNT_LOGIN, "EMAIL");
        track(PublicFormAction.ACCOUNT_LOGIN, "ACCOUNT", value);
        track(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", value);

        rateLimiter.checkKey(PublicFormAction.ACCOUNT_LOGIN, "EMAIL", value, 1);

        assertDoesNotThrow(() -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_LOGIN, "EMAIL", other, 1));
        assertDoesNotThrow(() -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_LOGIN, "ACCOUNT", value, 1));
        assertDoesNotThrow(() -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", value, 1));
        assertThrows(PublicFormRateLimitException.class, () -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_LOGIN, "EMAIL", value, 1));
    }

    @Test
    void valueIsStrippedAndLowerCasedBeforeItIsCounted() {
        String value = key(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL");

        rateLimiter.checkKey(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL",
                "  " + value.toUpperCase(Locale.ROOT) + " ", 1);

        assertThrows(PublicFormRateLimitException.class, () -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", value, 1));
    }

    @Test
    void nullOrBlankValueIsNotCounted() {
        long before = bucketCount();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertDoesNotThrow(() -> rateLimiter.checkKey(
                    PublicFormAction.ACCOUNT_LOGIN, "EMAIL", null, 1));
            assertDoesNotThrow(() -> rateLimiter.checkKey(
                    PublicFormAction.ACCOUNT_LOGIN, "EMAIL", "   ", 1));
        }

        assertEquals(before, bucketCount());
    }

    @Test
    void aSolvedChallengeIsAcceptedOnceWhicheverAccountFormUsedItFirst() {
        String challengeHash = AccountTokens.hash("solved-" + UUID.randomUUID());
        track(PublicFormAction.ACCOUNT_CHALLENGE, "CHALLENGE", challengeHash);

        /* All three account forms pass the same action, so they share one bucket. */
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_CHALLENGE, "CHALLENGE", challengeHash, 1);

        assertThrows(PublicFormRateLimitException.class, () -> rateLimiter.checkKey(
                PublicFormAction.ACCOUNT_CHALLENGE, "CHALLENGE", challengeHash, 1));
        assertEquals("CHALLENGE",
                bucket(PublicFormAction.ACCOUNT_CHALLENGE, "CHALLENGE", challengeHash).keyType);
    }

    @Test
    void accountActionsCarryTheAgreedLimitsAndWindows() {
        assertLimits(PublicFormAction.ACCOUNT_REQUEST_SUBMIT, 5, 3_600, 3);
        assertLimits(PublicFormAction.ACCOUNT_LINK_REQUEST, 5, 3_600, 0);
        assertLimits(PublicFormAction.ACCOUNT_LOGIN, 20, 900, 0);
        assertLimits(PublicFormAction.ACCOUNT_ACTIVATE, 20, 3_600, 0);
        assertLimits(PublicFormAction.ACCOUNT_QUOTE_READ, 120, 3_600, 0);
        assertLimits(PublicFormAction.ACCOUNT_QUOTE_SUBMIT, 10, 3_600, 10);
        assertLimits(PublicFormAction.ACCOUNT_NOTICE_HOUR, 6, 3_600, 0);
        assertLimits(PublicFormAction.ACCOUNT_NOTICE_DAY, 20, 86_400, 0);
        assertLimits(PublicFormAction.ACCOUNT_CHALLENGE, 1, 86_400, 0);
    }

    @Test
    void existingActionsKeepTheirLimits() {
        assertLimits(PublicFormAction.QUOTE_PREVIEW, 60, 60, 0);
        assertLimits(PublicFormAction.QUOTE_SUBMIT, 5, 3_600, 3);
        assertLimits(PublicFormAction.CONTACT_SUBMIT, 10, 3_600, 3);
    }

    @Test
    void everyActionAndPurposeNameFitsItsColumn() {
        for (PublicFormAction action : PublicFormAction.values()) {
            assertTrue(action.name().length() <= 32, action.name());
        }
        for (PublicFormPurpose purpose : PublicFormPurpose.values()) {
            assertTrue(purpose.name().length() <= 16, purpose.name());
        }
        assertNull(bucket(PublicFormAction.ACCOUNT_NOTICE_DAY, "GLOBAL", "never-used-" + UUID.randomUUID()));
    }

    private static void assertLimits(PublicFormAction action, int ipLimit, long windowSeconds,
                                     int emailLimit) {
        assertEquals(ipLimit, action.ipLimit(), action.name());
        assertEquals(windowSeconds, action.windowSeconds(), action.name());
        assertEquals(emailLimit, action.emailLimit(), action.name());
    }

    private String key(PublicFormAction action, String keyType) {
        String value = "key-" + UUID.randomUUID();
        track(action, keyType, value);
        return value;
    }

    private void track(PublicFormAction action, String keyType, String value) {
        usedKeyHashes.add(hasher.hash(action.name() + ":" + keyType, value));
        usedKeyHashes.add(hasher.hash(action.name() + ":" + keyType, value + "@example.com"));
    }

    private PublicFormRateBucketEntity bucket(PublicFormAction action, String keyType, String value) {
        String keyHash = hasher.hash(action.name() + ":" + keyType, value);
        return QuarkusTransaction.requiringNew().call(() ->
                PublicFormRateBucketEntity.<PublicFormRateBucketEntity>find(
                        "action = ?1 and keyType = ?2 and keyHash = ?3",
                        action.name(), keyType, keyHash).firstResult());
    }

    private static long bucketCount() {
        /* A lambda, not a method reference: only a direct call reaches the enhanced entity. */
        return QuarkusTransaction.requiringNew().call(() -> PublicFormRateBucketEntity.count());
    }
}
