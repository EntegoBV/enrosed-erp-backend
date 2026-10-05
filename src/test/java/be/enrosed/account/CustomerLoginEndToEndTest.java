package be.enrosed.account;

import be.enrosed.account.CustomerQuoteResourceHttpTest.QuoteFixture;
import be.enrosed.publicform.PublicFormRateBucketEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.WebsiteQuoteSettingsService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer login from the visitor's first form to a withdrawn and re-given login, through
 * real HTTP only. Nothing is mocked or spied: the form tokens are minted by the public
 * configuration endpoint and are old enough when they are used, the buckets count, staff sign
 * in with HTTP Basic, the background tasks run on their own pool and the mails land in the
 * mock mailbox. The injected services are used to prepare a product, to read rows no endpoint
 * of this feature shows, and to clean up.
 */
@QuarkusTest
class CustomerLoginEndToEndTest {
    private static final String STAFF = "/api/login-requests";
    private static final String LOGINS = "/api/customer-logins";
    private static final String ACCOUNT = "/api/v1/public/account";
    private static final String ACCOUNT_QUOTES = ACCOUNT + "/quotes";
    private static final String ANONYMOUS_QUOTES = "/api/v1/public/quotes";
    private static final String STAFF_PASSWORD = "named-auth-test-password";
    private static final String FIRST_PASSWORD = "roses-in-a-dome";
    private static final String SECOND_PASSWORD = "peonies-in-a-vase";
    /** enrosed.mail.sales-copy: where every styled team notice goes. */
    private static final String TEAM = "hello@enrosed.com";
    private static final List<String> OFFICE = List.of(TEAM, "admin@enrosed.com", "verkoop@enrosed.be");

    @Inject CustomerService customers;
    @Inject SalesOrderService salesOrders;
    @Inject WebsiteQuoteSettingsService quoteSettings;
    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @ConfigProperty(name = "enrosed.website.base-url") String websiteBaseUrl;

    private final QuoteFixture fixture = new QuoteFixture();
    private long productId;
    private String accountFormToken;
    private String quoteFormToken;

    @BeforeEach
    void hidePricesAndOpenTheForms() throws InterruptedException {
        settle();
        /* Buckets other test classes filled (per network, the global notice cap) are not this walk's. */
        QuarkusTransaction.requiringNew().run(() -> PublicFormRateBucketEntity.deleteAll());
        clearRequests();
        productId = fixture.orderableProduct(em);

        staff().contentType("application/json").body("{\"pricesVisible\":false}")
                .when().put("/api/website/quote-settings")
                .then().statusCode(200).body("pricesVisible", equalTo(false));

        /* A visitor opens the account page and the quote page; both forms must be three seconds old. */
        Response account = given().queryParam("purpose", "ACCOUNT").when().get("/api/v1/public/forms/configuration");
        Response quote = given().queryParam("purpose", "QUOTE").when().get("/api/v1/public/forms/configuration");
        account.then().statusCode(200).body("challengeSiteKey", nullValue());
        quote.then().statusCode(200);
        accountFormToken = account.path("formToken");
        quoteFormToken = quote.path("formToken");
        Instant ready = Instant.parse(quote.<String>path("minimumSubmitAt")).plusMillis(1_200);
        long wait = Duration.between(Instant.now(), ready).toMillis();
        if (wait > 0) Thread.sleep(wait);
        mailbox.clear();
    }

    @AfterEach
    void cleanUp() {
        settle();
        quoteSettings.update(true);
        clearRequests();
        fixture.remove(em, customers);
        QuarkusTransaction.requiringNew().run(() -> PublicFormRateBucketEntity.deleteAll());
        mailbox.clear();
    }

    @Test
    void aVisitorBecomesACustomerWithPricesAndStaffCanTakeTheLoginBackAndGiveItAgain() {
        String email = QuoteFixture.email();
        String vat = "BE0" + (100_000_000 + (long) (Math.random() * 899_999_999));
        /* A customer staff already know under the same VAT number: the detail must show it as a hint. */
        long known = QuarkusTransaction.requiringNew().call(() -> customers.create(new Customer(null,
                "Fleurs Dupont (bekend)", "Luc Dupont", QuoteFixture.email(), null, vat, "BE", Language.FR,
                null, null, null, null, null, null, null)).id());
        fixture.adopt(known);

        // (a) the anonymous quote configuration and preview show no prices
        anonymousSeesNoPrices();

        // (b) the visitor asks for a login with the standalone form
        String reference = given().contentType("application/json")
                .header("Idempotency-Key", "login-" + UUID.randomUUID())
                .body(loginRequest(email, "Fleurs Dupont SRL", vat, "FR"))
                .when().post(ACCOUNT + "/requests")
                .then().statusCode(202)
                .header("Cache-Control", "no-store")
                .body("status", equalTo("RECEIVED"))
                .body("reference", notNullValue())
                .extract().path("reference");
        settle();
        List<Mail> teamAfterRequest = mailbox.getMailsSentTo(TEAM);
        assertEquals(1, teamAfterRequest.size(), "one team mail about the new login request");
        assertTrue(teamAfterRequest.getFirst().getSubject().startsWith("Nieuwe login-aanvraag " + reference),
                teamAfterRequest.getFirst().getSubject());
        assertEquals(1, mailbox.getTotalMessagesSent(), "the applicant gets no mail for asking");

        // (c) staff see it waiting, see the customer it may belong to, and approve it for a new customer
        staff().when().get(STAFF + "/summary").then().statusCode(200).body("pending", equalTo(1));
        int requestId = staff().when().get(STAFF)
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].reference", equalTo(reference))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].source", equalTo("ORDER_SCREEN"))
                .body("[0].email", equalTo(email))
                .body("[0].language", equalTo("FR"))
                .body("[0].companyName", equalTo("Fleurs Dupont SRL"))
                .body("[0].customerId", nullValue())
                .body("[0].hasExistingLogin", equalTo(false))
                .extract().path("[0].id");
        staff().when().get(STAFF + "/" + requestId)
                .then().statusCode(200)
                .body("request.reference", equalTo(reference))
                .body("existingAccount", nullValue())
                .body("matches", hasSize(1))
                .body("matches[0].customerId", equalTo((int) known))
                .body("matches[0].company", equalTo("Fleurs Dupont (bekend)"))
                .body("matches[0].matchedOn", equalTo(List.of("VAT")))
                .body("matches[0].logins", hasSize(0));

        long customersBeforeApproval = customerCount();
        Response approved = staff().contentType("application/json")
                .body(Map.of("newCustomer", Map.of("company", "Fleurs Dupont SRL", "vatNumber", vat,
                        "countryCode", "BE", "contact", "Claire Dupont", "phone", "+32 470 00 00 00",
                        "language", "FR")))
                .when().post(STAFF + "/" + requestId + "/approve");
        approved.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("request.status", equalTo("APPROVED"))
                .body("request.decidedBy", notNullValue())
                .body("request.customerCompany", equalTo("Fleurs Dupont SRL"))
                .body("account.status", equalTo("INVITED"))
                .body("account.email", equalTo(email))
                .body("account.language", equalTo("FR"))
                .body("invitation.sent", equalTo(true))
                .body("invitation.error", nullValue());
        assertFalse(approved.asString().contains("eci1_"), "the link never travels to the ERP");
        long customerId = ((Number) approved.path("request.customerId")).longValue();
        long accountId = ((Number) approved.path("account.id")).longValue();
        fixture.adopt(customerId);
        assertNotEquals(known, customerId);
        assertEquals(customersBeforeApproval + 1, customerCount(), "approval made exactly one customer");
        assertEquals(customerId, ((Number) approved.path("account.customerId")).longValue());
        Instant linkEnds = Instant.parse(approved.<String>path("invitation.expiresAt"));
        long linkHours = Duration.between(Instant.now(), linkEnds).toHours();
        assertTrue(linkHours >= 167 && linkHours <= 168, "the link lasts seven days: " + linkEnds);
        staff().when().get(STAFF).then().statusCode(200).body("$", hasSize(0));
        staff().queryParam("status", "APPROVED").when().get(STAFF)
                .then().statusCode(200).body("id", equalTo(List.of(requestId)));

        // (d) exactly one invitation mail, in French, to the applicant alone, token in the fragment
        settle();
        String firstLink = onlyInvitation(email, Language.FR, 1);
        assertEquals(2, mailbox.getTotalMessagesSent(), "the team notice of (b) and the invitation, nothing else");
        String firstToken = firstLink.substring(firstLink.indexOf("#activate=") + "#activate=".length());

        // (e) the link is inspected, a password chosen, a session received
        given().contentType("application/json").body(Map.of("token", firstToken))
                .when().post(ACCOUNT + "/activation/inspect")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("email", equalTo(email))
                .body("company", equalTo("Fleurs Dupont SRL"))
                .body("expiresAt", notNullValue());
        Response activated = given().contentType("application/json")
                .body(Map.of("token", firstToken, "password", FIRST_PASSWORD))
                .when().post(ACCOUNT + "/activation");
        activated.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("sessionToken", notNullValue())
                .body("profile.email", equalTo(email))
                .body("profile.company", equalTo("Fleurs Dupont SRL"))
                .body("profile.companyCountryCode", equalTo("BE"))
                .body("profile.vatNumber", equalTo(vat))
                .body("profile.contactName", equalTo("Claire Dupont"))
                .body("profile.language", equalTo("FR"));
        String session = activated.path("sessionToken");
        assertTrue(AccountTokens.isSessionToken(session));
        bearer(session).when().get(ACCOUNT + "/session")
                .then().statusCode(200)
                .body("profile.email", equalTo(email))
                .body("profile.company", equalTo("Fleurs Dupont SRL"));
        staff().queryParam("customerId", customerId).when().get(LOGINS)
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].status", equalTo("ACTIVE"))
                .body("[0].activeSessions", equalTo(1));

        // (f) the session shows real prices; a visitor still sees none
        bearer(session).queryParam("language", "FR").when().get(ACCOUNT_QUOTES + "/configuration")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("pricesVisible", equalTo(true))
                .body(product() + ".unitPriceNet", equalTo(10.0f))
                .body(product() + ".priceAvailable", equalTo(true));
        bearer(session).contentType("application/json").body(fixture.previewBody(productId, 2))
                .when().post(ACCOUNT_QUOTES + "/preview")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(true))
                .body("lines[0].unitPriceNet", equalTo(10.0f))
                .body("totals.goodsNet", equalTo(240.0f));
        anonymousSeesNoPrices();

        // (g) a logged-in quote request hangs on the approved customer; no customer is created
        long customersBeforeQuote = customerCount();
        String quoteReference = bearer(session).contentType("application/json")
                .header("Idempotency-Key", "account-quote-" + UUID.randomUUID())
                .body(fixture.submitBody(productId, 2, Map.of("formToken", quoteFormToken)))
                .when().post(ACCOUNT_QUOTES + "/requests")
                .then().statusCode(201)
                .body("status", equalTo("RECEIVED"))
                .body("estimate.pricesVisible", equalTo(true))
                .body("estimate.lines[0].unitPriceNet", equalTo(10.0f))
                .body("estimate.totals.goodsNet", equalTo(240.0f))
                .extract().path("reference");
        SalesOrder order = fixture.order(salesOrders, quoteReference);
        assertEquals(customerId, order.customerId(), "the quote hangs on the approved customer");
        assertEquals(customersBeforeQuote, customerCount(), "a logged-in quote creates no customer");
        assertEquals(24, order.lines().getFirst().quantity());
        assertEquals(0, new BigDecimal("10").compareTo(order.lines().getFirst().unitPriceEur()));
        assertTrue(order.internalNotes().contains("Aangevraagd via klantlogin " + email), order.internalNotes());
        Customer afterQuote = customers.get(customerId);
        assertEquals("Fleurs Dupont SRL", afterQuote.company(), "the typed company name changed nothing");
        assertEquals(email, afterQuote.email());
        settle();
        assertEquals(0, loginRequests(email, "PENDING"), "a logged-in quote asks for no login");

        // (h) staff withdraw the login: the session dies at once and the password is refused
        staff().when().post(LOGINS + "/" + accountId + "/withdraw")
                .then().statusCode(200)
                .body("status", equalTo("DISABLED"))
                .body("disabledBy", notNullValue())
                .body("activeSessions", equalTo(0));
        bearer(session).when().get(ACCOUNT + "/session")
                .then().statusCode(401).body("code", equalTo("SESSION_INVALID"));
        bearer(session).queryParam("language", "FR").when().get(ACCOUNT_QUOTES + "/configuration")
                .then().statusCode(401).body("code", equalTo("SESSION_INVALID"));
        bearer(session).contentType("application/json").body(fixture.previewBody(productId, 2))
                .when().post(ACCOUNT_QUOTES + "/preview")
                .then().statusCode(401);
        login(email, FIRST_PASSWORD).then().statusCode(401).body("code", equalTo("INVALID_CREDENTIALS"));

        // (i) staff send a new link: the login is given again, the old password stays dead
        mailbox.clear();
        Response reissued = staff().when().post(LOGINS + "/" + accountId + "/invitation");
        reissued.then().statusCode(200)
                .body("account.id", equalTo((int) accountId))
                .body("account.status", equalTo("INVITED"))
                .body("account.disabledAt", nullValue())
                .body("invitation.sent", equalTo(true));
        assertFalse(reissued.asString().contains("eci1_"));
        String secondLink = onlyInvitation(email, Language.FR, 1);
        assertEquals(1, mailbox.getTotalMessagesSent());
        String secondToken = secondLink.substring(secondLink.indexOf("#activate=") + "#activate=".length());
        assertNotEquals(firstToken, secondToken);

        login(email, FIRST_PASSWORD).then().statusCode(401).body("code", equalTo("INVALID_CREDENTIALS"));
        inspect(firstToken).then().statusCode(422).body("fieldErrors.token", equalTo("INVALID"));
        inspect(secondToken).then().statusCode(200).body("email", equalTo(email));
        String secondSession = given().contentType("application/json")
                .body(Map.of("token", secondToken, "password", SECOND_PASSWORD))
                .when().post(ACCOUNT + "/activation")
                .then().statusCode(200)
                .body("profile.email", equalTo(email))
                .extract().path("sessionToken");
        assertNotEquals(session, secondSession);
        /* The link worked once. */
        given().contentType("application/json")
                .body(Map.of("token", secondToken, "password", "another-password-entirely"))
                .when().post(ACCOUNT + "/activation")
                .then().statusCode(422).body("fieldErrors.token", equalTo("INVALID"));
        inspect(secondToken).then().statusCode(422).body("fieldErrors.token", equalTo("INVALID"));

        bearer(session).when().get(ACCOUNT + "/session").then().statusCode(401);
        bearer(secondSession).queryParam("language", "FR").when().get(ACCOUNT_QUOTES + "/configuration")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(true))
                .body(product() + ".unitPriceNet", equalTo(10.0f));
        login(email, FIRST_PASSWORD).then().statusCode(401);
        login(email, "another-password-entirely").then().statusCode(401);
        String thirdSession = login(email, SECOND_PASSWORD)
                .then().statusCode(200)
                .body("profile.company", equalTo("Fleurs Dupont SRL"))
                .extract().path("sessionToken");
        bearer(thirdSession).when().get(ACCOUNT + "/session").then().statusCode(200);
        bearer(thirdSession).when().post(ACCOUNT + "/session/logout").then().statusCode(204);
        bearer(thirdSession).when().get(ACCOUNT + "/session").then().statusCode(401);
        bearer(secondSession).when().get(ACCOUNT + "/session").then().statusCode(200);
        anonymousSeesNoPrices();
    }

    // (j)
    @Test
    void theTickBoxOfAnAnonymousQuoteLeavesALoginRequestOnThatQuoteAndOneTeamMail() {
        String email = QuoteFixture.email();
        long customersBefore = customerCount();

        Response submitted = given().contentType("application/json")
                .header("Idempotency-Key", "quote-" + UUID.randomUUID())
                .body(fixture.submitBody(productId, 2, Map.of("email", email, "language", "NL",
                        "formToken", quoteFormToken, "loginRequested", true)))
                .when().post(ANONYMOUS_QUOTES + "/requests");
        submitted.then().statusCode(201)
                .body("status", equalTo("RECEIVED"))
                .body("estimate.pricesVisible", equalTo(false))
                .body("estimate.lines[0].unitPriceNet", nullValue())
                .body("estimate.totals.goodsNet", nullValue());
        String reference = submitted.path("reference");
        settle();

        assertEquals(customersBefore + 1, customerCount(), "the anonymous quote made its own customer");
        Customer created = customers.list().stream()
                .filter(customer -> email.equals(customer.email())).findFirst().orElseThrow();
        fixture.adopt(created.id());
        SalesOrder order = fixture.order(salesOrders, reference);
        assertEquals(created.id(), order.customerId());

        String row = "find { it.email == '" + email + "' }";
        int requestId = staff().when().get(STAFF)
                .then().statusCode(200)
                .body("findAll { it.email == '" + email + "' }", hasSize(1))
                .body(row + ".status", equalTo("PENDING"))
                .body(row + ".source", equalTo("QUOTE"))
                .body(row + ".salesOrderNumber", equalTo(reference))
                .body(row + ".salesOrderId", equalTo(order.id().intValue()))
                .body(row + ".customerId", equalTo(created.id().intValue()))
                .body(row + ".language", equalTo("NL"))
                .body(row + ".companyName", equalTo("Typed Company BV"))
                .extract().path(row + ".id");
        staff().when().get(STAFF + "/" + requestId)
                .then().statusCode(200)
                .body("request.salesOrderNumber", equalTo(reference))
                .body("matches.find { it.customerId == " + created.id() + " }.matchedOn",
                        equalTo(List.of("QUOTE", "EMAIL", "VAT")));

        List<Mail> team = mailbox.getMailsSentTo(TEAM);
        assertEquals(1, team.size(), "one visitor action, one team mail: "
                + team.stream().map(Mail::getSubject).toList());
        assertTrue(team.getFirst().getSubject().startsWith("Nieuwe websiteaanvraag " + reference),
                team.getFirst().getSubject());
        assertTrue(team.getFirst().getHtml().contains("Login gevraagd"), "the quote's own mail says a login was asked");
        assertEquals(1, mailbox.getTotalMessagesSent(), "nothing for the login request, nothing to the visitor");
        assertTrue(mailbox.getMailsSentTo(email).isEmpty());
    }

    // (k)
    @Test
    void aRejectionIsSilentAndTheApplicantCanAskAgain() {
        String email = QuoteFixture.email();
        Map<String, Object> form = loginRequest(email, "Bloemen Janssens BV", "BE0123456789", "NL");

        given().contentType("application/json").header("Idempotency-Key", "login-" + UUID.randomUUID())
                .body(form).when().post(ACCOUNT + "/requests")
                .then().statusCode(202).body("status", equalTo("RECEIVED"));
        settle();
        assertEquals(1, mailbox.getMailsSentTo(TEAM).size());
        int first = staff().when().get(STAFF)
                .then().statusCode(200).body("$", hasSize(1)).body("[0].email", equalTo(email))
                .extract().path("[0].id");

        long customersBefore = customerCount();
        staff().contentType("application/json").body(Map.of("note", "Geen handelaar"))
                .when().post(STAFF + "/" + first + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("decisionNote", equalTo("Geen handelaar"))
                .body("decidedBy", notNullValue())
                .body("accountId", nullValue());
        settle();
        assertTrue(mailbox.getMailsSentTo(email).isEmpty(), "a rejection sends the applicant nothing");
        assertEquals(1, mailbox.getTotalMessagesSent(), "a rejection sends no mail at all");
        assertEquals(customersBefore, customerCount());
        staff().queryParam("customerId", 0).when().get(LOGINS).then().statusCode(200).body("$", hasSize(0));
        login(email, FIRST_PASSWORD).then().statusCode(401);
        staff().when().get(STAFF).then().statusCode(200).body("$", hasSize(0));
        staff().contentType("application/json").body(Map.of("note", "nog eens"))
                .when().post(STAFF + "/" + first + "/reject")
                .then().statusCode(409);

        /* The applicant asks again and is taken in like anybody else. */
        given().contentType("application/json").header("Idempotency-Key", "login-" + UUID.randomUUID())
                .body(form).when().post(ACCOUNT + "/requests")
                .then().statusCode(202).body("status", equalTo("RECEIVED"));
        settle();
        int second = staff().when().get(STAFF)
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].email", equalTo(email))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].previouslyRejected", equalTo(true))
                .body("id", not(hasItem(first)))
                .extract().path("[0].id");
        assertNotEquals(first, second);
        staff().queryParam("status", "REJECTED").when().get(STAFF)
                .then().statusCode(200).body("id", equalTo(List.of(first)));
        /* Contract 3: a request within 30 days of a rejection arrives silently. */
        assertEquals(1, mailbox.getTotalMessagesSent(), "asking again after a rejection rings nobody");
    }

    // ------------------------------------------------------------------ helpers

    private void anonymousSeesNoPrices() {
        given().queryParam("language", "FR").when().get(ANONYMOUS_QUOTES + "/configuration")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(false))
                .body(product() + ".productId", equalTo((int) productId))
                .body(product() + ".unitPriceNet", nullValue())
                .body(product() + ".priceAvailable", equalTo(false))
                .body("countries.find { it.code == 'BE' }.minimumOrderNet", nullValue());
        given().contentType("application/json").body(fixture.previewBody(productId, 2))
                .when().post(ANONYMOUS_QUOTES + "/preview")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(false))
                .body("lines[0].unitPriceNet", nullValue())
                .body("lines[0].lineTotalNet", nullValue())
                .body("totals.goodsNet", nullValue())
                .body("totals.totalNet", nullValue());
        /* Without a session the account endpoints give nothing at all. */
        given().queryParam("language", "FR").when().get(ACCOUNT_QUOTES + "/configuration")
                .then().statusCode(401).body("code", equalTo("SESSION_INVALID"));
    }

    private String product() {
        return "products.find { it.productId == " + productId + " }";
    }

    /**
     * The single invitation in the applicant's mailbox: subject in the given language, no copy
     * to anybody, and one link to the website's account page with the token in the fragment.
     * Returns that link.
     */
    private String onlyInvitation(String email, Language language, int expected) {
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertEquals(expected, mails.size(), "invitation mails to the applicant");
        Mail mail = mails.getLast();
        assertEquals(DocumentText.of(language).get("mailAccountInviteSubject"), mail.getSubject());
        assertEquals(List.of(email), mail.getTo());
        assertTrue(mail.getCc().isEmpty() && mail.getBcc().isEmpty(), "no office copy of a login link");
        String html = mail.getHtml();
        assertTrue(html.contains("<html lang=\"" + language.code() + "\">"), "the mail is in the request's language");
        String page = websiteBaseUrl.replaceAll("/+$", "")
                + (language == Language.EN ? "" : "/" + language.code()) + "/account/";
        Matcher link = Pattern.compile(Pattern.quote(page) + "#activate=(eci1_[A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
                .matcher(html);
        assertTrue(link.find(), "a link to " + page + " with the token in the fragment");
        String found = link.group();
        assertTrue(AccountTokens.isInvitationToken(link.group(1)));
        assertFalse(found.contains("?"), "the token is not a query parameter");
        /* Every occurrence of a token in the mail is that one link. */
        assertEquals(count(html, "eci1_"), count(html, found));
        for (String office : OFFICE) {
            assertTrue(mailbox.getMailsSentTo(office).stream()
                    .noneMatch(other -> other.getHtml() != null && other.getHtml().contains("eci1_")),
                    "no link in a mail to " + office);
        }
        return found;
    }

    private Response login(String email, String password) {
        return given().contentType("application/json")
                .body(Map.of("email", email, "password", password, "formToken", accountFormToken))
                .when().post(ACCOUNT + "/session");
    }

    private static Response inspect(String token) {
        return given().contentType("application/json").body(Map.of("token", token))
                .when().post(ACCOUNT + "/activation/inspect");
    }

    private Map<String, Object> loginRequest(String email, String company, String vat, String language) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", language);
        body.put("companyName", company);
        body.put("companyCountryCode", "BE");
        body.put("vatNumber", vat);
        body.put("contactName", "Claire Dupont");
        body.put("email", email);
        body.put("phone", "+32 470 00 00 00");
        body.put("message", "Nous sommes fleuristes.");
        body.put("privacyAccepted", true);
        body.put("website", "");
        body.put("formToken", accountFormToken);
        return body;
    }

    private static RequestSpecification staff() {
        return given().auth().preemptive().basic("emre", STAFF_PASSWORD);
    }

    private static RequestSpecification bearer(String sessionToken) {
        return given().header("Authorization", "Bearer " + sessionToken);
    }

    /** The background pool of the notifier has nothing left to do. */
    private static void settle() {
        assertTrue(ForkJoinPool.commonPool().awaitQuiescence(20, TimeUnit.SECONDS), "background work finished");
    }

    private long customerCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(c) from " + CustomerEntity.class.getName() + " c", Long.class)
                        .getSingleResult());
    }

    private static long loginRequests(String email, String status) {
        return QuarkusTransaction.requiringNew().call(() ->
                CustomerLoginRequestEntity.count("email = ?1 and status = ?2", email, status));
    }

    private static void clearRequests() {
        QuarkusTransaction.requiringNew().run(() -> CustomerLoginRequestEntity.deleteAll());
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }
}
