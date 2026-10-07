package be.enrosed.account;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The customer's endpoints through the real HTTP pipeline, with the real rate buckets for
 * e-mail and solved challenge. Only the per-network bucket and the form security check are
 * stubbed: all calls here come from one address, and Turnstile counts as configured.
 */
@QuarkusTest
class PublicCustomerAccountResourceHttpTest {
    private static final String BASE = "/api/v1/public/account";
    private static final String PASSWORD = "roses-in-a-dome";
    private static final String UNAUTHORIZED = "{\"code\":\"INVALID_CREDENTIALS\","
            + "\"message\":\"E-mail address or password is incorrect\",\"fieldErrors\":{}}";

    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @InjectSpy PublicFormRateLimiter rateLimiter;
    @InjectMock PublicFormSecurityService security;

    private final List<Long> createdCustomers = new ArrayList<>();

    @BeforeEach
    void oneNetworkAddressIsNotTheSubject() {
        doNothing().when(rateLimiter).checkIp(any(), any());
    }

    @AfterEach
    void removeCustomers() {
        createdCustomers.forEach(customers::delete);
        createdCustomers.clear();
    }

    @Test
    void loginAnswersASessionWithTheProfileAndClearsExpiredSessions() {
        String email = email();
        long accountId = activeLogin(email);
        long expired = QuarkusTransaction.requiringNew().call(() -> {
            CustomerSessionEntity old = new CustomerSessionEntity();
            old.accountId = accountId;
            old.tokenHash = AccountTokens.hash(AccountTokens.newSessionToken());
            old.createdAt = Instant.now().minus(40, ChronoUnit.DAYS);
            old.lastSeenAt = old.createdAt;
            old.expiresAt = Instant.now().minus(10, ChronoUnit.DAYS);
            old.persist();
            return old.id;
        });

        String token = login(email, PASSWORD)
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("sessionToken", matchesPattern("^ecs1_[A-Za-z0-9_-]{43}$"))
                .body("expiresAt", notNullValue())
                .body("profile.email", equalTo(email))
                .body("profile.contactName", equalTo("An Peeters"))
                .body("profile.company", equalTo("Bloemen Peeters BV"))
                .body("profile.companyCountryCode", equalTo("BE"))
                .body("profile.vatNumber", equalTo("BE0123456789"))
                .body("profile.phone", nullValue())
                .body("profile.language", equalTo("NL"))
                .extract().path("sessionToken");

        assertEquals(0, countSessions("id", expired), "housekeeping ran after the login");
        verify(security).verifySubmission(eq(PublicFormPurpose.ACCOUNT), eq("form-token"), any());
        verify(rateLimiter).checkIp(eq(PublicFormAction.ACCOUNT_LOGIN), any());

        given().header("Authorization", "Bearer " + token)
                .when().get(BASE + "/session")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("expiresAt", notNullValue())
                .body("profile.email", equalTo(email))
                .body("sessionToken", nullValue());
    }

    @Test
    void everyRefusedLoginHasTheSameBodyAndNoBrowserChallenge() {
        String active = email();
        activeLogin(active);
        String invited = email();
        accounts.grant(customer(), invited, null, Language.NL);
        String withdrawn = email();
        accounts.withdraw(activeLogin(withdrawn));

        Map<String, Response> refusals = new HashMap<>();
        refusals.put("unknown e-mail", login(email(), PASSWORD));
        refusals.put("wrong password", login(active, "not-the-password"));
        refusals.put("invited", login(invited, PASSWORD));
        refusals.put("withdrawn", login(withdrawn, PASSWORD));
        refusals.put("e-mail that does not normalise", login(active + "\u0001", PASSWORD));

        refusals.forEach((cause, response) -> {
            assertEquals(401, response.statusCode(), cause);
            assertEquals(UNAUTHORIZED, response.asString(), cause);
            assertNull(response.header("WWW-Authenticate"), cause);
            assertEquals("no-store", response.header("Cache-Control"), cause);
        });
        /* The address with a control character has no bucket of its own and never reaches the login. */
        verify(rateLimiter, times(1)).checkKey(eq(PublicFormAction.ACCOUNT_LOGIN), eq("EMAIL"),
                eq(active), eq(10));
        verify(rateLimiter).checkKey(eq(PublicFormAction.ACCOUNT_LOGIN), eq("EMAIL"), eq(null), eq(10));
    }

    @Test
    void anAddressWithSpacesAndCapitalsIsTheSameLoginAndTheSameBucket() {
        String email = email();
        activeLogin(email);

        login(email, PASSWORD).then().statusCode(200);
        login(" " + email.toUpperCase(), PASSWORD)
                .then().statusCode(200)
                .body("profile.email", equalTo(email));

        verify(rateLimiter, times(2)).checkKey(eq(PublicFormAction.ACCOUNT_LOGIN), eq("EMAIL"),
                eq(email), eq(10));
    }

    @Test
    void anInvalidChallengeNeverReachesTheEmailBucket() {
        String email = email();
        activeLogin(email);
        doThrow(new PublicFormValidationException(Map.of("challengeToken", "INVALID")))
                .when(security).verifySubmission(any(), any(), any());

        login(email, PASSWORD)
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.challengeToken", equalTo("INVALID"));

        verify(rateLimiter, never()).checkKey(any(), any(), any(), anyInt());
    }

    @Test
    void oneSolvedChallengeBuysOneLoginAttempt() {
        String email = email();
        activeLogin(email);
        String challenge = "solved-" + UUID.randomUUID();

        login(email, PASSWORD, challenge).then().statusCode(200);
        login(email, PASSWORD, " " + challenge + " ")
                .then().statusCode(422)
                .header("Retry-After", nullValue())
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.challengeToken", equalTo("INVALID"));

        verify(rateLimiter, times(1)).checkKey(eq(PublicFormAction.ACCOUNT_LOGIN), eq("EMAIL"),
                eq(email), eq(10));
    }

    @Test
    void theNetworkBucketAndTheEmailBucketBothAnswerTooManyRequests() throws InterruptedException {
        String email = email();
        activeLogin(email);
        /* The buckets are fixed windows of fifteen minutes: do not start counting to ten right
           before one ends. */
        long intoWindow = Instant.now().getEpochSecond() % PublicFormAction.ACCOUNT_LOGIN.windowSeconds();
        long left = PublicFormAction.ACCOUNT_LOGIN.windowSeconds() - intoWindow;
        if (left < 20) Thread.sleep((left + 1) * 1000);
        for (int attempt = 0; attempt < 10; attempt++) {
            login(email, "not-the-password").then().statusCode(401);
        }
        login(email, PASSWORD)
                .then().statusCode(429)
                .header("Retry-After", notNullValue())
                .header("Cache-Control", "no-store")
                .body("code", equalTo("RATE_LIMITED"));

        doThrow(new PublicFormRateLimitException(60)).when(rateLimiter)
                .checkIp(eq(PublicFormAction.ACCOUNT_LOGIN), any());
        login(email(), PASSWORD)
                .then().statusCode(429)
                .header("Retry-After", "60")
                .body("code", equalTo("RATE_LIMITED"));
        verify(security, times(11)).verifySubmission(any(), any(), any());
    }

    @Test
    void aLoginWithoutAddressOrWithAnOverlongPasswordIsAValidationError() {
        given().contentType("application/json")
                .body(Map.of("password", "x".repeat(73), "formToken", "form-token"))
                .when().post(BASE + "/session")
                .then().statusCode(422)
                .body("fieldErrors.email", equalTo("REQUIRED"))
                .body("fieldErrors.password", equalTo("TOO_LONG"));
        given().contentType("application/json")
                .body(Map.of("email", "a".repeat(250) + "@x.be", "formToken", "form-token"))
                .when().post(BASE + "/session")
                .then().statusCode(422)
                .body("fieldErrors.email", equalTo("TOO_LONG"))
                .body("fieldErrors.password", equalTo("REQUIRED"));
        verify(security, never()).verifySubmission(any(), any(), any());
    }

    @Test
    void withAllHashingPermitsTakenALoginAnswersServiceUnavailable() {
        CustomerAccountService.BCRYPT.acquireUninterruptibly(4);
        try {
            login(email(), PASSWORD)
                    .then().statusCode(503)
                    .header("Retry-After", "30")
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("SERVICE_UNAVAILABLE"));
        } finally {
            CustomerAccountService.BCRYPT.release(4);
        }
    }

    @Test
    void bodiesAreCappedAndMustBeJson() {
        given().contentType("application/json")
                .body("{\"email\":\"" + "x".repeat(17 * 1024) + "\"}")
                .when().post(BASE + "/session")
                .then().statusCode(413)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("PAYLOAD_TOO_LARGE"));
        for (String path : new String[]{"/session", "/activation/inspect", "/activation"}) {
            given().contentType("application/json").body("{\"email\":")
                    .when().post(BASE + path)
                    .then().statusCode(400)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("INVALID_REQUEST"));
            given().contentType("application/json").body("null")
                    .when().post(BASE + path)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
        given().contentType("application/json")
                .body("{\"token\":\"" + "x".repeat(17 * 1024) + "\"}")
                .when().post(BASE + "/activation")
                .then().statusCode(413);
    }

    @Test
    void theSessionEndpointNeedsALiveCustomerBearer() {
        String sessionInvalid = "{\"code\":\"SESSION_INVALID\","
                + "\"message\":\"The session is no longer valid\",\"fieldErrors\":{}}";
        Response none = given().when().get(BASE + "/session");
        Response unknown = given().header("Authorization", "Bearer ecs1_" + "A".repeat(43))
                .when().get(BASE + "/session");
        for (Response response : List.of(none, unknown)) {
            assertEquals(401, response.statusCode());
            assertEquals(sessionInvalid, response.asString());
            assertNull(response.header("WWW-Authenticate"));
            assertEquals("no-store", response.header("Cache-Control"));
        }
    }

    @Test
    void logoutEndsTheSessionWithOrWithoutABody() {
        String email = email();
        long accountId = activeLogin(email);
        String first = login(email, PASSWORD).then().statusCode(200).extract().path("sessionToken");
        String second = login(email, PASSWORD).then().statusCode(200).extract().path("sessionToken");

        given().header("Authorization", "Bearer " + first)
                .when().post(BASE + "/session/logout")
                .then().statusCode(204)
                .header("Cache-Control", "no-store");
        assertEquals(0, countSessions("tokenHash", AccountTokens.hash(first)));
        given().header("Authorization", "Bearer " + first)
                .when().get(BASE + "/session")
                .then().statusCode(401)
                .body("code", equalTo("SESSION_INVALID"));
        given().header("Authorization", "Bearer " + first)
                .when().post(BASE + "/session/logout")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + second)
                .contentType("application/json").body("{}")
                .when().post(BASE + "/session/logout")
                .then().statusCode(204);
        assertEquals(0, countSessions("tokenHash", AccountTokens.hash(second)));
        given().when().post(BASE + "/session/logout").then().statusCode(204);
        assertEquals(1, countSessions("accountId", accountId), "only the activation's session is left");
    }

    @Test
    void logoutRefusesMoreThanOneKilobyte() {
        String email = email();
        activeLogin(email);
        String token = login(email, PASSWORD).then().statusCode(200).extract().path("sessionToken");

        given().header("Authorization", "Bearer " + token)
                .contentType("text/plain").body("x".repeat(2 * 1024))
                .when().post(BASE + "/session/logout")
                .then().statusCode(413)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("PAYLOAD_TOO_LARGE"));
        given().header("Authorization", "Bearer " + token)
                .when().get(BASE + "/session")
                .then().statusCode(200);
    }

    @Test
    void aLinkIsInspectedThenUsedOnceToChooseAPassword() {
        String email = email();
        var grant = accounts.grant(customer(), email, "An Peeters", Language.NL);

        given().contentType("application/json").body(Map.of("token", grant.rawToken()))
                .when().post(BASE + "/activation/inspect")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("email", equalTo(email))
                .body("company", equalTo("Bloemen Peeters BV"))
                .body("expiresAt", notNullValue());
        for (String token : new String[]{"eci1_" + "A".repeat(43), "not-a-token", grant.rawToken() + "\n"}) {
            given().contentType("application/json").body(Map.of("token", token))
                    .when().post(BASE + "/activation/inspect")
                    .then().statusCode(422)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("VALIDATION_ERROR"))
                    .body("fieldErrors.token", equalTo("INVALID"));
        }
        given().contentType("application/json").body("{}")
                .when().post(BASE + "/activation/inspect")
                .then().statusCode(422)
                .body("fieldErrors.token", equalTo("INVALID"));

        given().contentType("application/json")
                .body(Map.of("token", grant.rawToken(), "password", "too-short"))
                .when().post(BASE + "/activation")
                .then().statusCode(422)
                .body("fieldErrors.password", equalTo("TOO_SHORT"));
        String session = given().contentType("application/json")
                .body(Map.of("token", grant.rawToken(), "password", PASSWORD))
                .when().post(BASE + "/activation")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("sessionToken", matchesPattern("^ecs1_[A-Za-z0-9_-]{43}$"))
                .body("profile.email", equalTo(email))
                .extract().path("sessionToken");
        given().header("Authorization", "Bearer " + session)
                .when().get(BASE + "/session")
                .then().statusCode(200);

        given().contentType("application/json")
                .body(Map.of("token", grant.rawToken(), "password", PASSWORD))
                .when().post(BASE + "/activation")
                .then().statusCode(422)
                .body("fieldErrors.token", equalTo("INVALID"));
        given().contentType("application/json").body(Map.of("token", grant.rawToken()))
                .when().post(BASE + "/activation/inspect")
                .then().statusCode(422)
                .body("fieldErrors.token", equalTo("INVALID"));
        verify(rateLimiter, times(9)).checkIp(eq(PublicFormAction.ACCOUNT_ACTIVATE), any());
        verify(security, never()).verifySubmission(any(), any(), any());

        doThrow(new PublicFormRateLimitException(120)).when(rateLimiter)
                .checkIp(eq(PublicFormAction.ACCOUNT_ACTIVATE), any());
        given().contentType("application/json").body(Map.of("token", grant.rawToken()))
                .when().post(BASE + "/activation/inspect")
                .then().statusCode(429)
                .header("Retry-After", "120");
    }

    @Test
    void staffAndCustomerCredentialsOpenNothingOnEachOthersSide() {
        String email = email();
        activeLogin(email);
        String token = login(email, PASSWORD).then().statusCode(200).extract().path("sessionToken");

        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .when().get(BASE + "/session")
                .then().statusCode(401)
                .body("code", equalTo("SESSION_INVALID"));
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .contentType("application/json").body(Map.of("token", "eci1_" + "A".repeat(43)))
                .when().post(BASE + "/activation/inspect")
                .then().statusCode(422);
        given().auth().preemptive().basic("emre", "named-auth-test-password")
                .contentType("application/json")
                .body(Map.of("email", email, "password", "not-the-password", "formToken", "form-token"))
                .when().post(BASE + "/session")
                .then().statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/customers")
                .then().statusCode(401);
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/customer-logins?customerId=1")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------ fixtures

    private static Response login(String email, String password) {
        return login(email, password, "solved-" + UUID.randomUUID());
    }

    private static Response login(String email, String password, String challenge) {
        return given().contentType("application/json")
                .body(Map.of("email", email, "password", password,
                        "formToken", "form-token", "challengeToken", challenge))
                .when().post(BASE + "/session");
    }

    private long customer() {
        Customer customer = customers.create(new Customer(null, "Bloemen Peeters BV", "Contact Peeters",
                email(), null, "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));
        createdCustomers.add(customer.id());
        return customer.id();
    }

    /** A committed ACTIVE login with PASSWORD on a customer of its own; returns the account id. */
    private long activeLogin(String email) {
        var grant = accounts.grant(customer(), email, "An Peeters", Language.NL);
        accounts.activate(grant.rawToken(), PASSWORD);
        return grant.accountId();
    }

    private static long countSessions(String field, Object value) {
        return QuarkusTransaction.requiringNew().call(() -> CustomerSessionEntity.count(field, value));
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
