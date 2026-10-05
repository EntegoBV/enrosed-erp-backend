package be.enrosed.account;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The two request forms through the real HTTP pipeline, with the real idempotency store and
 * the real buckets for e-mail and solved challenge. The per-network bucket and the form
 * security check are stubbed, and the background executor only captures, so every test
 * decides itself what runs after the answer.
 */
@QuarkusTest
class PublicLoginRequestResourceHttpTest {
    private static final String BASE = "/api/v1/public/account";
    private static final String ACCEPTED = "^\\{\"reference\":\"LGN-[0-9A-F]{20}\",\"status\":\"RECEIVED\"}$";

    @Inject LoginRequestNotifier notifier;
    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @Inject TransactionSynchronizationRegistry transactions;
    @InjectSpy LoginRequestService requests;
    @InjectSpy LoginRequestWriter writer;
    @InjectSpy AccountHousekeeping housekeeping;
    @InjectSpy PublicFormRateLimiter rateLimiter;
    @InjectSpy PublicFormIdempotencyService idempotency;
    @InjectMock PublicFormSecurityService security;

    private final List<Runnable> captured = new ArrayList<>();
    private final List<Long> createdCustomers = new ArrayList<>();

    @BeforeEach
    void oneNetworkAddressAndACapturingBackground() {
        doNothing().when(rateLimiter).checkIp(any(), any());
        notifier.useExecutor(captured::add);
        clearRequests();
    }

    @AfterEach
    void cleanUp() {
        notifier.useExecutor(ForkJoinPool.commonPool());
        clearRequests();
        createdCustomers.forEach(customers::delete);
        createdCustomers.clear();
    }

    @Test
    void aRequestIsAcceptedWithAReferenceAndStoredAsOneWaitingRow() {
        String email = email();

        Response response = submit(body(email), "login-request-" + UUID.randomUUID());

        response.then().statusCode(202)
                .header("Cache-Control", "no-store")
                .body("reference", matchesPattern("^LGN-[0-9A-F]{20}$"))
                .body("status", equalTo("RECEIVED"));
        CustomerLoginRequestEntity row = only(email);
        assertEquals(response.<String>path("reference"), row.reference);
        assertEquals("PENDING", row.status);
        assertEquals("ORDER_SCREEN", row.source);
        assertEquals("FR", row.language);
        assertEquals("Bloemen Peeters BV", row.companyName);
        assertEquals("BE", row.companyCountryCode);
        assertEquals("BE0123456789", row.vatNumber);
        assertEquals("An Peeters", row.contactName);
        assertEquals("Graag een login.", row.message);
        assertNotNull(row.privacyAcceptedAt);
        verify(rateLimiter).checkIp(eq(PublicFormAction.ACCOUNT_REQUEST_SUBMIT), any());
        verify(security).verifySubmission(eq(PublicFormPurpose.ACCOUNT), eq("form-token"), any());
        verify(idempotency).executeAccepted(eq(PublicFormPurpose.ACCOUNT), any(), any(),
                eq(PublicFormAction.ACCOUNT_REQUEST_SUBMIT), eq(email), any(), any());
        assertEquals(1, captured.size(), "the notice waits in the background");
    }

    @Test
    void theRequestThreadWritesInOneTransactionAndLeavesTheRestToTheBackground() {
        List<Object> inAction = new ArrayList<>();
        List<Object> inWriter = new ArrayList<>();
        doAnswer(invocation -> {
            inAction.add(transactions.getTransactionKey());
            return invocation.callRealMethod();
        }).when(requests).submit(any());
        doAnswer(invocation -> {
            inWriter.add(transactions.getTransactionKey());
            return invocation.callRealMethod();
        }).when(writer).storeJoined(any());

        submit(body(email()), null).then().statusCode(202);

        assertEquals(1, inAction.size());
        assertEquals(1, inWriter.size());
        assertNotNull(inAction.getFirst());
        assertEquals(inAction.getFirst(), inWriter.getFirst(), "the writer joined the transaction of the action");
        verify(writer, never()).storeDetached(any());
        verify(housekeeping, never()).purge();
        assertEquals(1, captured.size());
    }

    @Test
    void aFilledHoneypotIsAcceptedWithoutChallengeOrRow() {
        Map<String, Object> bot = body(email());
        bot.put("website", "https://bot.example");

        Response response = submit(bot, null);

        response.then().statusCode(202).header("Cache-Control", "no-store");
        assertTrue(response.asString().matches(ACCEPTED), response.asString());
        verifyNoInteractions(security);
        verifyNoInteractions(writer);
        verify(requests, never()).validate(any());
        verify(idempotency, never()).executeAccepted(any(), any(), any(), any(), any(), any(), any());

        Map<String, Object> linkBot = linkBody(email());
        linkBot.put("website", "https://bot.example");
        link(linkBot).then().statusCode(202).body("status", equalTo("RECEIVED"));
        verifyNoInteractions(security);
        assertTrue(captured.isEmpty());
    }

    @Test
    void anInvalidChallengeIsRefusedBeforeTheEmailQuotaAndAnOutageFailsClosed() {
        String email = email();
        doThrow(new PublicFormValidationException(Map.of("challengeToken", "INVALID")))
                .when(security).verifySubmission(any(), any(), any());

        for (int attempt = 0; attempt < 4; attempt++) {
            submit(body(email), null)
                    .then().statusCode(422)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("VALIDATION_ERROR"))
                    .body("fieldErrors.challengeToken", equalTo("INVALID"));
        }
        link(linkBody(email)).then().statusCode(422)
                .body("fieldErrors.challengeToken", equalTo("INVALID"));
        verify(idempotency, never()).executeAccepted(any(), any(), any(), any(), any(), any(), any());
        verify(rateLimiter, never()).checkKey(any(), any(), any(), anyInt());

        doThrow(new PublicFormServiceUnavailableException())
                .when(security).verifySubmission(any(), any(), any());
        for (Response response : List.of(submit(body(email), null), link(linkBody(email)))) {
            response.then().statusCode(503)
                    .header("Retry-After", "30")
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("SERVICE_UNAVAILABLE"));
        }
        verifyNoInteractions(writer);
        assertTrue(captured.isEmpty());

        doNothing().when(security).verifySubmission(any(), any(), any());
        submit(body(email), null).then().statusCode(202);
    }

    @Test
    void oneSolvedChallengeBuysOneCallOnTheThreeAccountFormsTogether() {
        String usedByLogin = "solved-" + UUID.randomUUID();
        login(email(), usedByLogin).then().statusCode(401);
        for (Response response : List.of(submit(body(email(), usedByLogin), null),
                link(linkBody(email(), " " + usedByLogin + " ")))) {
            response.then().statusCode(422)
                    .header("Retry-After", nullValue())
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("VALIDATION_ERROR"))
                    .body("fieldErrors.challengeToken", equalTo("INVALID"));
        }

        String usedByRequest = "solved-" + UUID.randomUUID();
        submit(body(email(), usedByRequest), null).then().statusCode(202);
        /* The same form again is a reuse too, not a replay: other details, same challenge. */
        submit(body(email(), usedByRequest), null).then().statusCode(422)
                .body("fieldErrors.challengeToken", equalTo("INVALID"));
        link(linkBody(email(), usedByRequest)).then().statusCode(422)
                .body("fieldErrors.challengeToken", equalTo("INVALID"));
        login(email(), usedByRequest).then().statusCode(422)
                .body("fieldErrors.challengeToken", equalTo("INVALID"));

        String usedByLink = "solved-" + UUID.randomUUID();
        link(linkBody(email(), usedByLink)).then().statusCode(202);
        submit(body(email(), usedByLink), null).then().statusCode(422)
                .body("fieldErrors.challengeToken", equalTo("INVALID"));

        verify(writer, times(1)).storeJoined(any());
        verify(rateLimiter, times(1)).checkKey(eq(PublicFormAction.ACCOUNT_LINK_REQUEST), eq("EMAIL"),
                any(), eq(3));
    }

    @Test
    void theNetworkBucketAndTheEmailBucketBothAnswerTooManyRequests() throws InterruptedException {
        /* Fixed windows of an hour: do not start counting to three right before one ends. */
        long left = 3_600 - Instant.now().getEpochSecond() % 3_600;
        if (left < 10) Thread.sleep((left + 1) * 1_000);
        String email = email();
        for (int attempt = 0; attempt < 3; attempt++) {
            submit(body(email), "request-attempt-" + attempt + "-" + UUID.randomUUID())
                    .then().statusCode(202);
        }
        submit(body(" " + email.toUpperCase() + " "), "request-attempt-3-" + UUID.randomUUID())
                .then().statusCode(429)
                .header("Retry-After", matchesPattern("\\d+"))
                .header("Cache-Control", "no-store")
                .body("code", equalTo("RATE_LIMITED"));
        assertEquals(2, only(email).repeatCount, "the refused attempt stored nothing");

        String asked = email();
        for (int attempt = 0; attempt < 3; attempt++) {
            link(linkBody(asked)).then().statusCode(202);
        }
        link(linkBody(asked)).then().statusCode(429).body("code", equalTo("RATE_LIMITED"));
        assertEquals(4, captured.size(), "the one notice and three new-link tasks");

        doThrow(new PublicFormRateLimitException(60)).when(rateLimiter).checkIp(any(), any());
        for (Response response : List.of(submit(body(email()), null), link(linkBody(email())))) {
            response.then().statusCode(429)
                    .header("Retry-After", "60")
                    .body("code", equalTo("RATE_LIMITED"));
        }
    }

    @Test
    void bodiesAreCappedAndFieldsAreValidated() {
        String oversized = "{\"message\":\"" + "x".repeat(17 * 1024) + "\"}";
        for (String path : new String[]{"/requests", "/link-requests", "/requests/", "/link-requests;v=1"}) {
            given().urlEncodingEnabled(false).contentType("application/json").body(oversized)
                    .when().post(BASE + path)
                    .then().statusCode(413)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("PAYLOAD_TOO_LARGE"));
        }
        for (String path : new String[]{"/requests", "/link-requests"}) {
            given().contentType("application/json").body("{\"email\":")
                    .when().post(BASE + path)
                    .then().statusCode(400)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("INVALID_REQUEST"));
        }

        given().contentType("application/json").body("{\"companyCountryCode\":\"Belgium\","
                        + "\"email\":\"a@x.be\\u0001\",\"phone\":\"" + "1".repeat(51) + "\","
                        + "\"message\":\"" + "m".repeat(1_001) + "\"}")
                .when().post(BASE + "/requests")
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.companyName", equalTo("REQUIRED"))
                .body("fieldErrors.companyCountryCode", equalTo("INVALID"))
                .body("fieldErrors.vatNumber", equalTo("REQUIRED"))
                .body("fieldErrors.contactName", equalTo("REQUIRED"))
                .body("fieldErrors.email", equalTo("INVALID"))
                .body("fieldErrors.phone", equalTo("TOO_LONG"))
                .body("fieldErrors.message", equalTo("TOO_LONG"))
                .body("fieldErrors.privacyAccepted", equalTo("REQUIRED"));
        link(Map.of("formToken", "form-token")).then().statusCode(422)
                .body("fieldErrors.email", equalTo("REQUIRED"));
        link(Map.of("email", "geen adres", "formToken", "form-token")).then().statusCode(422)
                .body("fieldErrors.email", equalTo("INVALID"));
        verifyNoInteractions(security);
        verifyNoInteractions(writer);

        /* An unknown language is not a reason to refuse; a lower-case country is upper-cased. */
        String email = email();
        Map<String, Object> lenient = body(email);
        lenient.put("language", "xx");
        lenient.put("companyCountryCode", "be");
        submit(lenient, null).then().statusCode(202);
        assertEquals("EN", only(email).language);
        assertEquals("BE", only(email).companyCountryCode);
    }

    @Test
    void aRepeatAnAddressWithALoginAndADroppedRequestAllReadTheSame() {
        String email = email();
        Response first = submit(body(email), "first-" + UUID.randomUUID());
        Map<String, Object> other = body(email);
        other.put("companyName", "Another Company NV");
        Response repeat = submit(other, "repeat-" + UUID.randomUUID());

        String holder = email();
        long customerId = customer();
        accounts.grant(customerId, holder, "An Peeters", Language.NL);
        Response withLogin = submit(body(holder), null);

        /* The same key and payload again is a replay of the stored answer. */
        String key = "replay-" + UUID.randomUUID();
        String replayed = email();
        Response original = submit(body(replayed), key);
        Response replay = submit(body(replayed), key);
        submit(body(email()), key).then().statusCode(409);

        QuarkusTransaction.requiringNew().run(() -> {
            for (int open = 0; open < LoginRequestWriter.INTAKE_CEILING; open++) {
                CustomerLoginRequestEntity row = new CustomerLoginRequestEntity();
                row.reference = LoginRequestWriter.newReference();
                row.status = "PENDING";
                row.source = "ORDER_SCREEN";
                row.language = "NL";
                row.companyName = "Filler";
                row.contactName = "Filler";
                row.email = email();
                row.createdAt = Instant.now();
                row.updatedAt = row.createdAt;
                row.persist();
            }
        });
        String turnedAway = email();
        Response dropped = submit(body(turnedAway), null);

        for (Response response : List.of(first, repeat, withLogin, original, replay, dropped)) {
            assertEquals(202, response.statusCode());
            assertEquals("no-store", response.header("Cache-Control"));
            assertTrue(response.asString().matches(ACCEPTED), response.asString());
        }
        assertEquals(first.<String>path("reference"), repeat.<String>path("reference"));
        assertEquals(original.asString(), replay.asString());
        assertEquals(1, only(email).repeatCount);
        assertEquals("PENDING", only(holder).status);
        assertEquals(0, only(replayed).repeatCount);
        assertEquals(0, count(turnedAway));
        assertNotEquals(first.<String>path("reference"), dropped.<String>path("reference"));
    }

    @Test
    void aCollisionWithABackgroundRouteIsRetriedOnceAndOnlyOnce() {
        PersistenceException collision = new PersistenceException(
                new org.hibernate.exception.ConstraintViolationException("duplicate key",
                        new SQLException("unique"), "uq_customer_login_request_pending_email"));
        String email = email();
        doThrow(collision).doCallRealMethod().when(writer).storeJoined(any());

        submit(body(email), null)
                .then().statusCode(202)
                .body("reference", matchesPattern("^LGN-[0-9A-F]{20}$"));

        verify(writer, times(2)).storeJoined(any());
        verify(requests, times(2)).submit(any());
        assertEquals(1, count(email));

        String second = email();
        doThrow(collision).when(writer).storeJoined(any());
        submit(body(second), null).then().statusCode(500);

        verify(writer, times(4)).storeJoined(any());
        assertEquals(0, count(second));
    }

    @Test
    void theNewLinkFormAnswersBeforeAnythingIsLookedUp() {
        String holder = email();
        long customerId = customer();
        accounts.grant(customerId, holder, "An Peeters", Language.NL);
        String stranger = email();

        Response known = link(linkBody(" " + holder.toUpperCase() + " "));
        Response unknown = link(linkBody(stranger));

        for (Response response : List.of(known, unknown)) {
            assertEquals(202, response.statusCode());
            assertEquals("no-store", response.header("Cache-Control"));
            assertEquals("{\"status\":\"RECEIVED\"}", response.asString());
        }
        verify(rateLimiter, times(2)).checkIp(eq(PublicFormAction.ACCOUNT_LINK_REQUEST), any());
        verify(rateLimiter).checkKey(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", holder, 3);
        verify(rateLimiter).checkKey(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", stranger, 3);
        verifyNoInteractions(writer);
        verify(requests, never()).requestNewLink(any(), any());
        assertEquals(2, captured.size());
        assertEquals(0, count(holder));

        captured.removeFirst().run();
        captured.removeFirst().run();

        verify(writer, times(2)).storeDetached(any());
        verify(writer, never()).storeJoined(any());
        assertEquals("NEW_LINK", only(holder).source);
        assertEquals(0, count(stranger));
        assertEquals(1, captured.size(), "one notice, for the address that has a login");
        verify(housekeeping, never()).purge();
    }

    // ------------------------------------------------------------------ fixtures

    private static Response submit(Map<String, Object> body, String idempotencyKey) {
        var request = given().contentType("application/json").body(body);
        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
        return request.when().post(BASE + "/requests");
    }

    private static Response link(Map<String, Object> body) {
        return given().contentType("application/json").body(body).when().post(BASE + "/link-requests");
    }

    private static Response login(String email, String challenge) {
        return given().contentType("application/json")
                .body(Map.of("email", email, "password", "not-the-password",
                        "formToken", "form-token", "challengeToken", challenge))
                .when().post(BASE + "/session");
    }

    private static Map<String, Object> body(String email) {
        return body(email, "solved-" + UUID.randomUUID());
    }

    private static Map<String, Object> body(String email, String challenge) {
        Map<String, Object> body = new HashMap<>();
        body.put("language", "fr");
        body.put("companyName", "Bloemen Peeters BV");
        body.put("companyCountryCode", "BE");
        body.put("vatNumber", "BE0123456789");
        body.put("contactName", "An Peeters");
        body.put("email", email);
        body.put("phone", "+32 14 00 00 00");
        body.put("message", "Graag een login.");
        body.put("privacyAccepted", true);
        body.put("website", "");
        body.put("formToken", "form-token");
        body.put("challengeToken", challenge);
        return body;
    }

    private static Map<String, Object> linkBody(String email) {
        return linkBody(email, "solved-" + UUID.randomUUID());
    }

    private static Map<String, Object> linkBody(String email, String challenge) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("language", "nl");
        body.put("formToken", "form-token");
        body.put("challengeToken", challenge);
        return body;
    }

    private long customer() {
        Customer customer = customers.create(new Customer(null, "Bloemen Peeters BV", "Contact Peeters",
                email(), null, "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));
        createdCustomers.add(customer.id());
        return customer.id();
    }

    private static long count(String email) {
        return QuarkusTransaction.requiringNew().call(() -> CustomerLoginRequestEntity.count("email", email));
    }

    private static CustomerLoginRequestEntity only(String email) {
        List<CustomerLoginRequestEntity> rows = QuarkusTransaction.requiringNew().call(() ->
                CustomerLoginRequestEntity.list("email", email));
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    private static void clearRequests() {
        QuarkusTransaction.requiringNew().run(() -> CustomerLoginRequestEntity.deleteAll());
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
