package be.enrosed.account;

import be.enrosed.account.LoginRequestWriter.Candidate;
import be.enrosed.account.LoginRequestWriter.Stored;
import be.enrosed.contact.ContactInquiryService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** First wins, every later version kept: what the three routes may and may not store. */
@QuarkusTest
@TestTransaction
class LoginRequestPersistenceTest {
    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");
    private static final String REFERENCE = "^LGN-[0-9A-F]{20}$";

    @Inject LoginRequestWriter writer;
    @Inject LoginRequestService requests;
    @Inject CustomerAccountService accounts;
    @Inject AccountHousekeeping housekeeping;
    @Inject CustomerService customers;

    @AfterEach
    void realClock() {
        writer.useClock(Clock.systemUTC());
        housekeeping.useClock(Clock.systemUTC());
    }

    @Test
    void theFirstRequestIsStoredAsTypedAndAnIdenticalRepeatOnlyCounts() {
        at(START);
        String email = email();

        Stored first = writer.storeJoined(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "FR",
                "  Bloemen Peeters BV ", "BE", "BE 0123.456.789", "An Peeters", "+32 14 00 00 00",
                "Graag een login.", null, null, null));

        assertTrue(first.reference().matches(REFERENCE), first.reference());
        assertTrue(first.notifyStaff());
        assertFalse(first.changed());
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(first.requestId());
        assertEquals(first.reference(), row.reference);
        assertEquals("PENDING", row.status);
        assertEquals("ORDER_SCREEN", row.source);
        assertEquals("FR", row.language);
        assertEquals("Bloemen Peeters BV", row.companyName);
        assertEquals("BE", row.companyCountryCode);
        assertEquals("BE 0123.456.789", row.vatNumber);
        assertEquals("An Peeters", row.contactName);
        assertEquals(email, row.email);
        assertEquals("+32 14 00 00 00", row.phone);
        assertEquals("Graag een login.", row.message);
        assertNull(row.customerId);
        assertNull(row.accountId);
        assertEquals(0, row.repeatCount);
        assertNull(row.laterSubmissions);
        assertEquals(START, row.privacyAcceptedAt);
        assertEquals(ContactInquiryService.CURRENT_PRIVACY_VERSION, row.privacyPolicyVersion);
        assertEquals(START, row.createdAt);
        assertEquals(START, row.updatedAt);

        at(START.plusSeconds(60));
        /* Same company, VAT number and contact: capitals, spaces and VAT punctuation do not count. */
        Stored again = writer.storeJoined(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL",
                "bloemen peeters bv", "NL", "be0123456789", " AN PEETERS ", "other phone",
                "Another message", null, null, null));

        assertEquals(first.reference(), again.reference());
        assertEquals(first.requestId(), again.requestId());
        assertFalse(again.notifyStaff());
        assertEquals(1, row.repeatCount);
        assertEquals(START.plusSeconds(60), row.updatedAt);
        assertEquals(START, row.createdAt);
        assertEquals("FR", row.language);
        assertEquals("Bloemen Peeters BV", row.companyName);
        assertEquals("+32 14 00 00 00", row.phone);
        assertEquals("Graag een login.", row.message);
        assertNull(row.laterSubmissions);
        assertEquals(1, CustomerLoginRequestEntity.count("email", email));
    }

    @Test
    void everyLaterVersionWithOtherDetailsIsListedBesideTheFirstAndNothingIsReplaced() {
        at(START);
        String email = email();
        /* The squatting sequence: junk first, then the genuine applicant, then junk again. */
        Stored junk = writer.storeJoined(form(email, "Junk Ltd", "XX000", "Nobody"));

        at(START.plusSeconds(60));
        Stored genuine = writer.storeJoined(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", "+32 14 00 00 00",
                "Wij zijn de echte aanvrager.", null, null, null));

        assertEquals(junk.reference(), genuine.reference());
        assertEquals(junk.requestId(), genuine.requestId());
        assertTrue(genuine.notifyStaff());
        assertTrue(genuine.changed());
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(junk.requestId());
        assertEquals("Junk Ltd", row.companyName);
        assertEquals("XX000", row.vatNumber);
        assertEquals("Nobody", row.contactName);
        assertEquals(1, row.repeatCount);
        List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
        assertEquals(1, later.size());
        LoginRequestDtos.LaterSubmission kept = later.getFirst();
        assertEquals(START.plusSeconds(60), kept.at());
        assertEquals("ORDER_SCREEN", kept.source());
        assertEquals("Bloemen Peeters BV", kept.companyName());
        assertEquals("BE", kept.companyCountryCode());
        assertEquals("BE0123456789", kept.vatNumber());
        assertEquals("An Peeters", kept.contactName());
        assertEquals("+32 14 00 00 00", kept.phone());
        assertEquals("Wij zijn de echte aanvrager.", kept.message());
        assertEquals("NL", kept.language());
        assertNull(kept.customerId());

        at(START.plusSeconds(120));
        Stored junkAgain = writer.storeJoined(form(email, "Junk Ltd", "XX000", "Somebody Else"));

        assertTrue(junkAgain.notifyStaff());
        assertTrue(junkAgain.changed());
        later = writer.later(row);
        assertEquals(2, later.size());
        assertEquals(kept, later.getFirst(), "the genuine version is still listed, unchanged");
        assertEquals("Somebody Else", later.get(1).contactName());
        assertEquals("Junk Ltd", row.companyName);
        assertEquals("Nobody", row.contactName);
        assertEquals(2, row.repeatCount);

        /* Another VAT number and another source are each a version of their own. */
        assertTrue(writer.storeJoined(form(email, "Junk Ltd", "XX111", "Nobody")).notifyStaff());
        assertTrue(writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email, "EN", "Junk Ltd",
                "BE", "XX000", "Nobody", null, null, null, null, null)).notifyStaff());
        assertEquals(4, writer.later(row).size());

        /* Equal to the first request or to a version already listed: counted, nothing added. */
        String before = row.laterSubmissions;
        Stored likeTheRow = writer.storeJoined(form(email, "junk ltd", "xx-000", "nobody"));
        Stored likeAnEntry = writer.storeJoined(form(email, "BLOEMEN PEETERS BV", "BE 0123.456.789",
                "an peeters"));
        assertFalse(likeTheRow.notifyStaff());
        assertFalse(likeAnEntry.notifyStaff());
        assertEquals(before, row.laterSubmissions);
        assertEquals(6, row.repeatCount);
        assertEquals(1, CustomerLoginRequestEntity.count("email", email));
    }

    @Test
    void theSixthDifferingVersionIsCountedButNotKept() {
        at(START);
        String email = email();
        Stored first = writer.storeJoined(form(email, "Company 0", "BE0123456789", "An Peeters"));
        for (int version = 1; version <= 5; version++) {
            Stored stored = writer.storeJoined(form(email, "Company " + version, "BE0123456789", "An Peeters"));
            assertTrue(stored.notifyStaff(), "version " + version);
        }
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(first.requestId());
        String five = row.laterSubmissions;
        assertEquals(5, writer.later(row).size());

        Stored sixth = writer.storeJoined(form(email, "Company 6", "BE0123456789", "An Peeters"));

        assertFalse(sixth.notifyStaff());
        assertEquals(first.reference(), sixth.reference());
        assertEquals(6, row.repeatCount);
        assertEquals(five, row.laterSubmissions, "the five kept versions are unchanged");
        LoginRequestDtos.LoginRequestView view = requests.detail(row.id).request();
        assertTrue(view.laterSubmissionsFull());
        assertEquals(5, view.laterSubmissions().size());
        assertEquals("Company 1", view.laterSubmissions().getFirst().companyName());
        assertEquals("Company 5", view.laterSubmissions().getLast().companyName());
        assertEquals(6, view.repeatCount());
    }

    @Test
    void aLongMessageIsCutInTheListAndFiveVersionsAtFullLengthFitTheColumn() {
        at(START);
        String email = email();
        Stored first = writer.storeJoined(form(email, "First", "BE0123456789", "An Peeters"));
        for (int version = 1; version <= 5; version++) {
            Stored stored = writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email, "EL",
                    version + "é".repeat(159), "BE", "9".repeat(32), "ü".repeat(120), "0".repeat(50),
                    "m".repeat(1_000), 1_000_000_000L + version, 2_000_000_000L + version,
                    "N".repeat(40)));
            assertTrue(stored.notifyStaff(), "version " + version);
        }

        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(first.requestId());
        List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
        assertEquals(5, later.size());
        for (LoginRequestDtos.LaterSubmission entry : later) {
            assertEquals(300, entry.message().length());
            assertEquals(160, entry.companyName().length());
            assertEquals(120, entry.contactName().length());
        }
        assertTrue(row.laterSubmissions.length() <= 7_900, "length " + row.laterSubmissions.length());
    }

    @Test
    void aQuoteOnAnOpenRequestKeepsTheRequestAndListsItsOwnCustomerAndOrder() {
        at(START);
        String email = email();
        Stored first = writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email, "NL",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", null, null,
                11L, 21L, "ENR-2026-0021"));
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(first.requestId());
        assertEquals(11L, row.customerId);
        assertEquals(21L, row.salesOrderId);
        assertEquals("ENR-2026-0021", row.salesOrderNumber);
        assertNotNull(row.privacyAcceptedAt);

        /* Every website quote makes a customer row of its own: same details, another link. */
        Stored second = writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email, "NL",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", null, null,
                12L, 22L, "ENR-2026-0022"));

        assertTrue(second.notifyStaff());
        assertEquals(11L, row.customerId);
        assertEquals(21L, row.salesOrderId);
        assertEquals("ENR-2026-0021", row.salesOrderNumber);
        LoginRequestDtos.LaterSubmission entry = writer.later(row).getFirst();
        assertEquals("QUOTE", entry.source());
        assertEquals(12L, entry.customerId());
        assertEquals(22L, entry.salesOrderId());
        assertEquals("ENR-2026-0022", entry.salesOrderNumber());

        /* The same quote once more, and a form request without any link: nothing new. */
        assertFalse(writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email, "NL",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", null, null,
                12L, 22L, "ENR-2026-0022")).notifyStaff());
        assertEquals(1, writer.later(row).size());
    }

    @Test
    void aNewLinkIsOnlyStoredForALoginThatCanBeUsed() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");

        Stored unknown = writer.storeJoined(Candidate.newLink(email()));
        assertNull(unknown.requestId());
        assertFalse(unknown.notifyStaff());
        assertTrue(unknown.reference().matches(REFERENCE));

        String invited = email();
        long invitedAccount = accounts.grant(customer.id(), invited, "An Peeters", Language.FR).accountId();
        Stored forInvited = writer.storeJoined(Candidate.newLink(invited));
        assertTrue(forInvited.notifyStaff());
        assertFalse(forInvited.changed());
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(forInvited.requestId());
        assertEquals("NEW_LINK", row.source);
        assertEquals("PENDING", row.status);
        assertEquals(customer.id(), row.customerId);
        assertEquals(invitedAccount, row.accountId);
        assertEquals("Bloemen Peeters BV", row.companyName);
        assertEquals("An Peeters", row.contactName);
        assertEquals("FR", row.language);
        assertNull(row.privacyAcceptedAt);
        assertNull(row.privacyPolicyVersion);

        /* Asking again while that request is open adds nothing. */
        Stored repeated = writer.storeJoined(Candidate.newLink(invited));
        assertFalse(repeated.notifyStaff());
        assertEquals(forInvited.reference(), repeated.reference());
        assertEquals(1, row.repeatCount);
        assertNull(row.laterSubmissions);

        String active = email();
        activeLogin(customer, active);
        assertTrue(writer.storeJoined(Candidate.newLink(active)).notifyStaff());

        String withdrawn = email();
        accounts.withdraw(accounts.grant(customer.id(), withdrawn, null, Language.NL).accountId());
        Stored forWithdrawn = writer.storeJoined(Candidate.newLink(withdrawn));
        assertNull(forWithdrawn.requestId());
        assertEquals(0, CustomerLoginRequestEntity.count("email", withdrawn));
    }

    @Test
    void aNewLinkOnAnOpenRequestIsMarkedOnceAlsoAfterARejection() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        String email = email();
        activeLogin(customer, email);
        rejected(email, START.minus(Duration.ofDays(3)));
        /* Someone parks a request on the address of a customer who has a login. */
        Stored parked = writer.storeJoined(form(email, "Junk Ltd", "XX000", "Nobody"));
        assertFalse(parked.notifyStaff(), "rejected three days ago: stored, but silent");
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(parked.requestId());

        at(START.plusSeconds(60));
        Stored marked = writer.storeJoined(Candidate.newLink(email));

        assertTrue(marked.notifyStaff(), "the holder of the login is never silenced by that rejection");
        assertTrue(marked.changed());
        assertEquals(parked.reference(), marked.reference());
        List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
        assertEquals(1, later.size());
        assertEquals("NEW_LINK", later.getFirst().source());
        assertEquals(START.plusSeconds(60), later.getFirst().at());
        assertNull(later.getFirst().companyName());
        assertTrue(row.laterSubmissions.length() < 100, row.laterSubmissions);
        assertEquals("ORDER_SCREEN", row.source);
        assertEquals("Junk Ltd", row.companyName);

        Stored second = writer.storeJoined(Candidate.newLink(email));
        assertFalse(second.notifyStaff());
        assertEquals(1, writer.later(row).size());
        assertEquals(2, row.repeatCount);

        /* The marker does not take one of the five places of the applicant versions. */
        for (int version = 1; version <= 5; version++) {
            writer.storeJoined(form(email, "Company " + version, "XX000", "Nobody"));
        }
        assertEquals(6, writer.later(row).size());
        assertEquals("NEW_LINK", writer.later(row).getFirst().source());
    }

    @Test
    void noNewLinkWithoutALoginOrInsideTheCoolDownOfADay() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");

        /* An open request for an address without a login: no marker. */
        String stranger = email();
        Stored open = writer.storeJoined(form(stranger, "Junk Ltd", "XX000", "Nobody"));
        assertFalse(writer.storeJoined(Candidate.newLink(stranger)).notifyStaff());
        CustomerLoginRequestEntity openRow = CustomerLoginRequestEntity.findById(open.requestId());
        assertNull(openRow.laterSubmissions);

        /* A link was mailed less than a day ago: no request, and no marker on an open one. */
        String mailed = email();
        long mailedAccount = accounts.grant(customer.id(), mailed, null, Language.NL).accountId();
        CustomerAccountEntity mailedLogin = CustomerAccountEntity.findById(mailedAccount);
        mailedLogin.lastLinkSentAt = START.minus(Duration.ofHours(23));
        assertNull(writer.storeJoined(Candidate.newLink(mailed)).requestId());
        Stored parked = writer.storeJoined(form(mailed, "Junk Ltd", "XX000", "Nobody"));
        assertFalse(writer.storeJoined(Candidate.newLink(mailed)).notifyStaff());
        CustomerLoginRequestEntity parkedRow = CustomerLoginRequestEntity.findById(parked.requestId());
        assertNull(parkedRow.laterSubmissions);
        mailedLogin.lastLinkSentAt = START.minus(Duration.ofHours(25));
        assertTrue(writer.storeJoined(Candidate.newLink(mailed)).notifyStaff());
        assertEquals(1, writer.later(parkedRow).size());

        /* A new-link request for this login was decided less than a day ago. */
        String decided = email();
        long decidedAccount = accounts.grant(customer.id(), decided, null, Language.NL).accountId();
        CustomerLoginRequestEntity earlier = CustomerLoginRequestEntity.findById(
                writer.storeJoined(Candidate.newLink(decided)).requestId());
        earlier.status = "REJECTED";
        earlier.decidedAt = START.minus(Duration.ofHours(23));
        CustomerLoginRequestEntity.flush();
        assertNull(writer.storeJoined(Candidate.newLink(decided)).requestId());
        earlier.decidedAt = START.minus(Duration.ofHours(25));
        CustomerLoginRequestEntity.flush();
        Stored allowed = writer.storeJoined(Candidate.newLink(decided));
        assertNotNull(allowed.requestId());
        assertNotEquals(earlier.id, allowed.requestId());
        CustomerLoginRequestEntity allowedRow = CustomerLoginRequestEntity.findById(allowed.requestId());
        assertEquals(decidedAccount, allowedRow.accountId);
    }

    @Test
    void anAddressRejectedLessThanThirtyDaysAgoIsStoredButRingsNobody() {
        at(START);
        String recent = email();
        rejected(recent, START.minus(Duration.ofDays(29)));

        Stored stored = writer.storeJoined(form(recent, "Bloemen Peeters BV", "BE0123456789", "An Peeters"));
        assertNotNull(stored.requestId());
        assertFalse(stored.notifyStaff());
        Stored appended = writer.storeJoined(form(recent, "Another Company", "BE0123456789", "An Peeters"));
        assertFalse(appended.notifyStaff());
        assertTrue(appended.changed());
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(stored.requestId());
        assertEquals(1, writer.later(row).size());
        assertTrue(requests.detail(row.id).request().previouslyRejected());

        String longAgo = email();
        rejected(longAgo, START.minus(Duration.ofDays(31)));
        assertTrue(writer.storeJoined(form(longAgo, "Bloemen Peeters BV", "BE0123456789", "An Peeters"))
                .notifyStaff());
    }

    @Test
    void aFullListDropsOnlyNewRequestsOfItsOwnRoute() {
        at(START);
        Customer customer = customer("Bloemen Peeters BV");
        String holder = email();
        accounts.grant(customer.id(), holder, null, Language.NL);
        String secondHolder = email();
        accounts.grant(customer.id(), secondHolder, null, Language.NL);
        String waiting = email();
        writer.storeJoined(form(waiting, "Waiting BV", "BE0123456789", "An Peeters"));
        fill(LoginRequestWriter.ORDER_SCREEN);
        assertEquals(List.of("ORDER_SCREEN"), requests.summary().intakeFullSources());

        String turnedAway = email();
        Stored dropped = writer.storeJoined(form(turnedAway, "Late BV", "BE0123456789", "An Peeters"));
        assertNull(dropped.requestId());
        assertFalse(dropped.notifyStaff());
        assertTrue(dropped.reference().matches(REFERENCE), dropped.reference());
        assertEquals(0, CustomerLoginRequestEntity.count("email", turnedAway));
        /* A repeat for a request that is already in the list is not a new request. */
        assertNotNull(writer.storeJoined(form(waiting, "Other BV", "BE0123456789", "An Peeters")).requestId());
        assertNotNull(writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email(), "NL",
                "Quote BV", "BE", "BE0123456789", "An Peeters", null, null, 1L, 2L, "ENR-1")).requestId());
        assertNotNull(writer.storeJoined(Candidate.newLink(holder)).requestId());

        fill(LoginRequestWriter.QUOTE);
        LoginRequestDtos.Summary summary = requests.summary();
        assertTrue(summary.intakeFull());
        assertEquals(List.of("ORDER_SCREEN", "QUOTE"), summary.intakeFullSources());
        assertNull(writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email(), "NL",
                "Quote BV", "BE", "BE0123456789", "An Peeters", null, null, 3L, 4L, "ENR-2")).requestId());
        assertNotNull(writer.storeJoined(Candidate.newLink(secondHolder)).requestId(),
                "a customer who asks for a new link is never turned away by a full list");
    }

    @Test
    void aFullQuoteListLeavesTheLoginFormOpen() {
        at(START);
        fill(LoginRequestWriter.QUOTE);

        assertNull(writer.storeJoined(new Candidate(LoginRequestWriter.QUOTE, email(), "NL",
                "Quote BV", "BE", "BE0123456789", "An Peeters", null, null, 1L, 2L, "ENR-1")).requestId());
        assertNotNull(writer.storeJoined(form(email(), "Form BV", "BE0123456789", "An Peeters")).requestId());
        assertTrue(requests.summary().intakeFullSources().contains("QUOTE"));
        assertFalse(requests.summary().intakeFullSources().contains("ORDER_SCREEN"));
    }

    @Test
    void housekeepingRemovesOldRequestsByTheirStatus() {
        Instant now = Instant.parse("2027-06-01T12:00:00Z");
        /* purge runs in a transaction of its own and only sees committed rows. */
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> List.of(
                aged("REJECTED", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(181))),
                aged("REJECTED", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(179))),
                aged("APPROVED", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(366))),
                aged("APPROVED", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(364))),
                aged("PENDING", now.minus(Duration.ofDays(181)), null),
                aged("PENDING", now.minus(Duration.ofDays(179)), null)));
        try {
            housekeeping.useClock(Clock.fixed(now, ZoneOffset.UTC));

            housekeeping.purge();

            List<Boolean> left = QuarkusTransaction.requiringNew().call(() -> ids.stream()
                    .map(id -> CustomerLoginRequestEntity.count("id", id) == 1).toList());
            assertEquals(List.of(false, true, false, true, false, true), left);
        } finally {
            QuarkusTransaction.requiringNew().run(() ->
                    ids.forEach(id -> CustomerLoginRequestEntity.delete("id", id)));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private void at(Instant moment) {
        writer.useClock(Clock.fixed(moment, ZoneOffset.UTC));
    }

    private static Candidate form(String email, String company, String vatNumber, String contact) {
        return new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", company, "BE", vatNumber,
                contact, null, null, null, null, null);
    }

    private Customer customer(String company) {
        return customers.create(new Customer(null, company, "Contact " + company, email(), null,
                "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));
    }

    /** ACTIVE without paying for bcrypt: only the status matters to the writer. */
    private void activeLogin(Customer customer, String email) {
        CustomerAccountEntity account = CustomerAccountEntity.findById(
                accounts.grant(customer.id(), email, "An Peeters", Language.NL).accountId());
        account.status = CustomerAccountService.ACTIVE;
        account.passwordHash = "not-a-real-hash";
        CustomerAccountEntity.flush();
    }

    private static void rejected(String email, Instant decidedAt) {
        CustomerLoginRequestEntity row = row("REJECTED", LoginRequestWriter.ORDER_SCREEN, email,
                decidedAt.minusSeconds(3_600));
        row.decidedAt = decidedAt;
        row.decidedBy = "emre";
        row.persist();
    }

    /** Brings the open requests of one route to the ceiling. */
    private static void fill(String source) {
        long open = CustomerLoginRequestEntity.count("status = ?1 and source = ?2", "PENDING", source);
        for (long count = open; count < LoginRequestWriter.INTAKE_CEILING; count++) {
            row("PENDING", source, email(), START).persist();
        }
        CustomerLoginRequestEntity.flush();
    }

    private static Long aged(String status, Instant createdAt, Instant decidedAt) {
        CustomerLoginRequestEntity row = row(status, LoginRequestWriter.ORDER_SCREEN, email(), createdAt);
        row.decidedAt = decidedAt;
        row.persist();
        return row.id;
    }

    private static CustomerLoginRequestEntity row(String status, String source, String email,
                                                  Instant createdAt) {
        CustomerLoginRequestEntity row = new CustomerLoginRequestEntity();
        row.reference = LoginRequestWriter.newReference();
        row.status = status;
        row.source = source;
        row.language = "NL";
        row.companyName = "Bloemen Peeters BV";
        row.contactName = "An Peeters";
        row.email = email;
        row.createdAt = createdAt;
        row.updatedAt = createdAt;
        return row;
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
