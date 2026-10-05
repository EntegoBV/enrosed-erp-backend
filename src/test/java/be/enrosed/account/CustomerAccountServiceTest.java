package be.enrosed.account;

import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.mail.CustomerAccountMailer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The state machine of a website login, its one-time link and its sessions. */
@QuarkusTest
@TestTransaction
class CustomerAccountServiceTest {
    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");
    private static final String PASSWORD = "roses-in-a-dome";

    @Inject CustomerAccountService accounts;
    @Inject CustomerSessionGuard guard;
    @Inject CustomerService customers;
    @InjectMock CustomerAccountMailer mailer;

    @AfterEach
    void realClock() {
        accounts.useClock(Clock.systemUTC());
    }

    @Test
    void firstGrantCreatesAnInvitedLoginWithOneHashedLinkThatLivesTheConfiguredDays() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();

        var grant = accounts.grant(customer.id(), "  " + email.toUpperCase() + " ", "An Peeters", Language.FR);

        CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
        assertEquals(CustomerAccountService.INVITED, account.status);
        assertEquals(email, account.email, "stored in the one normal form");
        assertEquals(customer.id(), account.customerId);
        assertEquals("An Peeters", account.contactName);
        assertEquals("FR", account.language);
        assertEquals("systeem", account.createdBy);
        assertNull(account.passwordHash);
        assertEquals(CustomerAccountMailer.Kind.FIRST, grant.kind());
        assertTrue(AccountTokens.isInvitationToken(grant.rawToken()));
        assertEquals(START.plus(Duration.ofDays(7)), grant.expiresAt(),
                "issue time plus enrosed.customer-account.invite-ttl-days");
        List<CustomerAccountTokenEntity> tokens = CustomerAccountTokenEntity.list("accountId", account.id);
        assertEquals(1, tokens.size());
        assertEquals(AccountTokens.hash(grant.rawToken()), tokens.getFirst().tokenHash);
        assertEquals(grant.expiresAt(), tokens.getFirst().expiresAt);
        assertFalse(grant.toString().contains(grant.rawToken()), "a logged grant never shows the link");
        assertEquals(grant.expiresAt(), accounts.view(account.id).linkExpiresAt());
    }

    @Test
    void aLinkLifetimeOutsideTwoToThirtyDaysFallsBackToSeven() {
        assertEquals(7, CustomerAccountService.inviteTtlDays(1));
        assertEquals(7, CustomerAccountService.inviteTtlDays(45));
        assertEquals(2, CustomerAccountService.inviteTtlDays(2));
        assertEquals(30, CustomerAccountService.inviteTtlDays(30));
        assertEquals(5, CustomerAccountService.inviteTtlDays(5));
    }

    @Test
    void theMailIsAFirstMailUntilOneHasLeftAndANewLinkAfterwards() {
        Customer customer = customer("Bloemen Peeters BV");
        var first = accounts.grant(customer.id(), email(), "An", Language.NL);
        assertEquals(CustomerAccountMailer.Kind.FIRST, first.kind());

        doThrow(new BusinessRuleException("De maildienst weigert")).when(mailer).sendInvitation(any());
        AccountDtos.Invitation failed = accounts.deliver(first);
        assertFalse(failed.sent());
        assertEquals("De maildienst weigert", failed.error());
        assertEquals(first.expiresAt(), failed.expiresAt());
        CustomerAccountEntity account = CustomerAccountEntity.findById(first.accountId());
        assertNull(account.lastLinkSentAt);
        assertEquals("De maildienst weigert", account.lastLinkError);

        var retry = accounts.reissue(first.accountId());
        assertEquals(CustomerAccountMailer.Kind.FIRST, retry.kind(),
                "no invitation mail has left yet, so the retry is still the first mail");
        assertEquals(CustomerAccountService.INVITED, account.status);
        assertEquals(1, CustomerAccountTokenEntity.count("accountId", account.id), "one live link per login");
        assertInvalidToken(() -> accounts.inspect(first.rawToken()));

        reset(mailer);
        AccountDtos.Invitation sent = accounts.deliver(retry);
        assertTrue(sent.sent());
        assertNull(sent.error());
        assertNotNull(account.lastLinkSentAt);
        assertNull(account.lastLinkError);
        ArgumentCaptor<CustomerAccountMailer.Invitation> mail =
                ArgumentCaptor.forClass(CustomerAccountMailer.Invitation.class);
        verify(mailer).sendInvitation(mail.capture());
        assertEquals(account.email, mail.getValue().to());
        assertEquals(Language.NL, mail.getValue().language());
        assertEquals("An", mail.getValue().contactName());
        assertEquals("Bloemen Peeters BV", mail.getValue().company());
        assertEquals(retry.rawToken(), mail.getValue().token());
        assertEquals(7, mail.getValue().validDays(), "the number of days comes from configuration");
        assertEquals(CustomerAccountMailer.Kind.FIRST, mail.getValue().kind());

        assertEquals(CustomerAccountMailer.Kind.NEW_LINK, accounts.reissue(account.id).kind());
        assertEquals(CustomerAccountMailer.Kind.NEW_LINK,
                accounts.grant(customer.id(), account.email, "An", Language.NL).kind());
        assertEquals(CustomerAccountService.INVITED, account.status);
    }

    @Test
    void deliverNeverThrowsWhateverTheMailerDoes() {
        Customer customer = customer("Bloemen Peeters BV");
        var grant = accounts.grant(customer.id(), email(), null, null);
        doThrow(new IllegalStateException("eci1_secret in a provider message"))
                .when(mailer).sendInvitation(any());

        AccountDtos.Invitation outcome = assertDoesNotThrow(() -> accounts.deliver(grant));

        assertFalse(outcome.sent());
        assertEquals("De mail kon niet verzonden worden", outcome.error(),
                "only our own sentences are kept, never a provider's text");
        CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
        assertEquals("De mail kon niet verzonden worden", account.lastLinkError);
        assertEquals("NL", account.language, "without a language the customer's own is used");
    }

    @Test
    void activationChoosesThePasswordOnceAndLogsTheCustomerIn() {
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        var grant = accounts.grant(customer.id(), email, "An Peeters", Language.NL);

        AccountDtos.TokenInfo info = accounts.inspect(grant.rawToken());
        assertEquals(email, info.email());
        assertEquals("Bloemen Peeters BV", info.company());
        assertEquals(grant.expiresAt(), info.expiresAt());

        AccountDtos.SessionResponse session = accounts.activate(grant.rawToken(), PASSWORD);

        CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
        assertEquals(CustomerAccountService.ACTIVE, account.status);
        assertNotNull(account.passwordSetAt);
        assertTrue(account.passwordHash.startsWith("$2"), "bcrypt, never the password itself");
        assertNotEquals(PASSWORD, account.passwordHash);
        assertEquals(0, CustomerAccountTokenEntity.count("accountId", account.id));
        assertTrue(AccountTokens.isSessionToken(session.sessionToken()));
        assertEquals(new AccountDtos.Profile(email, "An Peeters", "Bloemen Peeters BV", "BE",
                "BE0123456789", "+32 14 00 00 00", "NL"), session.profile());
        List<CustomerSessionEntity> sessions = CustomerSessionEntity.list("accountId", account.id);
        assertEquals(1, sessions.size());
        assertEquals(AccountTokens.hash(session.sessionToken()), sessions.getFirst().tokenHash,
                "the database never holds a raw session token");
        assertEquals(account.id, guard.require(bearer(session.sessionToken())).accountId());
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(customer.id()),
                "Websitelogin geactiveerd: wachtwoord gekozen (" + email + ")"));

        assertInvalidToken(() -> accounts.activate(grant.rawToken(), "another-password"));
        assertInvalidToken(() -> accounts.inspect(grant.rawToken()));
        assertEquals(session.profile(), accounts.login(email, PASSWORD).profile(),
                "the second attempt changed nothing");
    }

    @Test
    void aRefusedPasswordLeavesTheLinkUsable() {
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        var grant = accounts.grant(customer.id(), email, null, Language.NL);

        assertPasswordRefused("REQUIRED", grant.rawToken(), "   ");
        assertPasswordRefused("TOO_SHORT", grant.rawToken(), "123456789");
        assertPasswordRefused("TOO_LONG", grant.rawToken(), "é".repeat(36) + "x");
        assertPasswordRefused("INVALID", grant.rawToken(), email.toUpperCase());
        assertNull(CustomerAccountService.passwordError("é".repeat(36), email), "72 bytes is allowed");
        assertNull(CustomerAccountService.passwordError("1234567890", email), "ten characters is enough");

        assertNotNull(accounts.activate(grant.rawToken(), PASSWORD).sessionToken());
    }

    @Test
    void anExpiredLinkIsAsInvalidAsAnUnknownOne() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        var grant = accounts.grant(customer.id(), email(), null, Language.NL);

        at(START.plus(Duration.ofDays(7)).minusSeconds(1));
        assertNotNull(accounts.inspect(grant.rawToken()));
        at(START.plus(Duration.ofDays(7)));
        assertInvalidToken(() -> accounts.inspect(grant.rawToken()));
        assertInvalidToken(() -> accounts.activate(grant.rawToken(), PASSWORD));
        assertInvalidToken(() -> accounts.inspect("eci1_" + "A".repeat(43)));
        assertInvalidToken(() -> accounts.inspect("ecs1_" + "A".repeat(43)));
        assertInvalidToken(() -> accounts.inspect(null));
        assertEquals(grant.expiresAt(), accounts.view(grant.accountId()).linkExpiresAt(),
                "staff still see when the unused link ended");
    }

    @Test
    void aNewLinkForAnActiveLoginKeepsPasswordAndSessionsUntilItIsUsed() {
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        String firstSession = activeLogin(customer, email);
        String secondSession = accounts.login(email, PASSWORD).sessionToken();

        var again = accounts.reissue(accountId(email));

        assertEquals(CustomerAccountMailer.Kind.NEW_LINK_KEEPS_PASSWORD, again.kind());
        assertEquals(CustomerAccountMailer.Kind.NEW_LINK_KEEPS_PASSWORD,
                accounts.grant(customer.id(), email, null, Language.NL).kind());
        CustomerAccountEntity account = CustomerAccountEntity.findById(again.accountId());
        assertEquals(CustomerAccountService.ACTIVE, account.status);
        assertNotNull(accounts.login(email, PASSWORD), "the password keeps working");
        assertNotNull(guard.require(bearer(firstSession)), "and so do the sessions");
        assertEquals(3, accounts.view(account.id).activeSessions());

        var last = accounts.reissue(account.id);
        assertInvalidToken(() -> accounts.inspect(again.rawToken()));
        String fresh = accounts.activate(last.rawToken(), "a-brand-new-password").sessionToken();

        assertEquals(1, CustomerSessionEntity.count("accountId", account.id),
                "choosing a new password ends every other session");
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(firstSession)));
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(secondSession)));
        assertNotNull(guard.require(bearer(fresh)));
        assertThrows(CustomerUnauthorizedException.class, () -> accounts.login(email, PASSWORD));
        assertNotNull(accounts.login(email, "a-brand-new-password"));
    }

    @Test
    void withdrawingEndsEverythingAndGivingAgainStartsWithAFirstMail() {
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        String session = activeLogin(customer, email);
        long id = accountId(email);
        accounts.deliver(accounts.reissue(id));
        CustomerAccountEntity account = CustomerAccountEntity.findById(id);
        assertNotNull(account.lastLinkSentAt);

        AccountDtos.AccountView withdrawn = accounts.withdraw(id);

        assertEquals(CustomerAccountService.DISABLED, withdrawn.status());
        assertEquals(0, withdrawn.activeSessions());
        assertNull(withdrawn.linkExpiresAt());
        assertNull(withdrawn.lastLinkSentAt());
        assertEquals("systeem", withdrawn.disabledBy());
        assertNotNull(withdrawn.disabledAt());
        assertNull(account.passwordHash);
        assertEquals(0, CustomerSessionEntity.count("accountId", id));
        assertEquals(0, CustomerAccountTokenEntity.count("accountId", id));
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(session)));
        assertThrows(CustomerUnauthorizedException.class, () -> accounts.login(email, PASSWORD));
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(customer.id()), "Websitelogin ingetrokken (" + email + ")"));
        assertEquals(CustomerAccountService.DISABLED, accounts.findByEmail(" " + email.toUpperCase()).orElseThrow().status(),
                "a withdrawn login is still found by its address");

        var regiven = accounts.grant(customer.id(), email, "An", Language.NL);
        assertEquals(CustomerAccountMailer.Kind.FIRST, regiven.kind());
        assertEquals(id, regiven.accountId(), "the same row is given again");
        assertEquals(CustomerAccountService.INVITED, account.status);
        assertNull(account.disabledAt);
        assertNull(account.disabledBy);
        assertNull(account.lastLinkSentAt, "so the mail after giving again is a first mail");
        assertThrows(CustomerUnauthorizedException.class, () -> accounts.login(email, PASSWORD),
                "the old password does not come back");

        accounts.withdraw(id);
        var relinked = accounts.reissue(id);
        assertEquals(CustomerAccountMailer.Kind.FIRST, relinked.kind());
        assertEquals(CustomerAccountService.INVITED, account.status);

        accounts.withdraw(id);
        BusinessRuleException twice = assertThrows(BusinessRuleException.class, () -> accounts.withdraw(id));
        assertEquals("Deze login is al ingetrokken", twice.getMessage());
    }

    @Test
    void aWithdrawnLoginOnAnotherCustomerIsMovedWithALineOnBothCustomers() {
        Customer old = customer("Oude Klant BV");
        Customer chosen = customer("Nieuwe Klant NV");
        String email = email();
        activeLogin(old, email);
        long id = accountId(email);
        accounts.withdraw(id);

        var moved = accounts.grant(chosen.id(), email, "Bram Nieuw", Language.DE);

        assertEquals(id, moved.accountId());
        assertEquals(CustomerAccountMailer.Kind.FIRST, moved.kind());
        CustomerAccountEntity account = CustomerAccountEntity.findById(id);
        assertEquals(chosen.id(), account.customerId);
        assertEquals("Bram Nieuw", account.contactName);
        assertEquals("DE", account.language);
        assertEquals(CustomerAccountService.INVITED, account.status);
        assertNull(account.disabledAt);
        assertNull(account.lastLinkSentAt);
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(chosen.id()),
                "Websitelogin " + email + " verplaatst van klant Oude Klant BV"));
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(old.id()),
                "Websitelogin " + email + " verplaatst naar klant Nieuwe Klant NV"));
        assertEquals(List.of(), accounts.forCustomer(old.id()));
        assertEquals("Nieuwe Klant NV", accounts.forCustomer(chosen.id()).getFirst().customerCompany());
    }

    @Test
    void anInvitedLoginOnAnotherCustomerBlocksAGrant() {
        Customer holder = customer("Bloemen Peeters BV");
        Customer other = customer("Andere Klant NV");
        String email = email();
        accounts.grant(holder.id(), email, null, Language.NL);

        BusinessRuleException blocked = assertThrows(BusinessRuleException.class,
                () -> accounts.grant(other.id(), email, null, Language.NL));

        assertEquals("Dit e-mailadres heeft al een login bij klant Bloemen Peeters BV. Trek die login eerst in.",
                blocked.getMessage());
    }

    @Test
    void anActiveLoginOnAnotherCustomerBlocksAGrant() {
        Customer holder = customer("Bloemen Peeters BV");
        Customer other = customer("Andere Klant NV");
        String email = email();
        activeLogin(holder, email);

        BusinessRuleException blocked = assertThrows(BusinessRuleException.class,
                () -> accounts.grant(other.id(), email, null, Language.NL));

        assertEquals("Dit e-mailadres heeft al een login bij klant Bloemen Peeters BV. Trek die login eerst in.",
                blocked.getMessage());
    }

    @Test
    void staffGivesALoginOnTheCustomersOwnAddressAndSendsNewLinksWithALogbookLine() {
        String email = email();
        Customer customer = customers.create(new Customer(null, "Bloemen Peeters BV", "An Peeters",
                " " + email.toUpperCase(), null, "BE0123456789", "BE", Language.FR, null, null, null,
                null, null, null, null));

        var grant = accounts.grantByStaff(customer.id(), null);

        CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
        assertEquals(email, account.email);
        assertEquals("An Peeters", account.contactName);
        assertEquals("FR", account.language, "the customer's language");
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(customer.id()), "Websitelogin gegeven aan " + email));

        accounts.reissueByStaff(account.id);
        assertEquals(1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                "CUSTOMER", String.valueOf(customer.id()), "Nieuwe loginlink verstuurd naar " + email));

        String second = email();
        assertEquals(second, ((CustomerAccountEntity) CustomerAccountEntity.findById(
                accounts.grantByStaff(customer.id(), second).accountId())).email);
        assertEquals(2, accounts.forCustomer(customer.id()).size(), "several logins per customer are allowed");
    }

    @Test
    void staffCannotGiveALoginWithoutAUsableAddress() {
        Customer without = customers.create(new Customer(null, "Zonder Mail BV", "An", null, null,
                "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));

        assertEquals("Vul een geldig e-mailadres in", assertThrows(BusinessRuleException.class,
                () -> accounts.grantByStaff(without.id(), "geen adres")).getMessage());
        assertEquals("Deze klant heeft geen e-mailadres", assertThrows(BusinessRuleException.class,
                () -> accounts.grantByStaff(without.id(), " ")).getMessage());
    }

    @Test
    void everyFailedLoginIsTheSameRefusal() {
        Customer customer = customer("Bloemen Peeters BV");
        String active = email();
        activeLogin(customer, active);
        String invited = email();
        accounts.grant(customer.id(), invited, null, Language.NL);

        for (String address : new String[]{email(), invited, null}) {
            CustomerUnauthorizedException refused = assertThrows(CustomerUnauthorizedException.class,
                    () -> accounts.login(address, PASSWORD));
            assertEquals("INVALID_CREDENTIALS", refused.code());
            assertEquals("E-mail address or password is incorrect", refused.getMessage());
        }
        CustomerUnauthorizedException wrong = assertThrows(CustomerUnauthorizedException.class,
                () -> accounts.login(active, "not-the-password"));
        assertEquals("INVALID_CREDENTIALS", wrong.code());
        assertNull(((CustomerAccountEntity) CustomerAccountEntity.findById(accountId(active))).lastLoginAt,
                "a refused login leaves no trace on the login");
    }

    @Test
    void aSixthLoginEndsTheOldestSession() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        String oldest = activeLogin(customer, email);
        long id = accountId(email);

        String newest = null;
        for (int login = 1; login <= 5; login++) {
            at(START.plusSeconds(login * 60L));
            newest = accounts.login(email, PASSWORD).sessionToken();
        }

        assertEquals(CustomerAccountService.MAX_SESSIONS, CustomerSessionEntity.count("accountId", id));
        assertEquals(0, CustomerSessionEntity.count("tokenHash", AccountTokens.hash(oldest)));
        assertNotNull(guard.require(bearer(newest)));
        assertEquals(START.plusSeconds(300),
                ((CustomerAccountEntity) CustomerAccountEntity.findById(id)).lastLoginAt);
    }

    @Test
    void aSessionEndsAfterFourteenIdleDaysAndAfterThirtyDaysInAll() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        String token = activeLogin(customer, email);
        CustomerSessionEntity row = CustomerSessionEntity.find("tokenHash", AccountTokens.hash(token)).firstResult();
        assertEquals(START.plus(Duration.ofDays(30)), row.expiresAt);

        at(START.plus(Duration.ofMinutes(15)));
        guard.require(bearer(token));
        assertEquals(START, row.lastSeenAt, "last seen is written at most once per fifteen minutes");
        at(START.plus(Duration.ofMinutes(15)).plusSeconds(1));
        guard.require(bearer(token));
        assertEquals(START.plus(Duration.ofMinutes(15)).plusSeconds(1), row.lastSeenAt);

        at(START.plus(Duration.ofDays(13)));
        guard.require(bearer(token));
        at(START.plus(Duration.ofDays(26)));
        guard.require(bearer(token));
        at(START.plus(Duration.ofDays(30)).minusSeconds(1));
        AccountDtos.SessionInfo info = accounts.sessionInfo(guard.require(bearer(token)));
        assertEquals(START.plus(Duration.ofDays(30)), info.expiresAt());
        assertEquals(email, info.profile().email());
        at(START.plus(Duration.ofDays(30)));
        assertEquals("SESSION_INVALID", assertThrows(CustomerUnauthorizedException.class,
                () -> guard.require(bearer(token))).code(), "thirty days in all, however busy");

        at(START);
        String idle = accounts.login(email, PASSWORD).sessionToken();
        at(START.plus(Duration.ofDays(14)).minusSeconds(1));
        assertEquals(2, accounts.view(accountId(email)).activeSessions());
        at(START.plus(Duration.ofDays(14)));
        assertEquals(1, accounts.view(accountId(email)).activeSessions(), "staff count only sessions that still work");
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(idle)),
                "fourteen days without a call");
    }

    @Test
    void theGuardRefusesEverythingThatIsNotALiveCustomerBearer() {
        Customer customer = customer("Bloemen Peeters BV");
        String token = activeLogin(customer, email());

        assertEquals(customer.id(), guard.require(bearer(token)).customerId());
        for (String header : new String[]{null, "", token, "bearer " + token, "Bearer " + token + " ",
                "Bearer " + token + "\n", "Basic " + token, "Bearer ecs1_" + "A".repeat(43),
                "Bearer eci1_" + token.substring(5)}) {
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.getHeader("Authorization")).thenReturn(header);
            CustomerUnauthorizedException refused = assertThrows(CustomerUnauthorizedException.class,
                    () -> guard.require(request), String.valueOf(header));
            assertEquals("SESSION_INVALID", refused.code());
            assertEquals("The session is no longer valid", refused.getMessage());
        }
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(null));

        accounts.endSession(token);
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(token)));
        assertDoesNotThrow(() -> accounts.endSession(token), "logging out twice is not an error");
    }

    @Test
    void deletingTheCustomerTakesLoginsSessionsLinksAndRequestsAlong() {
        Customer leaving = customer("Vertrekkende Klant BV");
        Customer staying = customer("Blijvende Klant NV");
        String email = email();
        String session = activeLogin(leaving, email);
        long leavingAccount = accountId(email);
        accounts.reissue(leavingAccount);
        var kept = accounts.grant(staying.id(), email(), null, Language.NL);
        long leavingRequest = request(leaving.id(), email).id;
        long stayingRequest = request(staying.id(), email()).id;

        customers.delete(leaving.id());

        assertNull(CustomerAccountEntity.findById(leavingAccount));
        assertEquals(0, CustomerSessionEntity.count("accountId", leavingAccount));
        assertEquals(0, CustomerAccountTokenEntity.count("accountId", leavingAccount));
        assertEquals(0, CustomerLoginRequestEntity.count("id", leavingRequest));
        assertThrows(CustomerUnauthorizedException.class, () -> guard.require(bearer(session)));
        assertNotNull(CustomerAccountEntity.findById(kept.accountId()));
        assertEquals(1, CustomerAccountTokenEntity.count("accountId", kept.accountId()));
        assertEquals(1, CustomerLoginRequestEntity.count("id", stayingRequest));
        assertTrue(accounts.findByEmail(email).isEmpty());
    }

    @Test
    void withAllFourHashingPermitsTakenALoginIsRefusedAsTemporarilyUnavailable() {
        assertEquals(4, CustomerAccountService.BCRYPT.availablePermits());
        CustomerAccountService.BCRYPT.acquireUninterruptibly(4);
        try {
            assertThrows(PublicFormServiceUnavailableException.class,
                    () -> accounts.login("nobody@example.com", PASSWORD));
        } finally {
            CustomerAccountService.BCRYPT.release(4);
        }
        assertThrows(CustomerUnauthorizedException.class,
                () -> accounts.login("nobody@example.com", PASSWORD), "the permits came back");
    }

    // ------------------------------------------------------------------ fixtures

    private void at(Instant moment) {
        accounts.useClock(Clock.fixed(moment, ZoneOffset.UTC));
    }

    private Customer customer(String company) {
        return customers.create(new Customer(null, company, "Contact van " + company, email(),
                "+32 14 00 00 00", "BE0123456789", "BE", Language.NL, null, null, null, null, null,
                null, null));
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    /** An ACTIVE login with PASSWORD; returns the session token of the activation. */
    private String activeLogin(Customer customer, String email) {
        var grant = accounts.grant(customer.id(), email, "An Peeters", Language.NL);
        return accounts.activate(grant.rawToken(), PASSWORD).sessionToken();
    }

    private static long accountId(String email) {
        return ((CustomerAccountEntity) CustomerAccountEntity.find("email", email).firstResult()).id;
    }

    private static HttpServerRequest bearer(String token) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return request;
    }

    private static CustomerLoginRequestEntity request(long customerId, String email) {
        Instant now = Instant.now();
        CustomerLoginRequestEntity request = new CustomerLoginRequestEntity();
        request.reference = "LGN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        request.status = "PENDING";
        request.source = "QUOTE";
        request.language = "NL";
        request.companyName = "Aanvrager BV";
        request.contactName = "An";
        request.email = email;
        request.customerId = customerId;
        request.createdAt = now;
        request.updatedAt = now;
        request.persist();
        return request;
    }

    private void assertPasswordRefused(String code, String token, String password) {
        PublicFormValidationException refused = assertThrows(PublicFormValidationException.class,
                () -> accounts.activate(token, password));
        assertEquals(code, refused.fieldErrors().get("password"));
        assertNotNull(accounts.inspect(token), "the link is still usable after " + code);
    }

    private static void assertInvalidToken(org.junit.jupiter.api.function.Executable call) {
        PublicFormValidationException refused = assertThrows(PublicFormValidationException.class, call);
        assertEquals("INVALID", refused.fieldErrors().get("token"));
    }
}
