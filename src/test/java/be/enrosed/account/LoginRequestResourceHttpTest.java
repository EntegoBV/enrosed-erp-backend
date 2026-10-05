package be.enrosed.account;

import be.enrosed.account.LoginRequestWriter.Candidate;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.mail.CustomerAccountMailer;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The page Login-aanvragen: the inbox, the customers a request may belong to, and the decision. */
@QuarkusTest
class LoginRequestResourceHttpTest {
    private static final String BASE = "/api/login-requests";
    private static final String PASSWORD = "roses-in-a-dome";

    @Inject LoginRequestWriter writer;
    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @Inject MockMailbox mailbox;
    @InjectSpy CustomerAccountMailer mailer;

    private final List<Long> createdCustomers = new ArrayList<>();

    @BeforeEach
    void startEmpty() {
        mailbox.clear();
        clearRequests();
    }

    @AfterEach
    void cleanUp() {
        clearRequests();
        createdCustomers.forEach(customers::delete);
        createdCustomers.clear();
    }

    @Test
    void staffEndpointsAreClosedToAnonymousUsers() {
        long id = request(LoginRequestWriter.ORDER_SCREEN, email());

        given().when().get(BASE).then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().when().get(BASE + "/summary").then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().when().get(BASE + "/" + id).then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().contentType("application/json").body(Map.of("customerId", 1))
                .when().post(BASE + "/" + id + "/approve")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().contentType("application/json").body("{}")
                .when().post(BASE + "/" + id + "/reject")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        assertEquals("PENDING", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void theWaitingListIsOldestFirstInPagesAndTheDecidedListsNewestDecisionFirst() {
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<Integer> waiting = new ArrayList<>();
        for (int minute = 4; minute >= 0; minute--) {
            waiting.addFirst((int) request(LoginRequestWriter.ORDER_SCREEN, email(), start.plusSeconds(60L * minute)));
        }
        long rejectedFirst = decided("REJECTED", start.plusSeconds(100));
        long rejectedLast = decided("REJECTED", start.plusSeconds(200));
        long approved = decided("APPROVED", start.plusSeconds(150));

        given().when().get(BASE)
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo(waiting))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].source", equalTo("ORDER_SCREEN"))
                .body("[0].laterSubmissions", hasSize(0))
                .body("[0].laterSubmissionsFull", equalTo(false))
                .body("[0].hasExistingLogin", equalTo(false))
                .body("[0].previouslyRejected", equalTo(false))
                .body("[0].customerId", nullValue())
                .body("[0].decidedAt", nullValue());
        given().queryParam("status", "pending").queryParam("page", 0).queryParam("size", 2)
                .when().get(BASE)
                .then().statusCode(200).body("id", equalTo(waiting.subList(0, 2)));
        given().queryParam("page", 1).queryParam("size", 2).when().get(BASE)
                .then().statusCode(200).body("id", equalTo(waiting.subList(2, 4)));
        given().queryParam("page", 2).queryParam("size", 2).when().get(BASE)
                .then().statusCode(200).body("id", equalTo(waiting.subList(4, 5)));
        given().queryParam("status", "REJECTED").when().get(BASE)
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo(List.of((int) rejectedLast, (int) rejectedFirst)));
        given().queryParam("status", "APPROVED").when().get(BASE)
                .then().statusCode(200).body("id", equalTo(List.of((int) approved)));

        given().queryParam("status", "OPEN").when().get(BASE).then().statusCode(400);
        given().queryParam("size", 101).when().get(BASE).then().statusCode(400);
        given().queryParam("page", -1).when().get(BASE).then().statusCode(400);
        given().when().get(BASE + "/987654321").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void theSummaryCountsWaitingRequestsAndNamesTheFullRoutes() {
        given().when().get(BASE + "/summary")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("pending", equalTo(0))
                .body("intakeFull", equalTo(false))
                .body("intakeFullSources", hasSize(0));

        request(LoginRequestWriter.NEW_LINK, email());
        decided("REJECTED", Instant.now());
        fill(LoginRequestWriter.QUOTE, LoginRequestWriter.INTAKE_CEILING);
        fill(LoginRequestWriter.ORDER_SCREEN, LoginRequestWriter.INTAKE_CEILING - 1);
        given().when().get(BASE + "/summary")
                .then().statusCode(200)
                .body("pending", equalTo(2 * LoginRequestWriter.INTAKE_CEILING))
                .body("intakeFull", equalTo(true))
                .body("intakeFullSources", contains("QUOTE"));

        fill(LoginRequestWriter.ORDER_SCREEN, 1);
        given().when().get(BASE + "/summary")
                .then().statusCode(200)
                .body("pending", equalTo(2 * LoginRequestWriter.INTAKE_CEILING + 1))
                .body("intakeFull", equalTo(true))
                .body("intakeFullSources", contains("ORDER_SCREEN", "QUOTE"));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aRequestShowsWhetherTheAddressHasALoginAndWhetherItWasRejectedBefore() {
        long customerId = customer("Bloemen Peeters BV", email(), "BE0123456789");
        String invited = email();
        accounts.grant(customerId, invited, "An Peeters", Language.NL);
        String active = email();
        activeLogin(customerId, active);
        String withdrawn = email();
        long withdrawnAccount = accounts.grant(customerId, withdrawn, null, Language.NL).accountId();
        accounts.withdraw(withdrawnAccount);
        String rejectedBefore = email();
        decided("REJECTED", rejectedBefore, Instant.now().minus(40, ChronoUnit.DAYS));

        given().when().get(BASE + "/" + request(LoginRequestWriter.ORDER_SCREEN, invited))
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("request.hasExistingLogin", equalTo(true))
                .body("request.previouslyRejected", equalTo(false))
                .body("existingAccount.status", equalTo("INVITED"))
                .body("existingAccount.email", equalTo(invited));
        given().when().get(BASE + "/" + request(LoginRequestWriter.ORDER_SCREEN, active))
                .then().statusCode(200)
                .body("request.hasExistingLogin", equalTo(true))
                .body("existingAccount.status", equalTo("ACTIVE"));
        Response withdrawnDetail = given().when().get(BASE + "/" + request(LoginRequestWriter.ORDER_SCREEN, withdrawn));
        withdrawnDetail.then().statusCode(200)
                .body("request.hasExistingLogin", equalTo(false))
                .body("existingAccount.id", equalTo((int) withdrawnAccount))
                .body("existingAccount.status", equalTo("DISABLED"))
                .body("matches.findAll { it.matchedOn.contains('LOGIN') }", hasSize(0));
        assertNoSecret(withdrawnDetail);
        given().when().get(BASE + "/" + request(LoginRequestWriter.ORDER_SCREEN, rejectedBefore))
                .then().statusCode(200)
                .body("request.hasExistingLogin", equalTo(false))
                .body("request.previouslyRejected", equalTo(true))
                .body("existingAccount", nullValue());
        given().when().get(BASE)
                .then().statusCode(200)
                .body("hasExistingLogin", equalTo(List.of(true, true, false, false)))
                .body("previouslyRejected", equalTo(List.of(false, false, false, true)));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void laterVersionsComeInArrivalOrderAndTheCustomerOfEveryQuoteIsAMatch() {
        String email = email();
        long firstQuote = customer("Quote Klant Een", email(), "BE0111111111");
        long secondQuote = customer("Quote Klant Twee", email(), "BE0222222222");
        long thirdQuote = customer("Quote Klant Drie", email(), "BE0333333333");
        long id = writer.storeDetached(quote(email, "Aanvrager BV", firstQuote, 21L)).requestId();
        writer.storeDetached(quote(email, "Aanvrager BV", secondQuote, 22L));
        writer.storeDetached(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", "Derde Versie BV",
                "BE", "BE0999999999", "An Peeters", null, "m".repeat(400), null, null, null));
        writer.storeDetached(quote(email, "Vierde Versie BV", thirdQuote, 23L));

        Response detail = given().when().get(BASE + "/" + id);

        detail.then().statusCode(200)
                .body("request.source", equalTo("QUOTE"))
                .body("request.customerId", equalTo((int) firstQuote))
                .body("request.customerCompany", equalTo("Quote Klant Een"))
                .body("request.salesOrderId", equalTo(21))
                .body("request.salesOrderNumber", equalTo("ENR-2026-0021"))
                .body("request.repeatCount", equalTo(3))
                .body("request.laterSubmissions", hasSize(3))
                .body("request.laterSubmissionsFull", equalTo(false))
                .body("request.laterSubmissions.source", equalTo(List.of("QUOTE", "ORDER_SCREEN", "QUOTE")))
                .body("request.laterSubmissions.companyName",
                        equalTo(List.of("Aanvrager BV", "Derde Versie BV", "Vierde Versie BV")))
                .body("request.laterSubmissions[0].customerId", equalTo((int) secondQuote))
                .body("request.laterSubmissions[0].salesOrderNumber", equalTo("ENR-2026-0022"))
                .body("request.laterSubmissions[0].at", notNullValue())
                .body("request.laterSubmissions[1].customerId", nullValue())
                .body("request.laterSubmissions[1].message", equalTo("m".repeat(300)))
                .body("request.laterSubmissions[2].customerId", equalTo((int) thirdQuote))
                .body("matches.customerId", equalTo(List.of((int) firstQuote, (int) secondQuote, (int) thirdQuote)))
                .body("matches.matchedOn", equalTo(List.of(List.of("QUOTE"), List.of("QUOTE"), List.of("QUOTE"))));

        for (int version = 5; version <= 7; version++) {
            writer.storeDetached(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL",
                    "Versie " + version, "BE", "BE0999999999", "An Peeters", null, null, null, null, null));
        }
        given().when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("request.repeatCount", equalTo(6))
                .body("request.laterSubmissions", hasSize(5))
                .body("request.laterSubmissionsFull", equalTo(true))
                .body("request.laterSubmissions[4].companyName", equalTo("Versie 6"));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void matchesAreTheLoginThenTheSameAddressThenTheRest() {
        String email = email();
        long byVat = customer("Zelfde BTW NV", email(), "BE 0123.456.789");
        long byEmail = customer("Zelfde Adres BV", " " + email.toUpperCase() + " ", "NL111111111B01");
        long byBoth = customer("Adres En BTW BV", email, "be0123456789");
        long holder = customer("Houder Van De Login", email(), "FR99999999999");
        customer("Bijna Zelfde Adres", email + "\u0001", "DE999999999");
        customer("Niets Gemeen", email(), "BE0999999999");
        long accountId = activeLogin(holder, email);
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);

        Response detail = given().when().get(BASE + "/" + id);

        detail.then().statusCode(200)
                .body("existingAccount.id", equalTo((int) accountId))
                .body("matches.customerId", equalTo(List.of((int) holder, (int) byEmail, (int) byBoth, (int) byVat)))
                .body("matches[0].matchedOn", equalTo(List.of("LOGIN")))
                .body("matches[0].company", equalTo("Houder Van De Login"))
                .body("matches[0].contact", equalTo("An Peeters"))
                .body("matches[0].vatNumber", equalTo("FR99999999999"))
                .body("matches[0].countryCode", equalTo("BE"))
                .body("matches[0].city", nullValue())
                .body("matches[0].logins", hasSize(1))
                .body("matches[0].logins[0].id", equalTo((int) accountId))
                .body("matches[0].logins[0].email", equalTo(email))
                .body("matches[0].logins[0].status", equalTo("ACTIVE"))
                .body("matches[1].matchedOn", equalTo(List.of("EMAIL")))
                .body("matches[1].logins", hasSize(0))
                .body("matches[2].matchedOn", equalTo(List.of("EMAIL", "VAT")))
                .body("matches[3].matchedOn", equalTo(List.of("VAT")));
        assertNoSecret(detail);

        /* The login was bound to that customer when it was given: no mismatch to confirm. */
        mailbox.clear();
        Response approved = approve(id, Map.of("customerId", holder));
        approved.then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("request.customerId", equalTo((int) holder))
                .body("account.id", equalTo((int) accountId))
                .body("account.status", equalTo("ACTIVE"))
                .body("invitation.sent", equalTo(true));
        assertEquals(1, mailbox.getTotalMessagesSent());
        assertEquals(1, activity(holder, "Websitelogin goedgekeurd voor " + email));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void approvingOntoAnExistingCustomerGivesALoginAndMailsExactlyOneLink() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", email, "BE0123456789");
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);

        given().contentType("application/json").body("{}")
                .when().post(BASE + "/" + id + "/approve").then().statusCode(400);
        given().contentType("application/json")
                .body(Map.of("customerId", customerId, "newCustomer", Map.of("company", "X", "vatNumber", "Y")))
                .when().post(BASE + "/" + id + "/approve").then().statusCode(400);
        approve(id, Map.of("customerId", 987654321)).then().statusCode(404);
        assertEquals("PENDING", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());

        Response approved = approve(id, Map.of("customerId", customerId));

        approved.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("request.id", equalTo((int) id))
                .body("request.status", equalTo("APPROVED"))
                .body("request.customerId", equalTo((int) customerId))
                .body("request.customerCompany", equalTo("Bloemen Peeters BV"))
                .body("request.accountId", notNullValue())
                .body("request.hasExistingLogin", equalTo(true))
                .body("request.decidedBy", equalTo("emre"))
                .body("request.decidedAt", notNullValue())
                .body("account.customerId", equalTo((int) customerId))
                .body("account.email", equalTo(email))
                .body("account.contactName", equalTo("An Peeters"))
                .body("account.language", equalTo("FR"))
                .body("account.status", equalTo("INVITED"))
                .body("account.lastLinkSentAt", notNullValue())
                .body("account.linkExpiresAt", notNullValue())
                .body("account.createdBy", equalTo("emre"))
                .body("invitation.sent", equalTo(true))
                .body("invitation.error", nullValue());
        assertEquals(approved.<String>path("account.linkExpiresAt"), approved.<String>path("invitation.expiresAt"));
        assertEquals(approved.<Integer>path("request.accountId"), approved.<Integer>path("account.id"));
        assertNoSecret(approved);
        assertEquals(1, mailbox.getTotalMessagesSent(), "one mail: the link, to the applicant alone");
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertEquals(1, mails.size());
        assertTrue(mails.getFirst().getHtml().contains("/fr/account/#activate=eci1_"));
        assertEquals(1, activity(customerId, "Websitelogin goedgekeurd voor " + email));
        assertEquals(0, activity(customerId, "Websitelogin gegeven aan " + email));

        approve(id, Map.of("customerId", customerId))
                .then().statusCode(409)
                .body("message", equalTo("Deze aanvraag is al behandeld"));
        given().contentType("application/json").body(Map.of("note", "te laat"))
                .when().post(BASE + "/" + id + "/reject")
                .then().statusCode(409)
                .body("message", equalTo("Deze aanvraag is al behandeld"));
        assertEquals(1, mailbox.getTotalMessagesSent());

        /* The decided request shows the login it led to. */
        given().when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("existingAccount.id", equalTo(approved.<Integer>path("account.id")))
                .body("existingAccount.status", equalTo("INVITED"));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void approvingWithANewCustomerCreatesItWithTheAddressOfTheRequest() {
        String email = email();
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);
        Map<String, Object> withoutVat = new HashMap<>(Map.of("company", "Nieuwe Klant NV", "countryCode", "nl",
                "contact", "Jan Jansen", "phone", "+31 20 000 00 00", "language", "DE"));

        approve(id, Map.of("newCustomer", withoutVat))
                .then().statusCode(409)
                .body("message", equalTo("BTW-nummer is verplicht"));
        assertEquals("PENDING", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());

        Map<String, Object> complete = new HashMap<>(withoutVat);
        complete.put("vatNumber", "NL123456789B01");
        Response approved = approve(id, Map.of("newCustomer", complete, "confirmEmailMismatch", false));

        approved.then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("request.customerCompany", equalTo("Nieuwe Klant NV"))
                .body("account.customerCompany", equalTo("Nieuwe Klant NV"))
                .body("account.status", equalTo("INVITED"))
                .body("account.language", equalTo("FR"))
                .body("invitation.sent", equalTo(true));
        long customerId = approved.<Integer>path("request.customerId");
        createdCustomers.add(customerId);
        Customer created = customers.get(customerId);
        assertEquals("Nieuwe Klant NV", created.company());
        assertEquals("NL123456789B01", created.vatNumber());
        assertEquals("NL", created.countryCode());
        assertEquals("Jan Jansen", created.contact());
        assertEquals("+31 20 000 00 00", created.phone());
        assertEquals(Language.DE, created.language());
        assertEquals(email, created.email());
        assertEquals("Aangemaakt bij goedkeuring van login-aanvraag " + approved.<String>path("request.reference"),
                created.notes());
        assertEquals(1, mailbox.getMailsSentTo(email).size());
        assertEquals(1, activity(customerId, "Websitelogin goedgekeurd voor " + email));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aLiveLoginOnAnotherCustomerBlocksAndAWithdrawnOneMoves() {
        String email = email();
        long holder = customer("Oude Klant BV", email(), "BE0111111111");
        long chosen = customer("Nieuwe Klant NV", email, "BE0222222222");
        long accountId = accounts.grant(holder, email, "An Peeters", Language.NL).accountId();
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);
        String blocked = "Dit e-mailadres heeft al een login bij klant Oude Klant BV."
                + " Kies die klant of trek die login eerst in.";

        approve(id, Map.of("customerId", chosen)).then().statusCode(409).body("message", equalTo(blocked));
        approve(id, Map.of("newCustomer", Map.of("company", "Derde BV", "vatNumber", "BE0333333333")))
                .then().statusCode(409).body("message", equalTo(blocked));
        assertEquals("PENDING", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());

        accounts.withdraw(accountId);
        Response approved = approve(id, Map.of("customerId", chosen));

        approved.then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("request.customerId", equalTo((int) chosen))
                .body("account.id", equalTo((int) accountId))
                .body("account.customerId", equalTo((int) chosen))
                .body("account.customerCompany", equalTo("Nieuwe Klant NV"))
                .body("account.status", equalTo("INVITED"))
                .body("account.disabledAt", nullValue())
                .body("invitation.sent", equalTo(true));
        assertEquals(1, mailbox.getMailsSentTo(email).size());
        assertEquals(1, activity(chosen, "Websitelogin " + email + " verplaatst van klant Oude Klant BV"));
        assertEquals(1, activity(holder, "Websitelogin " + email + " verplaatst naar klant Nieuwe Klant NV"));
        assertEquals(1, activity(chosen, "Websitelogin goedgekeurd voor " + email));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void anotherAddressThanTheCustomersNeedsAnExplicitConfirmation() {
        String email = email();
        long other = customer("Andere Mail BV", email(), "BE0123456789");
        long none = customer("Geen Mail BV", null, "BE0123456789");
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);
        String mismatch = "Het e-mailadres van de aanvraag wijkt af van het e-mailadres van de klant."
                + " Bevestig uitdrukkelijk dat deze persoon bij de klant hoort.";

        approve(id, Map.of("customerId", other)).then().statusCode(409).body("message", equalTo(mismatch));
        approve(id, Map.of("customerId", other, "confirmEmailMismatch", false))
                .then().statusCode(409).body("message", equalTo(mismatch));
        approve(id, Map.of("customerId", none)).then().statusCode(409).body("message", equalTo(mismatch));
        assertEquals("PENDING", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());

        approve(id, Map.of("customerId", other, "confirmEmailMismatch", true))
                .then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("account.customerId", equalTo((int) other))
                .body("account.email", equalTo(email))
                .body("invitation.sent", equalTo(true));

        assertEquals(1, activity(other, "Websitelogin goedgekeurd voor " + email
                + " (wijkt af van het e-mailadres van de klant; uitdrukkelijk bevestigd)"));
        assertEquals(1, mailbox.getMailsSentTo(email).size());
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void approvingANewLinkRequestSendsAFreshLinkToTheSameLogin() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", email(), "BE0123456789");
        long accountId = activeLogin(customerId, email);
        long id = writer.storeDetached(Candidate.newLink(email)).requestId();
        mailbox.clear();

        given().when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("request.source", equalTo("NEW_LINK"))
                .body("request.customerId", equalTo((int) customerId))
                .body("request.accountId", equalTo((int) accountId))
                .body("request.companyName", equalTo("Bloemen Peeters BV"))
                .body("request.hasExistingLogin", equalTo(true))
                .body("matches.customerId", equalTo(List.of((int) customerId)))
                .body("matches[0].matchedOn", equalTo(List.of("LOGIN")));

        /* The body is ignored for this kind of request. */
        Map<String, Object> ignored = new HashMap<>();
        ignored.put("customerId", null);
        ignored.put("newCustomer", null);
        ignored.put("confirmEmailMismatch", false);
        Response approved = approve(id, ignored);

        approved.then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("request.accountId", equalTo((int) accountId))
                .body("account.id", equalTo((int) accountId))
                .body("account.status", equalTo("ACTIVE"))
                .body("account.linkExpiresAt", notNullValue())
                .body("invitation.sent", equalTo(true));
        assertEquals(1, mailbox.getMailsSentTo(email).size());
        ArgumentCaptor<CustomerAccountMailer.Invitation> sent =
                ArgumentCaptor.forClass(CustomerAccountMailer.Invitation.class);
        verify(mailer).sendInvitation(sent.capture());
        assertEquals(CustomerAccountMailer.Kind.NEW_LINK_KEEPS_PASSWORD, sent.getValue().kind());
        assertEquals(1, activity(customerId, "Websitelogin goedgekeurd voor " + email));

        /* Withdrawn while a second request waited: staff give the login again on the customer. */
        long later = request(LoginRequestWriter.NEW_LINK, email, Instant.now(), customerId, accountId);
        accounts.withdraw(accountId);
        mailbox.clear();
        approve(later, Map.of("customerId", customerId))
                .then().statusCode(409)
                .body("message", equalTo("Deze login is ingetrokken. Geef de login opnieuw bij de klant"
                        + " (Klanten, blok Websitelogin)."));
        assertEquals("PENDING", status(later));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void rejectingIsSilentAndFinal() {
        String email = email();
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);

        Response rejected = given().contentType("application/json").body(Map.of("note", "  " + "n".repeat(600)))
                .when().post(BASE + "/" + id + "/reject");

        rejected.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo((int) id))
                .body("status", equalTo("REJECTED"))
                .body("decidedBy", equalTo("emre"))
                .body("decidedAt", notNullValue())
                .body("decisionNote", equalTo("n".repeat(500)))
                .body("accountId", nullValue())
                .body("hasExistingLogin", equalTo(false));
        assertEquals(0, mailbox.getTotalMessagesSent(), "no mail, to nobody");
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> CustomerAccountEntity.count("email", email)));

        given().contentType("application/json").body("{}")
                .when().post(BASE + "/" + id + "/reject")
                .then().statusCode(409).header("Cache-Control", "no-store")
                .body("message", equalTo("Deze aanvraag is al behandeld"));
        approve(id, Map.of("newCustomer", Map.of("company", "X", "vatNumber", "Y")))
                .then().statusCode(409).header("Cache-Control", "no-store")
                .body("message", equalTo("Deze aanvraag is al behandeld"));
        given().contentType("application/json").body("{}")
                .when().post(BASE + "/987654321/reject")
                .then().statusCode(404).header("Cache-Control", "no-store");
        approve(987654321L, Map.of("customerId", 1))
                .then().statusCode(404).header("Cache-Control", "no-store");
        given().when().get(BASE + "/987654321")
                .then().statusCode(404).header("Cache-Control", "no-store");
        /* Only these two staff resources: another staff error keeps its own headers. */
        given().when().get("/api/customers/987654321")
                .then().statusCode(404).header("Cache-Control", org.hamcrest.Matchers.not(equalTo("no-store")));

        long withoutNote = request(LoginRequestWriter.ORDER_SCREEN, email);
        given().contentType("application/json").body("{\"note\":null}")
                .when().post(BASE + "/" + withoutNote + "/reject")
                .then().statusCode(200)
                .body("decisionNote", nullValue())
                .body("previouslyRejected", equalTo(true));
        given().queryParam("status", "REJECTED").when().get(BASE)
                .then().statusCode(200).body("$", hasSize(2));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aMailFailureNeverUndoesTheApprovalAndTheNextLinkIsAFirstMailAgain() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", email, "BE0123456789");
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);
        doThrow(new BusinessRuleException("De mailserver weigert")).when(mailer).sendInvitation(any());

        Response approved = approve(id, Map.of("customerId", customerId));

        approved.then().statusCode(200)
                .body("request.status", equalTo("APPROVED"))
                .body("account.status", equalTo("INVITED"))
                .body("account.lastLinkSentAt", nullValue())
                .body("account.lastLinkError", equalTo("De mailserver weigert"))
                .body("account.linkExpiresAt", notNullValue())
                .body("invitation.sent", equalTo(false))
                .body("invitation.error", equalTo("De mailserver weigert"));
        assertNoSecret(approved);
        assertEquals("APPROVED", status(id));
        assertEquals(0, mailbox.getTotalMessagesSent());

        doCallRealMethod().when(mailer).sendInvitation(any());
        int accountId = approved.path("account.id");
        given().when().post("/api/customer-logins/" + accountId + "/invitation")
                .then().statusCode(200)
                .body("invitation.sent", equalTo(true))
                .body("account.lastLinkError", nullValue());

        ArgumentCaptor<CustomerAccountMailer.Invitation> sent =
                ArgumentCaptor.forClass(CustomerAccountMailer.Invitation.class);
        verify(mailer, times(2)).sendInvitation(sent.capture());
        assertEquals(CustomerAccountMailer.Kind.FIRST, sent.getAllValues().get(0).kind());
        assertEquals(CustomerAccountMailer.Kind.FIRST, sent.getAllValues().get(1).kind(),
                "no mail had left yet, so this is not called a new link");
        assertEquals(1, mailbox.getMailsSentTo(email).size());
    }

    // ------------------------------------------------------------------ fixtures

    private static Response approve(long id, Map<String, Object> body) {
        return given().contentType("application/json").body(body).when().post(BASE + "/" + id + "/approve");
    }

    /** Whatever a mailer puts in its reason, no link reaches the database or a staff answer. */
    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aMailFailureThatEchoesTheLinkIsStoredAndAnsweredWithoutIt() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", email, "BE0123456789");
        long id = request(LoginRequestWriter.ORDER_SCREEN, email);
        doAnswer(call -> {
            CustomerAccountMailer.Invitation invitation = call.getArgument(0);
            throw new BusinessRuleException("maildienst antwoordde 400 - bad htmlContent: <a href=\"https://"
                    + "enrosed.com/nl/account/#activate=" + invitation.token() + "\"> token "
                    + invitation.token());
        }).when(mailer).sendInvitation(any());

        Response approved = approve(id, Map.of("customerId", customerId));

        approved.then().statusCode(200)
                .body("invitation.sent", equalTo(false))
                .body("invitation.error", equalTo("maildienst antwoordde 400 - bad htmlContent: <a href=\"https://"
                        + "enrosed.com/nl/account/#activate=[...] token [...]"))
                .body("account.lastLinkError", equalTo(approved.<String>path("invitation.error")));
        assertNoSecret(approved);
        int accountId = approved.path("account.id");
        String stored = QuarkusTransaction.requiringNew().call(() ->
                CustomerAccountEntity.<CustomerAccountEntity>findById((long) accountId).lastLinkError);
        assertFalse(stored.contains("eci1_"), stored);
        assertNoSecret(given().queryParam("customerId", customerId).when().get("/api/customer-logins"));
    }

    /**
     * Two grants for an address without a login can both find none; the loser's insert hits the
     * unique e-mail constraint. That exact failure is what the staff resources answer as 409.
     */
    @Test
    void theSecondInsertOfOneAddressIsRecognisedAsADuplicateLogin() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", email(), "BE0123456789");
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                QuarkusTransaction.requiringNew().run(() -> {
                    for (int row = 0; row < 2; row++) {
                        CustomerAccountEntity account = new CustomerAccountEntity();
                        account.customerId = customerId;
                        account.email = email;
                        account.language = "NL";
                        account.status = CustomerAccountService.INVITED;
                        account.createdAt = java.time.Instant.now();
                        account.updatedAt = account.createdAt;
                        account.persistAndFlush();
                    }
                }));

        assertTrue(CustomerAccountService.isDuplicateLogin(failure), String.valueOf(failure));
        assertTrue(CustomerAccountService.isDuplicateLogin(new IllegalStateException("wrapped", failure)));
        assertFalse(CustomerAccountService.isDuplicateLogin(new BusinessRuleException("iets anders")));
        assertFalse(CustomerAccountService.isDuplicateLogin(new org.hibernate.exception.ConstraintViolationException(
                "another constraint", new java.sql.SQLException("uq_customer_vat"), "uq_customer_vat")));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> CustomerAccountEntity.count("email", email)));
    }

    private long customer(String company, String email, String vatNumber) {
        Customer customer = customers.create(new Customer(null, company, "An Peeters", email, null,
                vatNumber, "BE", Language.NL, null, null, null, null, null, null, null));
        createdCustomers.add(customer.id());
        return customer.id();
    }

    /** A committed ACTIVE login with PASSWORD; returns the account id. */
    private long activeLogin(long customerId, String email) {
        var grant = accounts.grant(customerId, email, "An Peeters", Language.NL);
        accounts.activate(grant.rawToken(), PASSWORD);
        return grant.accountId();
    }

    private static Candidate quote(String email, String company, long customerId, long orderId) {
        return new Candidate(LoginRequestWriter.QUOTE, email, "NL", company, "BE", "BE0123456789",
                "An Peeters", null, null, customerId, orderId, "ENR-2026-00" + orderId);
    }

    private static long request(String source, String email) {
        return request(source, email, Instant.now());
    }

    private static long request(String source, String email, Instant createdAt) {
        return request(source, email, createdAt, null, null);
    }

    private static long request(String source, String email, Instant createdAt, Long customerId, Long accountId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            CustomerLoginRequestEntity row = row("PENDING", source, email, createdAt);
            row.customerId = customerId;
            row.accountId = accountId;
            row.persist();
            return row.id;
        });
    }

    private static long decided(String status, Instant decidedAt) {
        return decided(status, email(), decidedAt);
    }

    private static long decided(String status, String email, Instant decidedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            CustomerLoginRequestEntity row = row(status, LoginRequestWriter.ORDER_SCREEN, email,
                    decidedAt.minusSeconds(3_600));
            row.decidedAt = decidedAt;
            row.decidedBy = "berat";
            row.persist();
            return row.id;
        });
    }

    private static void fill(String source, int count) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (int open = 0; open < count; open++) row("PENDING", source, email(), Instant.now()).persist();
        });
    }

    private static CustomerLoginRequestEntity row(String status, String source, String email, Instant createdAt) {
        CustomerLoginRequestEntity row = new CustomerLoginRequestEntity();
        row.reference = LoginRequestWriter.newReference();
        row.status = status;
        row.source = source;
        row.language = "FR";
        row.companyName = "Bloemen Peeters BV";
        row.companyCountryCode = "BE";
        row.vatNumber = "BE0123456789";
        row.contactName = "An Peeters";
        row.email = email;
        row.createdAt = createdAt;
        row.updatedAt = createdAt;
        return row;
    }

    private static String status(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                CustomerLoginRequestEntity.<CustomerLoginRequestEntity>findById(id).status);
    }

    /** Lines in the customer's logbook with exactly this text, by the logged-in staff member. */
    private static long activity(long customerId, String summary) {
        return QuarkusTransaction.requiringNew().call(() -> ActivityLogEntity.count(
                "entityType = 'CUSTOMER' and entityId = ?1 and summary = ?2 and actorUsername = 'emre'",
                String.valueOf(customerId), summary));
    }

    private static void clearRequests() {
        QuarkusTransaction.requiringNew().run(() -> CustomerLoginRequestEntity.deleteAll());
    }

    private static void assertNoSecret(Response response) {
        String body = response.asString();
        for (String secret : new String[]{"passwordHash", "tokenHash", "rawToken", "eci1_", "ecs1_", "$2"}) {
            assertFalse(body.contains(secret), secret + " in " + body);
        }
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
