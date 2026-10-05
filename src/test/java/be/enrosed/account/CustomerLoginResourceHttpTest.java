package be.enrosed.account;

import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Websitelogin block of the customer sheet: give, new link, withdraw. */
@QuarkusTest
class CustomerLoginResourceHttpTest {
    private static final String BASE = "/api/customer-logins";

    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @Inject MockMailbox mailbox;

    private final List<Long> createdCustomers = new ArrayList<>();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
    }

    @AfterEach
    void removeCustomers() {
        createdCustomers.forEach(customers::delete);
        createdCustomers.clear();
    }

    @Test
    void staffEndpointsAreClosedToAnonymousUsers() {
        given().when().get(BASE + "?customerId=1")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().contentType("application/json").body("{\"customerId\":1}")
                .when().post(BASE)
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().when().post(BASE + "/1/invitation")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().when().post(BASE + "/1/withdraw")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        assertTrue(mailbox.getTotalMessagesSent() == 0);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void staffGiveALoginSendANewLinkAndWithdrawIt() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV", " " + email.toUpperCase() + " ");

        given().when().get(BASE).then().statusCode(400);
        given().queryParam("customerId", customerId).when().get(BASE)
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("$", hasSize(0));

        Response given = given().contentType("application/json").body(Map.of("customerId", customerId))
                .when().post(BASE);
        given.then().statusCode(201)
                .header("Cache-Control", "no-store")
                .body("account.customerId", equalTo((int) customerId))
                .body("account.customerCompany", equalTo("Bloemen Peeters BV"))
                .body("account.email", equalTo(email))
                .body("account.contactName", equalTo("An Peeters"))
                .body("account.language", equalTo("FR"))
                .body("account.status", equalTo("INVITED"))
                .body("account.passwordSetAt", nullValue())
                .body("account.lastLinkSentAt", notNullValue())
                .body("account.lastLinkError", nullValue())
                .body("account.linkExpiresAt", notNullValue())
                .body("account.createdBy", equalTo("emre"))
                .body("account.activeSessions", equalTo(0))
                .body("invitation.sent", equalTo(true))
                .body("invitation.expiresAt", notNullValue())
                .body("invitation.error", nullValue());
        int accountId = given.path("account.id");
        assertEquals(given.<String>path("account.linkExpiresAt"), given.<String>path("invitation.expiresAt"));
        String firstLink = onlyLinkMailedTo(email);
        assertNoSecret(given);

        Response listed = given().queryParam("customerId", customerId).when().get(BASE);
        listed.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("$", hasSize(1))
                .body("[0].id", equalTo(accountId))
                .body("[0].status", equalTo("INVITED"));
        assertNoSecret(listed);

        mailbox.clear();
        Response again = given().when().post(BASE + "/" + accountId + "/invitation");
        again.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("account.id", equalTo(accountId))
                .body("account.status", equalTo("INVITED"))
                .body("invitation.sent", equalTo(true));
        assertNoSecret(again);
        String secondLink = onlyLinkMailedTo(email);
        assertFalse(firstLink.equals(secondLink), "a new link is a new token");
        given().when().post(BASE + "/987654321/invitation").then().statusCode(404);

        String token = secondLink.substring(secondLink.indexOf("#activate=") + "#activate=".length());
        accounts.activate(token, "roses-in-a-dome");
        Response withdrawn = given().contentType("application/json").body("{}")
                .when().post(BASE + "/" + accountId + "/withdraw");
        withdrawn.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo(accountId))
                .body("status", equalTo("DISABLED"))
                .body("activeSessions", equalTo(0))
                .body("linkExpiresAt", nullValue())
                .body("disabledBy", equalTo("emre"))
                .body("disabledAt", notNullValue());
        assertNoSecret(withdrawn);
        given().when().post(BASE + "/" + accountId + "/withdraw")
                .then().statusCode(409)
                .body("message", equalTo("Deze login is al ingetrokken"));
        given().when().post(BASE + "/987654321/withdraw").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aLoginNeedsAnExistingCustomerAndAUsableAddress() {
        long without = customer("Zonder Mail BV", null);

        given().contentType("application/json").body("{}")
                .when().post(BASE).then().statusCode(400);
        given().contentType("application/json").body(Map.of("customerId", 987654321))
                .when().post(BASE).then().statusCode(404);
        given().contentType("application/json").body(Map.of("customerId", without))
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", equalTo("Deze klant heeft geen e-mailadres"));
        given().contentType("application/json").body(Map.of("customerId", without, "email", "geen adres"))
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", equalTo("Vul een geldig e-mailadres in"));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aLiveLoginElsewhereBlocksAndAWithdrawnOneMovesToTheChosenCustomer() {
        String email = email();
        long holder = customer("Oude Klant BV", email());
        long chosen = customer("Nieuwe Klant NV", email());
        Map<String, Object> onHolder = new HashMap<>(Map.of("customerId", holder, "email", email));
        Map<String, Object> onChosen = new HashMap<>(Map.of("customerId", chosen, "email", email));
        int accountId = given().contentType("application/json").body(onHolder)
                .when().post(BASE).then().statusCode(201).extract().path("account.id");

        given().contentType("application/json").body(onChosen)
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", equalTo("Dit e-mailadres heeft al een login bij klant Oude Klant BV."
                        + " Trek die login eerst in."));

        given().when().post(BASE + "/" + accountId + "/withdraw").then().statusCode(200);
        mailbox.clear();
        Response moved = given().contentType("application/json").body(onChosen).when().post(BASE);
        moved.then().statusCode(201)
                .body("account.id", equalTo(accountId))
                .body("account.customerId", equalTo((int) chosen))
                .body("account.customerCompany", equalTo("Nieuwe Klant NV"))
                .body("account.status", equalTo("INVITED"))
                .body("account.disabledAt", nullValue())
                .body("invitation.sent", equalTo(true));
        assertNoSecret(moved);
        onlyLinkMailedTo(email);
        given().queryParam("customerId", holder).when().get(BASE)
                .then().statusCode(200).body("$", hasSize(0));
        given().queryParam("customerId", chosen).when().get(BASE)
                .then().statusCode(200).body("$", hasSize(1));
    }

    // ------------------------------------------------------------------ fixtures

    private long customer(String company, String email) {
        Customer customer = customers.create(new Customer(null, company, "An Peeters", email, null,
                "BE0123456789", "BE", Language.FR, null, null, null, null, null, null, null));
        createdCustomers.add(customer.id());
        return customer.id();
    }

    /** Exactly one invitation left, to the customer alone; returns the link it carries. */
    private String onlyLinkMailedTo(String email) {
        assertEquals(1, mailbox.getTotalMessagesSent());
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertEquals(1, mails.size());
        assertTrue(mails.getFirst().getBcc().isEmpty() && mails.getFirst().getCc().isEmpty());
        String html = mails.getFirst().getHtml();
        int start = html.indexOf("href=\"", html.indexOf("bgcolor=\"#e0be73\"")) + "href=\"".length();
        String link = html.substring(start, html.indexOf('"', start));
        assertTrue(link.matches("https://enrosed\\.com/fr/account/#activate=eci1_[A-Za-z0-9_-]{43}"), link);
        return link;
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
