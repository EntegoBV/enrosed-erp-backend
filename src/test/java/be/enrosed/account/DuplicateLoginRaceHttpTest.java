package be.enrosed.account;

import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * Two grants for an address without a login can both find none (a double click on "Login
 * geven", or an approval beside it). The loser's insert hits the unique e-mail constraint;
 * staff read a conflict that tells them to reload, never a 500. The lost race itself is
 * played by the spy; that a real second insert is recognised is pinned in
 * LoginRequestResourceHttpTest.
 */
@QuarkusTest
class DuplicateLoginRaceHttpTest {
    @Inject CustomerService customers;
    @Inject MockMailbox mailbox;
    @InjectSpy CustomerAccountService accounts;

    private Long customerId;
    private Long requestId;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> CustomerLoginRequestEntity.deleteById(requestId));
        customers.delete(customerId);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aGrantThatLosesTheRaceForAnAddressAnswers409() {
        String email = "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        customerId = customers.create(new Customer(null, "Bloemen Peeters BV", "An Peeters", email, null,
                "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null)).id();
        requestId = QuarkusTransaction.requiringNew().call(() -> {
            CustomerLoginRequestEntity row = new CustomerLoginRequestEntity();
            row.reference = LoginRequestWriter.newReference();
            row.status = "PENDING";
            row.source = LoginRequestWriter.ORDER_SCREEN;
            row.language = "NL";
            row.companyName = "Bloemen Peeters BV";
            row.companyCountryCode = "BE";
            row.vatNumber = "BE0123456789";
            row.contactName = "An Peeters";
            row.email = email;
            row.createdAt = Instant.now();
            row.updatedAt = row.createdAt;
            row.persist();
            return row.id;
        });
        mailbox.clear();
        PersistenceException lost = new PersistenceException(new ConstraintViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint"),
                "uq_customer_account_email"));
        doThrow(lost).when(accounts).grantByStaff(anyLong(), any());
        doThrow(lost).when(accounts).grant(anyLong(), any(), any(), any());

        given().contentType("application/json").body(Map.of("customerId", customerId))
                .when().post("/api/customer-logins")
                .then().statusCode(409).header("Cache-Control", "no-store")
                .body("message", equalTo(CustomerAccountService.DUPLICATE_LOGIN));
        given().contentType("application/json").body(Map.of("customerId", customerId))
                .when().post("/api/login-requests/" + requestId + "/approve")
                .then().statusCode(409).header("Cache-Control", "no-store")
                .body("message", equalTo(CustomerAccountService.DUPLICATE_LOGIN));

        assertEquals("PENDING", QuarkusTransaction.requiringNew().call(() ->
                        CustomerLoginRequestEntity.<CustomerLoginRequestEntity>findById(requestId).status),
                "the approval rolled back; staff can decide again");
        assertEquals(0, mailbox.getTotalMessagesSent());
    }
}
