package be.enrosed.account;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.sales.adapter.out.persistence.SalesCustomerMessageEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.PublicQuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.WebsiteQuoteSettingsService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The quote request of a logged-in customer through the real HTTP pipeline, with the website
 * price switch OFF in every test: a valid session is the only thing that shows a price. The
 * quote service, the session guard and the buckets are the real ones; only the per-network
 * bucket and the form security check are stubbed.
 */
@QuarkusTest
class CustomerQuoteResourceHttpTest {
    private static final String BASE = "/api/v1/public/account/quotes";
    private static final String ANONYMOUS = "/api/v1/public/quotes";
    private static final String PASSWORD = "roses-in-a-dome";
    private static final String ORDERING_SWITCH = "enrosed.website.ordering.enabled";
    private static final String SESSION_INVALID = "{\"code\":\"SESSION_INVALID\","
            + "\"message\":\"The session is no longer valid\",\"fieldErrors\":{}}";

    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @Inject SalesOrderService salesOrders;
    @Inject WebsiteQuoteSettingsService quoteSettings;
    @Inject EntityManager em;
    @InjectSpy PublicQuoteService quotes;
    @InjectSpy CustomerSessionGuard guard;
    @InjectSpy PublicFormRateLimiter rateLimiter;
    @InjectMock PublicFormSecurityService security;

    private final QuoteFixture fixture = new QuoteFixture();
    private long productId;

    @BeforeEach
    void hidePricesForVisitors() {
        doNothing().when(rateLimiter).checkIp(any(), any());
        productId = fixture.orderableProduct(em);
        quoteSettings.update(false);
    }

    @AfterEach
    void cleanUp() {
        quoteSettings.update(true);
        fixture.remove(em, customers);
    }

    @Test
    void aSessionSeesThePriceListThePreviewAndItsOwnQuoteWhileVisitorsSeeNone() {
        Login login = login();
        long customersBefore = customerCount();

        given().header("Authorization", "Bearer " + login.token()).queryParam("language", "NL")
                .when().get(BASE + "/configuration")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("pricesVisible", equalTo(true))
                .body("products.find { it.productId == " + productId + " }.unitPriceNet", equalTo(10.0f))
                .body("countries.find { it.code == 'BE' }.minimumOrderNet", notNullValue());
        given().queryParam("language", "NL")
                .when().get(ANONYMOUS + "/configuration")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(false))
                .body("products.find { it.productId == " + productId + " }.unitPriceNet", nullValue());

        given().header("Authorization", "Bearer " + login.token())
                .contentType("application/json").body(previewBody())
                .when().post(BASE + "/preview")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("pricesVisible", equalTo(true))
                .body("lines[0].unitPriceNet", equalTo(10.0f))
                .body("totals.goodsNet", equalTo(720.0f));
        verify(rateLimiter).checkIp(eq(PublicFormAction.QUOTE_PREVIEW), any());

        String reference = submit(login.token(), "account-" + UUID.randomUUID(), submitBody(Map.of()))
                .then().statusCode(201)
                .header("Cache-Control", "no-store")
                .body("status", equalTo("RECEIVED"))
                .body("bindingStatus", equalTo("REQUEST_RECEIVED_NOT_BINDING"))
                .body("estimate.pricesVisible", equalTo(true))
                .body("estimate.lines[0].unitPriceNet", equalTo(10.0f))
                .body("estimate.totals.goodsNet", equalTo(720.0f))
                .extract().path("reference");

        SalesOrder order = fixture.order(salesOrders, reference);
        assertEquals(login.customerId(), order.customerId(), "the quote hangs on the customer of the login");
        assertEquals(customersBefore, customerCount(), "no customer was created");
        assertEquals(72, order.lines().getFirst().quantity());
        assertEquals(0, new BigDecimal("10").compareTo(order.lines().getFirst().unitPriceEur()));
        assertTrue(order.internalNotes().startsWith("[WEBSITE_AANVRAAG] " + reference + "\n"),
                order.internalNotes());
        assertTrue(order.internalNotes().contains("\nAangevraagd via klantlogin " + login.email()),
                order.internalNotes());
        assertEquals("Bloemen Peeters BV", customers.get(login.customerId()).company(),
                "the body's company name never reaches the customer record");
        /* The page's own quote form token is checked; ACCOUNT_QUOTE is a namespace only. */
        verify(security).verifySubmission(eq(PublicFormPurpose.QUOTE), eq("form-token"), any());
        verify(rateLimiter).checkIp(eq(PublicFormAction.ACCOUNT_QUOTE_SUBMIT), any());
        verify(quotes, never()).submit(any());
    }

    @Test
    void withoutASessionOrWithAWithdrawnOneEveryEndpointAnswers401WithoutAnyPrice() {
        Login login = login();
        Login withdrawn = login();
        accounts.withdraw(withdrawn.accountId());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("no header", null);
        headers.put("withdrawn login", "Bearer " + withdrawn.token());
        headers.put("unknown token", "Bearer " + AccountTokens.newSessionToken());
        headers.put("an invitation token", "Bearer " + AccountTokens.newInvitationToken());
        headers.put("lower-case scheme", "bearer " + login.token());

        headers.forEach((cause, header) -> {
            List<Response> answers = List.of(
                    with(header).queryParam("language", "EN").when().get(BASE + "/configuration"),
                    with(header).contentType("application/json").body(previewBody())
                            .when().post(BASE + "/preview"),
                    with(header).contentType("application/json")
                            .header("Idempotency-Key", "account-" + UUID.randomUUID())
                            .body(submitBody(Map.of())).when().post(BASE + "/requests"));
            for (Response answer : answers) {
                assertEquals(401, answer.statusCode(), cause);
                assertEquals(SESSION_INVALID, answer.asString(), cause);
                assertEquals("no-store", answer.header("Cache-Control"), cause);
                assertNull(answer.header("WWW-Authenticate"), cause);
            }
        });
        verify(quotes, never()).configuration(anyString());
        verify(quotes, never()).previewForCustomer(any(), anyLong());
        verify(quotes, never()).submitForCustomer(any(), anyLong(), anyString());
        assertTrue(fixture.orders(salesOrders, login.customerId()).isEmpty());
        assertTrue(fixture.orders(salesOrders, withdrawn.customerId()).isEmpty());
    }

    @Test
    void aStoredAnswerIsNotReplayedToSomeoneWhoseLoginWasWithdrawn() {
        Login login = login();
        String key = "account-" + UUID.randomUUID();
        submit(login.token(), key, submitBody(Map.of())).then().statusCode(201);

        accounts.withdraw(login.accountId());

        Response answer = submit(login.token(), key, submitBody(Map.of()));
        assertEquals(401, answer.statusCode());
        assertEquals(SESSION_INVALID, answer.asString());
    }

    @Test
    void aRetryThatDiffersOnlyInIgnoredFieldsIsAReplayAndAnotherBasketIsAConflict() {
        Login login = login();
        String key = "account-" + UUID.randomUUID();

        String reference = submit(login.token(), key, submitBody(Map.of()))
                .then().statusCode(201).extract().path("reference");
        submit(login.token(), key, submitBody(Map.of(
                "companyName", "Another Company NV", "companyCountryCode", "FR",
                "email", "someone-else@example.com", "vatNumber", "FR00999999999",
                "loginRequested", true)))
                .then().statusCode(201)
                .body("reference", equalTo(reference))
                .body("estimate.lines[0].unitPriceNet", equalTo(10.0f));
        assertEquals(1, fixture.orders(salesOrders, login.customerId()).size(), "one quote, not two");

        submit(login.token(), key, submitBody(Map.of("notes", "Toch liever volgende week.")))
                .then().statusCode(409)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.idempotencyKey", equalTo("CONFLICT"));
        assertEquals(1, fixture.orders(salesOrders, login.customerId()).size());
    }

    @Test
    void aKeyStoredForALoggedInCustomerIsNotReplayedByTheAnonymousEndpoint() {
        Login login = login();
        String key = "account-" + UUID.randomUUID();
        submit(login.token(), key, submitBody(Map.of())).then().statusCode(201);

        /* A shared namespace would answer the stored 201 or, with this other payload, a 409. Here
           the key is unknown to the anonymous route, which goes on to refuse the missing consent. */
        given().contentType("application/json").header("Idempotency-Key", key)
                .body(submitBody(Map.of("privacyAccepted", false)))
                .when().post(ANONYMOUS + "/requests")
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.privacyAccepted", equalTo("REQUIRED"))
                .body("fieldErrors.idempotencyKey", nullValue())
                .body("estimate", nullValue());
        verify(quotes, never()).submit(any());
    }

    @Test
    void thePriceListCanBeReadAHundredAndTwentyTimesAnHourPerLogin() {
        Login busy = login();
        Login other = login();
        for (int read = 0; read < 120; read++) {
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_QUOTE_READ, "ACCOUNT",
                    String.valueOf(busy.accountId()), 120);
        }

        Response refused = given().header("Authorization", "Bearer " + busy.token())
                .when().get(BASE + "/configuration");
        assertEquals(429, refused.statusCode());
        assertEquals("RATE_LIMITED", refused.path("code"));
        assertTrue(Integer.parseInt(refused.header("Retry-After")) > 0);
        assertEquals("no-store", refused.header("Cache-Control"));
        assertFalse(refused.asString().contains("unitPriceNet"));

        given().header("Authorization", "Bearer " + other.token())
                .when().get(BASE + "/configuration")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(true));
    }

    @Test
    void aFailingGuardNeverLeavesABodyWithPrices() {
        Login login = login();
        doThrow(new IllegalStateException("session store unreachable")).when(guard).require(any());

        List<Response> answers = List.of(
                with("Bearer " + login.token()).when().get(BASE + "/configuration"),
                with("Bearer " + login.token()).contentType("application/json").body(previewBody())
                        .when().post(BASE + "/preview"),
                with("Bearer " + login.token()).contentType("application/json")
                        .body(submitBody(Map.of())).when().post(BASE + "/requests"));

        for (Response answer : answers) {
            assertEquals(500, answer.statusCode());
            String body = answer.asString();
            assertFalse(body.contains("unitPriceNet"), body);
            assertFalse(body.contains("pricesVisible"), body);
            assertFalse(body.contains("totalNet"), body);
        }
        verify(quotes, never()).configuration(anyString());
        verify(quotes, never()).previewForCustomer(any(), anyLong());
        verify(quotes, never()).submitForCustomer(any(), anyLong(), anyString());
    }

    @Test
    void invalidInputTheHoneypotAndOversizedBodiesFollowThePublicContract() {
        Login login = login();

        given().header("Authorization", "Bearer " + login.token()).queryParam("language", "XX")
                .when().get(BASE + "/configuration")
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("fieldErrors.language", equalTo("UNSUPPORTED"));

        submit(login.token(), null, submitBody(Map.of("privacyAccepted", false, "companyName", "")))
                .then().statusCode(422)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.privacyAccepted", equalTo("REQUIRED"))
                .body("fieldErrors.companyName", nullValue());

        submit(login.token(), null, submitBody(Map.of("website", "https://spam.example")))
                .then().statusCode(201)
                .body("reference", matchesPattern("^WEB-[0-9A-F]{20}$"))
                .body("estimate", nullValue());
        verify(quotes, never()).submitForCustomer(any(), anyLong(), anyString());

        given().header("Authorization", "Bearer " + login.token())
                .contentType("application/json").body("{")
                .when().post(BASE + "/requests")
                .then().statusCode(400).body("code", equalTo("INVALID_REQUEST"));
        given().header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .body("{\"padding\":\"" + "x".repeat(65 * 1024) + "\"}")
                .when().post(BASE + "/preview")
                .then().statusCode(413).body("code", equalTo("PAYLOAD_TOO_LARGE"));

        doThrow(new BusinessRuleException("Product ROSE-1 heeft geen kostprijs"))
                .when(quotes).submitForCustomer(any(), anyLong(), anyString());
        submit(login.token(), null, submitBody(Map.of("notes", "Een tweede poging")))
                .then().statusCode(409)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("QUOTE_REVIEW_REQUIRED"))
                .body("message", equalTo("The quote request could not be completed automatically"));
        assertTrue(fixture.orders(salesOrders, login.customerId()).isEmpty());
    }

    /**
     * The website sends the customer record's VAT number from a read-only field. A placeholder
     * staff typed there must not close the estimate: the number is the record's and is not
     * judged, in the estimate as in the submission.
     */
    @Test
    void aPlaceholderVatNumberOnTheCustomerRecordNeverClosesTheEstimate() {
        for (String recorded : new String[]{"-", "n/a", "nvt"}) {
            Customer customer = customers.create(new Customer(null, "Placeholder BV", "An Peeters",
                    QuoteFixture.email(), null, recorded, "BE", Language.NL, null, null, null, null,
                    null, null, null));
            fixture.adopt(customer.id());
            String email = QuoteFixture.email();
            var grant = accounts.grant(customer.id(), email, "An Peeters", Language.NL);
            String token = accounts.activate(grant.rawToken(), PASSWORD).sessionToken();
            String profileVat = given().header("Authorization", "Bearer " + token)
                    .when().get("/api/v1/public/account/session")
                    .then().statusCode(200).extract().path("profile.vatNumber");

            /* Exactly what the page posts: the profile's number, and once something else typed. */
            for (String sent : new String[]{profileVat, "x"}) {
                Map<String, Object> body = previewBody();
                if (sent != null) body.put("vatNumber", sent);
                given().header("Authorization", "Bearer " + token)
                        .contentType("application/json").body(body)
                        .when().post(BASE + "/preview")
                        .then().statusCode(200)
                        .header("Cache-Control", "no-store")
                        .body("totals.goodsNet", equalTo(720.0f));
            }
            submit(token, null, submitBody(Map.of("vatNumber", "x")))
                    .then().statusCode(201).body("estimate.totals.goodsNet", equalTo(720.0f));
        }

        /* The anonymous estimate still judges what a visitor types. */
        Map<String, Object> anonymous = previewBody();
        anonymous.put("vatNumber", "-");
        given().contentType("application/json").body(anonymous)
                .when().post(ANONYMOUS + "/preview")
                .then().statusCode(422).body("fieldErrors.vatNumber", equalTo("INVALID"));
    }

    /**
     * While customers can order, the old route holds the minimum order value too; with ordering
     * switched off it is the route it always was. A visitor's request is never held to it.
     */
    @Test
    void belowTheMinimumALoggedInRequestIsRefusedOnlyWhileOrderingIsOn() {
        Login login = login();
        Map<String, Object> small = fixture.submitBody(productId, 2, Map.of());

        submit(login.token(), "account-" + UUID.randomUUID(), small)
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.items", equalTo("MINIMUM_NOT_MET"))
                .body("fieldErrors.size()", equalTo(1));
        verify(quotes, never()).submitForCustomer(any(), anyLong(), anyString());
        assertTrue(fixture.orders(salesOrders, login.customerId()).isEmpty());
        anonymousRequestBelowTheMinimumIsTaken();

        System.setProperty(ORDERING_SWITCH, "false");
        try {
            String reference = submit(login.token(), "account-" + UUID.randomUUID(), small)
                    .then().statusCode(201)
                    .header("Cache-Control", "no-store")
                    .body("status", equalTo("RECEIVED"))
                    .body("bindingStatus", equalTo("REQUEST_RECEIVED_NOT_BINDING"))
                    .body("estimate.pricesVisible", equalTo(true))
                    .body("estimate.lines[0].unitPriceNet", equalTo(10.0f))
                    .body("estimate.totals.goodsNet", equalTo(240.0f))
                    .body("estimate.validation.meetsMinimum", equalTo(false))
                    .extract().path("reference");
            SalesOrder order = fixture.order(salesOrders, reference);
            assertEquals(24, order.lines().getFirst().quantity());
            assertTrue(order.internalNotes().contains("\nAangevraagd via klantlogin " + login.email()),
                    order.internalNotes());
            anonymousRequestBelowTheMinimumIsTaken();
        } finally {
            System.clearProperty(ORDERING_SWITCH);
        }
    }

    private void anonymousRequestBelowTheMinimumIsTaken() {
        String email = QuoteFixture.email();
        given().contentType("application/json").header("Idempotency-Key", "anonymous-" + UUID.randomUUID())
                .body(fixture.submitBody(productId, 2, Map.of("email", email)))
                .when().post(ANONYMOUS + "/requests")
                .then().statusCode(201)
                .body("status", equalTo("RECEIVED"));
        customers.list().stream().filter(customer -> email.equals(customer.email()))
                .forEach(customer -> fixture.adopt(customer.id()));
    }

    private record Login(long customerId, long accountId, String email, String token) {}

    /** A committed customer with an ACTIVE login and one open session. */
    private Login login() {
        String email = QuoteFixture.email();
        long customerId = fixture.customer(customers, "Bloemen Peeters BV");
        var grant = accounts.grant(customerId, email, "An Peeters", Language.NL);
        String token = accounts.activate(grant.rawToken(), PASSWORD).sessionToken();
        return new Login(customerId, grant.accountId(), email, token);
    }

    private long customerCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(c) from " + CustomerEntity.class.getName() + " c", Long.class)
                        .getSingleResult());
    }

    private static io.restassured.specification.RequestSpecification with(String authorization) {
        return authorization == null ? given() : given().header("Authorization", authorization);
    }

    private static Response submit(String token, String key, Map<String, Object> body) {
        var request = given().header("Authorization", "Bearer " + token).contentType("application/json");
        if (key != null) request = request.header("Idempotency-Key", key);
        return request.body(body).when().post(BASE + "/requests");
    }

    private Map<String, Object> previewBody() {
        return fixture.previewBody(productId, QuoteFixture.CARTONS);
    }

    private Map<String, Object> submitBody(Map<String, Object> changes) {
        return fixture.submitBody(productId, QuoteFixture.CARTONS, changes);
    }

    /**
     * Committed rows a real website quote needs, and their removal. Shared with
     * QuoteLoginRequestFlowTest; nothing here survives the test that made it.
     */
    static final class QuoteFixture {
        /** 6 cartons x 12 x 10,00 = 720,00 of goods: above the Belgian minimum order value of 600,00. */
        static final int CARTONS = 6;

        private final List<Long> products = new ArrayList<>();
        private final List<Long> customerIds = new ArrayList<>();

        static String email() {
            return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        }

        /** One published product without a family: 12 per carton, list price 10, landed cost 1. */
        long orderableProduct(EntityManager em) {
            long id = QuarkusTransaction.requiringNew().call(() -> {
                ProductEntity product = new ProductEntity();
                product.sku = "LOGIN-QUOTE-" + UUID.randomUUID();
                product.name = "Login quote test";
                product.active = true;
                product.websiteStatus = PublicationState.PUBLISHED;
                product.piecesPerCarton = 12;
                product.inventoryKnown = true;
                product.stockQuantity = 1000;
                product.cartonLengthCm = new BigDecimal("40");
                product.cartonWidthCm = new BigDecimal("30");
                product.cartonHeightCm = new BigDecimal("20");
                product.cartonWeightKg = new BigDecimal("5");
                product.landedCostEur = BigDecimal.ONE;
                product.fixedSalesPriceEur = BigDecimal.TEN;
                em.persist(product);
                em.flush();
                return product.id;
            });
            products.add(id);
            return id;
        }

        long customer(CustomerService customers, String company) {
            Customer customer = customers.create(new Customer(null, company, "Contact " + company,
                    email(), null, "BE0123456789", "BE", Language.NL, null, null, null, null, null,
                    null, null));
            customerIds.add(customer.id());
            return customer.id();
        }

        /** A customer the code under test created; it is removed with the rest. */
        void adopt(long customerId) {
            customerIds.add(customerId);
        }

        Map<String, Object> previewBody(long productId, int cartons) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("language", "NL");
            body.put("fulfillment", "DELIVERY");
            body.put("destination", Map.of("countryCode", "BE", "postalCode", "2400",
                    "city", "Mol", "address", "Markt 1"));
            body.put("items", List.of(Map.of("productId", productId, "cartons", cartons)));
            return body;
        }

        Map<String, Object> submitBody(long productId, int cartons, Map<String, Object> changes) {
            Map<String, Object> body = previewBody(productId, cartons);
            body.put("vatNumber", "BE0123456789");
            body.put("companyCountryCode", "BE");
            body.put("companyName", "Typed Company BV");
            body.put("contactName", "An Peeters");
            body.put("email", email());
            body.put("privacyAccepted", true);
            body.put("website", "");
            body.put("formToken", "form-token");
            body.putAll(changes);
            return body;
        }

        List<SalesOrder> orders(SalesOrderService salesOrders, long customerId) {
            return QuarkusTransaction.requiringNew().call(() -> salesOrders.list().stream()
                    .filter(order -> order.customerId() != null && order.customerId() == customerId)
                    .toList());
        }

        SalesOrder order(SalesOrderService salesOrders, String number) {
            return QuarkusTransaction.requiringNew().call(() -> salesOrders.list().stream()
                    .filter(order -> number.equals(order.number()))
                    .findFirst().orElseThrow());
        }

        /** Quotes first, then their customers (logins and requests go along), then the products. */
        void remove(EntityManager em, CustomerService customers) {
            QuarkusTransaction.requiringNew().run(() -> {
                for (long customerId : customerIds) {
                    List<SalesOrderEntity> orders = em.createQuery("from " + SalesOrderEntity.class.getName()
                                    + " o where o.customerId = :id", SalesOrderEntity.class)
                            .setParameter("id", customerId).getResultList();
                    for (SalesOrderEntity order : orders) {
                        em.createNativeQuery("delete from quote_event where salesOrderId=:id")
                                .setParameter("id", order.id).executeUpdate();
                        em.createNativeQuery("delete from activity_log where entity_type='SALES_ORDER'"
                                + " and entity_id=:id").setParameter("id", Long.toString(order.id)).executeUpdate();
                        SalesCustomerMessageEntity message = em.find(SalesCustomerMessageEntity.class, order.id);
                        if (message != null) em.remove(message);
                        em.remove(order);
                    }
                }
                em.flush();
            });
            customerIds.forEach(customers::delete);
            customerIds.clear();
            QuarkusTransaction.requiringNew().run(() -> {
                for (long productId : products) {
                    ProductEntity product = em.find(ProductEntity.class, productId);
                    if (product != null) em.remove(product);
                }
            });
            products.clear();
        }
    }
}
