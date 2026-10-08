package be.enrosed.account;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormSubmissionEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.WebOrderService;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The order of a logged-in customer through the real HTTP pipeline: the session guard and
 * the switch before anything else, one answer per idempotency key, the refusals a customer
 * can meet and their exact bodies, and no sentence of the sales core in any of them. The
 * order service, the sales core and the buckets per login are the real ones; only the
 * per-network bucket and the form security check are stubbed.
 */
@QuarkusTest
class CustomerOrderResourceHttpTest {
    private static final String BASE = "/api/v1/public/account/orders";
    private static final String DOCUMENTS = "/api/v1/public/account/documents";
    private static final String STAFF = "/api/sales-orders";
    private static final String PASSWORD = "roses-in-a-dome";
    private static final String STAFF_PASSWORD = "named-auth-test-password";
    private static final String SWITCH = "enrosed.website.ordering.enabled";
    private static final String SESSION_INVALID = "{\"code\":\"SESSION_INVALID\","
            + "\"message\":\"The session is no longer valid\",\"fieldErrors\":{}}";
    private static final String NOT_FOUND = "{\"code\":\"NOT_FOUND\",\"message\":\"Not found\",\"fieldErrors\":{}}";
    private static final String ORDER_NOT_FOUND = "{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"Not found\",\"fieldErrors\":{}}";
    private static final String ORDER_LOCKED = "{\"code\":\"ORDER_LOCKED\","
            + "\"message\":\"The order can no longer be changed\",\"fieldErrors\":{}}";
    private static final String ORDER_CHANGED = "{\"code\":\"ORDER_CHANGED\","
            + "\"message\":\"The order was changed in the meantime\",\"fieldErrors\":{\"baseRevision\":\"STALE\"}}";
    private static final String ORDER_CHANGE_LIMIT = "{\"code\":\"ORDER_CHANGE_LIMIT\","
            + "\"message\":\"The order cannot be changed again\",\"fieldErrors\":{}}";
    private static final String REVIEW_REQUIRED = "{\"code\":\"ORDER_REVIEW_REQUIRED\","
            + "\"message\":\"The order could not be completed automatically\",\"fieldErrors\":{}}";
    private static final String UNAVAILABLE = "{\"code\":\"DOCUMENT_UNAVAILABLE\","
            + "\"message\":\"The document cannot be shown right now\",\"fieldErrors\":{}}";

    @Inject CustomerAccountService accounts;
    @Inject SalesOrderService sales;
    @Inject EntityManager em;
    @Inject ObjectMapper json;
    @Inject MockMailbox mailbox;
    @InjectSpy WebOrderService orders;
    @InjectSpy PublicFormRateLimiter rateLimiter;
    @InjectMock PublicFormSecurityService security;

    private final Shop shop = new Shop();
    private long customerId;
    private long productId;

    @BeforeEach
    void aCustomerAndAProduct() {
        doNothing().when(rateLimiter).checkIp(any(), any());
        customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        productId = shop.product();
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        System.clearProperty(SWITCH);
        shop.remove();
        mailbox.clear();
    }

    @Test
    void everyEndpointAsksForASessionFirstAndIsGoneWhileOrderingIsOff() {
        Login login = login();
        long id = place(login, "order-" + UUID.randomUUID(), body(6, Map.of())).then().statusCode(201)
                .extract().jsonPath().getLong("id");

        for (boolean on : new boolean[]{true, false}) {
            if (!on) System.setProperty(SWITCH, "false");
            for (Response answer : calls(null, id)) {
                assertEquals(401, answer.statusCode());
                assertEquals(SESSION_INVALID, answer.asString());
                assertEquals("no-store", answer.header("Cache-Control"));
            }
        }
        /* Off: what an older backend answers, so the page falls back to the quote request. */
        for (Response answer : calls(login.token(), id)) {
            assertEquals(404, answer.statusCode());
            assertEquals(NOT_FOUND, answer.asString());
            assertEquals("no-store", answer.header("Cache-Control"));
        }
        assertEquals(1, documents().size(), "nothing was placed, changed or cancelled meanwhile");
        assertEquals(QuoteStatus.CONCEPT, documents().getFirst().status());
        assertEquals(1, row(id).revision);
        verify(security).verifySubmission(any(), any(), any());
    }

    @Test
    void anOrderIsPlacedOnceAndItsReceiptIsAllThatIsKept() throws Exception {
        Login login = login();

        given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                .body(previewBody(6, null))
                .when().post(BASE + "/preview")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("pricesVisible", equalTo(true))
                .body("lines[0].unitPriceNet", equalTo(10.0f))
                .body("totals.goodsNet", equalTo(720.0f))
                .body("validation.meetsMinimum", equalTo(true));
        verify(rateLimiter).checkIp(eq(PublicFormAction.QUOTE_PREVIEW), any());

        String key = "order-" + UUID.randomUUID();
        Response placed = place(login, key, body(6, Map.of()));
        placed.then().statusCode(201)
                .header("Cache-Control", "no-store")
                .body("id", notNullValue())
                .body("number", notNullValue())
                .body("revision", equalTo(1))
                .body("status", equalTo("RECEIVED"));
        JsonNode receipt = json.readTree(placed.asString());
        assertEquals(List.of("id", "number", "revision", "status"), keys(receipt));
        long id = receipt.get("id").asLong();
        String number = receipt.get("number").asText();
        /* The page's own quote form token is checked; ACCOUNT_ORDER is a namespace only. */
        verify(security).verifySubmission(eq(PublicFormPurpose.QUOTE), eq("form-token"), any());
        verify(rateLimiter).checkIp(eq(PublicFormAction.ACCOUNT_QUOTE_SUBMIT), any());

        /* A retry is answered from the stored receipt, also with another form token. */
        Response replayed = place(login, key, body(6, Map.of("formToken", "a-newer-form-token")));
        assertEquals(201, replayed.statusCode());
        assertEquals(placed.asString(), replayed.asString());
        assertEquals(1, documents().size(), "one order, not two");
        place(login, key, body(7, Map.of())).then().statusCode(409)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.idempotencyKey", equalTo("CONFLICT"));
        assertEquals(1, documents().size());
        verify(orders).placeByCustomer(any(), any());

        JsonNode stored = shop.inTransaction(() -> PublicFormSubmissionEntity.<PublicFormSubmissionEntity>list(
                        "purpose", PublicFormPurpose.ACCOUNT_ORDER.name()).stream()
                .map(row -> row.responseJson).filter(text -> text.contains("\"" + number + "\""))
                .map(this::tree).findFirst().orElseThrow());
        assertEquals(List.of("id", "number", "revision", "status"), keys(stored), "nothing else is kept for the replay");

        /* The order is the customer's own: the read side shows what was typed and what the ERP priced. */
        given().header("Authorization", "Bearer " + login.token()).when().get(DOCUMENTS + "/" + id)
                .then().statusCode(200)
                .body("kind", equalTo("ORDER"))
                .body("number", equalTo(number))
                .body("status", equalTo("RECEIVED"))
                .body("revision", equalTo(1))
                .body("canChange", equalTo(true))
                .body("canCancel", equalTo(true))
                .body("basis", equalTo("AS_ORDERED"))
                .body("fulfillment", equalTo("DELIVERY"))
                .body("destination.countryCode", equalTo("BE"))
                .body("destination.postalCode", equalTo("3980"))
                .body("destination.address", equalTo("Industrieweg 1"))
                .body("contactName", equalTo("Jan Besteller"))
                .body("phone", equalTo("+32 13 00 00 00"))
                .body("notes", equalTo("Graag voor donderdag"))
                .body("lines[0].cartons", equalTo(6))
                .body("lines[0].piecesPerCarton", equalTo(12))
                .body("lines[0].unitPrice", equalTo(10.0f))
                .body("totals.goods", equalTo(720.0f));
        given().header("Authorization", "Bearer " + login.token()).when().get(DOCUMENTS + "/delivery-defaults")
                .then().statusCode(200)
                .body("source", equalTo("LAST_ORDER"))
                .body("destination.city", equalTo("Tessenderlo"));
        assertEquals(1, mailbox.getMailsSentTo(login.email()).size(), "the login that ordered gets the received mail");
        assertTrue(mailbox.getMailsSentTo(login.email()).getFirst().getSubject().contains(number));
    }

    @Test
    void whatCannotBeOrderedIsRefusedAndLeavesNothing() {
        Login login = login();

        place(login, null, body(2, Map.of())).then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.items", equalTo("MINIMUM_NOT_MET"))
                .body("fieldErrors.size()", equalTo(1));
        given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                .body(previewBody(2, null))
                .when().post(BASE + "/preview")
                .then().statusCode(200)
                .body("validation.meetsMinimum", equalTo(false))
                .body("validation.minimumOrderNet", equalTo(600.0f))
                .body("validation.minimumShortfallNet", equalTo(360.0f))
                .body("validation.messageCodes", hasItem("MINIMUM_NOT_MET"));

        Map<String, Object> noStreet = new LinkedHashMap<>();
        noStreet.put("countryCode", "BE");
        noStreet.put("postalCode", "3980");
        place(login, null, body(6, Map.of("fulfillment", " delivery ", "destination", noStreet, "privacyAccepted", false)))
                .then().statusCode(422)
                .body("fieldErrors.'destination.address'", equalTo("REQUIRED"))
                .body("fieldErrors.'destination.city'", equalTo("REQUIRED"))
                .body("fieldErrors.privacyAccepted", equalTo("REQUIRED"));
        place(login, null, body(6, Map.of("fulfillment", "COURIER"))).then().statusCode(422)
                .body("fieldErrors.fulfillment", equalTo("INVALID"));
        /* Nothing was refused by the form check: validation comes before the challenge is spent. */
        verify(security, never()).verifySubmission(any(), any(), any());

        /* A filled honeypot gets the success a real order gets, without an id and without an order. */
        place(login, null, body(6, Map.of("website", "https://spam.example"))).then().statusCode(201)
                .header("Cache-Control", "no-store")
                .body("id", nullValue())
                .body("number", matchesPattern("^WEB-[0-9A-F]{20}$"))
                .body("revision", nullValue())
                .body("status", equalTo("RECEIVED"));
        verify(orders, never()).placeByCustomer(any(), any());

        given().header("Authorization", "Bearer " + login.token()).contentType("application/json").body("{")
                .when().post(BASE)
                .then().statusCode(400).body("code", equalTo("INVALID_REQUEST"));
        given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                .body("{\"notes\":\"" + "x".repeat(65 * 1024) + "\"}")
                .when().post(BASE)
                .then().statusCode(413).body("code", equalTo("PAYLOAD_TOO_LARGE"));
        given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                .body("{\"language\":\"" + "x".repeat(65 * 1024) + "\"}")
                .when().post(BASE + "/preview")
                .then().statusCode(413).body("code", equalTo("PAYLOAD_TOO_LARGE"));

        assertTrue(documents().isEmpty());
        assertTrue(mailbox.getMailsSentTo(login.email()).isEmpty());
    }

    @Test
    void theCustomerChangesTheOrderUntilStaffTakeIt() {
        Login login = login();
        Login colleague = login();
        long id = place(login, "order-" + UUID.randomUUID(), body(6, Map.of())).then().statusCode(201)
                .extract().jsonPath().getLong("id");
        String number = documents().getFirst().number();

        change(login, id, null, body(7, Map.of())).then().statusCode(422)
                .body("fieldErrors.baseRevision", equalTo("REQUIRED"));
        change(login, id, null, body(7, Map.of("baseRevision", 1, "website", "https://spam.example")))
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("fieldErrors.request", equalTo("INVALID"));
        change(login, id, null, body(2, Map.of("baseRevision", 1))).then().statusCode(422)
                .body("fieldErrors.items", equalTo("MINIMUM_NOT_MET"));
        verify(orders, never()).changeByCustomer(anyLong(), any(), any());

        given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                .body(previewBody(7, id))
                .when().post(BASE + "/preview")
                .then().statusCode(200).body("totals.goodsNet", equalTo(840.0f));

        String key = "change-" + UUID.randomUUID();
        Response changed = change(login, id, key, body(7, Map.of("baseRevision", 1, "notes", "Liever vrijdag")));
        changed.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo((int) id))
                .body("number", equalTo(number))
                .body("revision", equalTo(2))
                .body("status", equalTo("RECEIVED"));
        verify(rateLimiter, atLeastOnce()).checkIp(eq(PublicFormAction.ACCOUNT_ORDER_WRITE), any());

        /* The same request again is the same answer and one step; the same key for another basket is a conflict. */
        Response replayed = change(login, id, key, body(7, Map.of("baseRevision", 1, "notes", "Liever vrijdag")));
        assertEquals(200, replayed.statusCode());
        assertEquals(changed.asString(), replayed.asString());
        assertEquals(2, row(id).revision);
        change(login, id, key, body(8, Map.of("baseRevision", 1, "notes", "Liever vrijdag"))).then().statusCode(409)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.idempotencyKey", equalTo("CONFLICT"));
        assertEquals(84, documents().getFirst().lines().getFirst().quantity());

        /* A colleague's page still shows the first version. */
        Response stale = change(colleague, id, null, body(9, Map.of("baseRevision", 1)));
        assertEquals(409, stale.statusCode());
        assertEquals(ORDER_CHANGED, stale.asString());
        assertEquals("no-store", stale.header("Cache-Control"));
        Response staleCancel = cancel(colleague, id, null, 1);
        assertEquals(409, staleCancel.statusCode());
        assertEquals(ORDER_CHANGED, staleCancel.asString());
        change(colleague, id, null, body(9, Map.of("baseRevision", 2))).then().statusCode(200)
                .body("revision", equalTo(3));
        assertEquals(colleague.email(), row(id).accountEmail);
        assertEquals(1, mailbox.getMailsSentTo(login.email()).size(), "a change sends the customer no mail");
        assertTrue(mailbox.getMailsSentTo(colleague.email()).isEmpty());

        /* A staff member takes the order: from here every customer write hears the same thing. */
        given().auth().preemptive().basic("emre", STAFF_PASSWORD).queryParam("webOrderRevision", 3)
                .when().post(STAFF + "/{id}/take-into-processing", id)
                .then().statusCode(200)
                .body("webOrder.processingTrigger", equalTo("KNOP"))
                .body("webOrder.customerChangeSummary", notNullValue())
                .body("order.lines[0].quantity", equalTo(108));
        for (Response refused : List.of(
                change(login, id, null, body(10, Map.of("baseRevision", 3))),
                cancel(login, id, null, 3),
                given().header("Authorization", "Bearer " + login.token()).contentType("application/json")
                        .body(previewBody(10, id)).when().post(BASE + "/preview"))) {
            assertEquals(409, refused.statusCode());
            assertEquals(ORDER_LOCKED, refused.asString());
            assertEquals("no-store", refused.header("Cache-Control"));
        }
        assertEquals(108, documents().getFirst().lines().getFirst().quantity());
        given().header("Authorization", "Bearer " + login.token()).when().get(DOCUMENTS + "?kind=ORDERS")
                .then().statusCode(200)
                .body("items[0].status", equalTo("IN_PROCESSING"))
                .body("items[0].canChange", equalTo(false));
        assertEquals(1, mailbox.getMailsSentTo(colleague.email()).size(),
                "the in-processing mail goes to the login that last changed the order");
    }

    @Test
    void anotherCustomersOrderAndADocumentThatIsNoOrderAreNotFound() {
        Login login = login();
        long id = place(login, null, body(6, Map.of())).then().statusCode(201).extract().jsonPath().getLong("id");
        long otherCustomer = shop.customer("Fleurs Dupont SRL", "Rue des Fleurs 1", "1000", "Bruxelles");
        Login stranger = login(otherCustomer);
        long legacy = shop.legacyRequests(1).getFirst();

        List<Response> answers = new ArrayList<>();
        answers.add(change(stranger, id, null, body(7, Map.of("baseRevision", 1))));
        answers.add(cancel(stranger, id, null, 1));
        answers.add(given().header("Authorization", "Bearer " + stranger.token()).contentType("application/json")
                .body(previewBody(7, id)).when().post(BASE + "/preview"));
        answers.add(change(login, legacy, null, body(7, Map.of("baseRevision", 1))));
        answers.add(cancel(login, legacy, null, 1));
        answers.add(change(login, 987_654_321L, null, body(7, Map.of("baseRevision", 1))));
        answers.add(cancel(login, 987_654_321L, null, 1));
        for (Response answer : answers) {
            assertEquals(404, answer.statusCode());
            assertEquals(ORDER_NOT_FOUND, answer.asString());
            assertEquals("no-store", answer.header("Cache-Control"));
        }
        assertEquals(1, row(id).revision);
        assertEquals(72, documents().getFirst().lines().getFirst().quantity());
    }

    @Test
    void cancellingIsAnsweredOnceAndMailsTheCustomerNothing() {
        Login login = login();
        long id = place(login, null, body(6, Map.of())).then().statusCode(201).extract().jsonPath().getLong("id");
        String number = documents().getFirst().number();
        assertEquals(1, mailbox.getMailsSentTo(login.email()).size());

        given().header("Authorization", "Bearer " + login.token()).contentType("application/json").body("{}")
                .when().post(BASE + "/" + id + "/cancellation")
                .then().statusCode(422).body("fieldErrors.baseRevision", equalTo("REQUIRED"));

        String key = "cancel-" + UUID.randomUUID();
        Response cancelled = cancel(login, id, key, 1);
        cancelled.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo((int) id))
                .body("number", equalTo(number))
                .body("revision", equalTo(2))
                .body("status", equalTo("CANCELLED"));
        Response replayed = cancel(login, id, key, 1);
        assertEquals(200, replayed.statusCode());
        assertEquals(cancelled.asString(), replayed.asString());
        verify(orders).cancelByCustomer(anyLong(), anyInt(), any());

        Response again = cancel(login, id, null, 2);
        assertEquals(409, again.statusCode());
        assertEquals(ORDER_LOCKED, again.asString());
        Response tooLate = change(login, id, null, body(7, Map.of("baseRevision", 2)));
        assertEquals(409, tooLate.statusCode());
        assertEquals(ORDER_LOCKED, tooLate.asString());

        SalesOrder document = documents().getFirst();
        assertEquals(QuoteStatus.GEANNULEERD, document.status());
        assertNull(document.portalToken());
        assertEquals(2, row(id).revision);
        assertEquals(1, mailbox.getMailsSentTo(login.email()).size(), "the customer is mailed nothing about their own cancel");
        given().auth().preemptive().basic("emre", STAFF_PASSWORD).when().get(STAFF + "/{id}", id)
                .then().statusCode(200)
                .body("webOrder.customerCancelledAt", notNullValue())
                .body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.customerEditable", equalTo(false));
        given().header("Authorization", "Bearer " + login.token()).when().get(DOCUMENTS + "?kind=ORDERS")
                .then().statusCode(200)
                .body("items[0].status", equalTo("CANCELLED"))
                .body("items[0].cancelledBy", equalTo("CUSTOMER"));
    }

    @Test
    void theTwentiethVersionIsTheLast() {
        Login login = login();
        long id = place(login, null, body(6, Map.of())).then().statusCode(201).extract().jsonPath().getLong("id");
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, id).revision = 20);

        Response refused = change(login, id, null, body(7, Map.of("baseRevision", 20)));

        assertEquals(409, refused.statusCode());
        assertEquals(ORDER_CHANGE_LIMIT, refused.asString());
        assertEquals(72, documents().getFirst().lines().getFirst().quantity());
        verify(orders, never()).changeByCustomer(anyLong(), any(), any());
    }

    @Test
    void nothingTheSalesCoreSaysReachesTheCustomer() {
        Login login = login();
        long id = place(login, null, body(6, Map.of())).then().statusCode(201).extract().jsonPath().getLong("id");

        List<RuntimeException> causes = List.of(
                new BusinessRuleException("Product ROSE-1 heeft geen kostprijs"),
                new NotFoundException("Verkooporder", id),
                new IllegalStateException("De bestelling kon niet worden vastgelegd"));
        for (RuntimeException cause : causes) {
            reset(orders);
            doThrow(cause).when(orders).placeByCustomer(any(), any());
            doThrow(cause).when(orders).changeByCustomer(anyLong(), any(), any());
            doThrow(cause).when(orders).cancelByCustomer(anyLong(), anyInt(), any());
            for (Response answer : List.of(
                    place(login, null, body(6, Map.of("notes", "Nog een bestelling " + UUID.randomUUID()))),
                    change(login, id, null, body(7, Map.of("baseRevision", 1))),
                    cancel(login, id, null, 1))) {
                assertEquals(409, answer.statusCode(), cause.toString());
                assertEquals(REVIEW_REQUIRED, answer.asString(), cause.toString());
                assertEquals("no-store", answer.header("Cache-Control"));
            }
            reset(orders);
            doThrow(cause).when(orders).previewByCustomer(any(), any());
            Response preview = given().header("Authorization", "Bearer " + login.token())
                    .contentType("application/json").body(previewBody(6, null)).when().post(BASE + "/preview");
            assertEquals(409, preview.statusCode(), cause.toString());
            assertEquals(UNAVAILABLE, preview.asString(), cause.toString());
        }
        reset(orders);

        /* The failed writes rolled back with their idempotency rows: the order is as it was placed. */
        assertEquals(1, documents().size());
        assertEquals(1, row(id).revision);
        assertEquals(QuoteStatus.CONCEPT, documents().getFirst().status());
    }

    @Test
    void changesAndCancellationsHaveTwentyAnHourPerLogin() {
        Login busy = login();
        Login colleague = login();
        long id = place(busy, null, body(6, Map.of())).then().statusCode(201).extract().jsonPath().getLong("id");
        for (int write = 0; write < 20; write++) {
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_ORDER_WRITE, "ACCOUNT", String.valueOf(busy.accountId()), 20);
        }

        for (Response refused : List.of(change(busy, id, null, body(7, Map.of("baseRevision", 1))),
                cancel(busy, id, null, 1))) {
            assertEquals(429, refused.statusCode());
            assertEquals("RATE_LIMITED", refused.path("code"));
            assertTrue(Integer.parseInt(refused.header("Retry-After")) > 0);
            assertEquals("no-store", refused.header("Cache-Control"));
        }
        assertEquals(1, row(id).revision);

        /* The budget is the login's: a colleague changes the same order, and placing has its own. */
        change(colleague, id, null, body(7, Map.of("baseRevision", 1))).then().statusCode(200);
        place(busy, null, body(6, Map.of("notes", "Een tweede bestelling"))).then().statusCode(201);
        assertFalse(documents().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record Login(long accountId, String email, String token) {}

    /** An ACTIVE login of the test's customer with one open session. */
    private Login login() {
        return login(customerId);
    }

    private Login login(long ofCustomer) {
        String email = "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        var grant = accounts.grant(ofCustomer, email, "An Peeters", Language.NL);
        return new Login(grant.accountId(), email, accounts.activate(grant.rawToken(), PASSWORD).sessionToken());
    }

    /** The four calls of this resource, with or without a bearer. */
    private List<Response> calls(String token, long id) {
        return List.of(
                with(token).contentType("application/json").body(previewBody(6, null)).when().post(BASE + "/preview"),
                with(token).contentType("application/json").header("Idempotency-Key", "order-" + UUID.randomUUID())
                        .body(body(6, Map.of("notes", "Nog een bestelling"))).when().post(BASE),
                with(token).contentType("application/json").body(body(7, Map.of("baseRevision", 1)))
                        .when().post(BASE + "/" + id + "/changes"),
                with(token).contentType("application/json").body(Map.of("baseRevision", 1))
                        .when().post(BASE + "/" + id + "/cancellation"));
    }

    private static io.restassured.specification.RequestSpecification with(String token) {
        return token == null ? given() : given().header("Authorization", "Bearer " + token);
    }

    private static Response place(Login login, String key, Map<String, Object> body) {
        var request = given().header("Authorization", "Bearer " + login.token()).contentType("application/json");
        if (key != null) request = request.header("Idempotency-Key", key);
        return request.body(body).when().post(BASE);
    }

    private static Response change(Login login, long id, String key, Map<String, Object> body) {
        var request = given().header("Authorization", "Bearer " + login.token()).contentType("application/json");
        if (key != null) request = request.header("Idempotency-Key", key);
        return request.body(body).when().post(BASE + "/" + id + "/changes");
    }

    private static Response cancel(Login login, long id, String key, int baseRevision) {
        var request = given().header("Authorization", "Bearer " + login.token()).contentType("application/json");
        if (key != null) request = request.header("Idempotency-Key", key);
        return request.body(Map.of("baseRevision", baseRevision)).when().post(BASE + "/" + id + "/cancellation");
    }

    private Map<String, Object> previewBody(int cartons, Long orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", "NL");
        body.put("fulfillment", "DELIVERY");
        body.put("destination", Map.of("countryCode", "BE", "postalCode", "3980",
                "city", "Tessenderlo", "address", "Industrieweg 1"));
        body.put("items", List.of(Map.of("productId", productId, "cartons", cartons)));
        if (orderId != null) body.put("orderId", orderId);
        return body;
    }

    /** What the page posts: a delivery in Tessenderlo, 12 pieces a carton at 10,00, so 6 cartons clear the minimum. */
    private Map<String, Object> body(int cartons, Map<String, Object> changes) {
        Map<String, Object> body = previewBody(cartons, null);
        body.put("contactName", "Jan Besteller");
        body.put("phone", "+32 13 00 00 00");
        body.put("notes", "Graag voor donderdag");
        body.put("privacyAccepted", true);
        body.put("website", "");
        body.put("formToken", "form-token");
        body.putAll(changes);
        return body;
    }

    private List<SalesOrder> documents() {
        return shop.inTransaction(() -> sales.list().stream()
                .filter(order -> order.customerId() != null && order.customerId() == customerId).toList());
    }

    private SalesWebOrderEntity row(long id) {
        return shop.inTransaction(() -> {
            SalesWebOrderEntity row = em.find(SalesWebOrderEntity.class, id);
            em.detach(row);
            return row;
        });
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
