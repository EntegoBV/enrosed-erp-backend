package be.enrosed.sales.application;

import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PublicQuoteAbuseGuardTest {

    @Test
    void explicitIdempotencyKeyReturnsFirstSafeResponse() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        AtomicInteger calls = new AtomicInteger();
        var first = guard.submitOnce("request-1234", "payload", () -> response(calls.incrementAndGet()));
        var second = guard.submitOnce("request-1234", "payload", () -> response(calls.incrementAndGet()));

        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void accidentalDoubleTapWithoutAKeyIsSuppressedByPayload() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        AtomicInteger calls = new AtomicInteger();

        var first = guard.submitOnce(null, "same-payload",
                () -> response(calls.incrementAndGet()));
        var second = guard.submitOnce(null, "same-payload",
                () -> response(calls.incrementAndGet()));

        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void failedSubmissionIsNotCachedAndCanBeRetried() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class,
                () -> guard.submitOnce("request-1234", "payload", () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("database unavailable");
                }));
        PublicQuoteDtos.SubmissionResponse retried = guard.submitOnce(
                "request-1234", "payload", () -> response(calls.incrementAndGet()));

        assertEquals("REF-2", retried.reference());
        assertEquals(2, calls.get());
    }

    @Test
    void previewLimitIsEnforced() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        for (int i = 0; i < 60; i++) guard.checkPreview("198.51.100.10");
        assertThrows(PublicQuoteRateLimitException.class,
                () -> guard.checkPreview("198.51.100.10"));
        assertDoesNotThrow(() -> guard.checkPreview("198.51.100.11"));
    }

    @Test
    void submitLimitCannotBeEvadedByRotatingEmailAddresses() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        for (int i = 0; i < 5; i++) {
            guard.checkSubmit("198.51.100.10", "buyer" + i + "@example.com");
        }

        assertThrows(PublicQuoteRateLimitException.class,
                () -> guard.checkSubmit("198.51.100.10", "another@example.com"));
        assertDoesNotThrow(() -> guard.checkSubmit(
                "198.51.100.11", "fresh@example.com"));
    }

    @Test
    void submitLimitAlsoFollowsAnEmailAcrossClientAddresses() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        for (int i = 0; i < 5; i++) {
            guard.checkSubmit("198.51.100." + (10 + i), "BUYER@example.com");
        }

        assertThrows(PublicQuoteRateLimitException.class,
                () -> guard.checkSubmit("198.51.100.99", "buyer@example.com"));
    }

    @Test
    void malformedIdempotencyKeyIsRejected() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        PublicQuoteValidationException error = assertThrows(
                PublicQuoteValidationException.class,
                () -> guard.submitOnce("bad key", "payload", () -> response(1)));
        assertEquals("INVALID", error.fieldErrors().get("idempotencyKey"));
    }

    @Test
    void sameIdempotencyKeyCannotBeReusedForDifferentPayload() {
        PublicQuoteAbuseGuard guard = new PublicQuoteAbuseGuard();
        guard.submitOnce("request-1234", "first", () -> response(1));

        PublicQuoteValidationException error = assertThrows(
                PublicQuoteValidationException.class,
                () -> guard.submitOnce("request-1234", "different", () -> response(2)));
        assertEquals("CONFLICT", error.fieldErrors().get("idempotencyKey"));
    }

    private static PublicQuoteDtos.SubmissionResponse response(int suffix) {
        return new PublicQuoteDtos.SubmissionResponse("REF-" + suffix, "RECEIVED",
                "REQUEST_RECEIVED_NOT_BINDING", "FINAL_QUOTE_FOLLOWS", null);
    }
}
