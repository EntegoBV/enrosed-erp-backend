package be.enrosed.account;

import be.enrosed.account.LoginRequestWriter.Candidate;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateBucketEntity;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.WebsiteQuoteLoginRequested;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.InternalMessageSender;
import be.enrosed.shared.mail.InternalMessageSender.TeamFact;
import be.enrosed.shared.mail.InternalMessageSender.TeamNotice;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the team hears about a login request, and that it hears it off the request thread.
 * The push is stubbed except in the one case that proves the device lookup works on a
 * thread without request context; the cap on notices uses the real buckets.
 */
@QuarkusTest
class LoginRequestNotifierTest {
    @Inject LoginRequestNotifier notifier;
    @Inject LoginRequestService requests;
    @Inject LoginRequestWriter writer;
    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;
    @InjectMock InternalMessageSender sender;
    @InjectSpy WebPushNotifier push;
    @InjectSpy AccountHousekeeping housekeeping;
    @InjectSpy PublicFormRateLimiter rateLimiter;

    private final List<Runnable> captured = new ArrayList<>();
    private final List<Long> createdCustomers = new ArrayList<>();

    @BeforeEach
    void startQuiet() {
        doNothing().when(push).notifyAll(any(), any(), any(), any());
        notifier.useExecutor(Runnable::run);
        clearRequestsAndNoticeBuckets();
    }

    @AfterEach
    void cleanUp() {
        notifier.useExecutor(ForkJoinPool.commonPool());
        clearRequestsAndNoticeBuckets();
        createdCustomers.forEach(customers::delete);
        createdCustomers.clear();
    }

    @Test
    void theObserverOnlyEnqueuesAndTheTaskSendsOnePushAndOneTeamMail() {
        notifier.useExecutor(captured::add);
        String email = email();
        long id = stored(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "FR",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", "+32 14 00 00 00",
                "Graag een login.", null, null, null));

        notifier.onReceived(new LoginRequestReceived(id, false));

        assertEquals(1, captured.size());
        verifyNoInteractions(sender);
        verify(push, never()).notifyAll(any(), any(), any(), any());
        verify(housekeeping, never()).purge();
        verify(rateLimiter, never()).checkKey(any(), any(), any(), anyInt());

        captured.getFirst().run();

        verify(push).notifyAll("login-request", "Nieuwe login-aanvraag",
                "Wacht op goedkeuring bij Login-aanvragen", "/klantlogins");
        TeamNotice notice = onlyNotice();
        String reference = reference(id);
        assertEquals("Nieuwe login-aanvraag " + reference + " · Bloemen Peeters BV", notice.subject());
        assertEquals("Website · login-aanvraag", notice.kicker());
        assertEquals("Bloemen Peeters BV vraagt een login", notice.title());
        assertEquals("Keur de aanvraag goed of wijs ze af in het ERP. Zonder jullie goedkeuring krijgt"
                + " niemand toegang.", notice.intro());
        assertEquals(List.of(
                new TeamFact("Bedrijf", "Bloemen Peeters BV"),
                new TeamFact("Contact", "An Peeters"),
                new TeamFact("E-mail", email),
                new TeamFact("Telefoon", "+32 14 00 00 00"),
                new TeamFact("BTW-nummer", "BE0123456789"),
                new TeamFact("Land", "BE"),
                new TeamFact("Taal", "FR"),
                new TeamFact("Bron", "Loginformulier")), notice.facts());
        assertTrue(notice.lines().isEmpty());
        assertEquals("Bericht van de aanvrager", notice.messageTitle());
        assertEquals("Graag een login.", notice.message());
        assertEquals("Open in het ERP", notice.buttonLabel());
        assertTrue(notice.buttonUrl().endsWith("/klantlogins?open=" + id), notice.buttonUrl());
        assertNull(notice.secondaryLabel());
        assertNull(notice.secondaryUrl());
        assertTrue(notice.textFallback().startsWith(notice.subject() + "\nBedrijf: Bloemen Peeters BV\n"));
        assertTrue(notice.textFallback().contains("E-mail: " + email + "\n"));
        assertTrue(notice.textFallback().contains("Bericht van de aanvrager:\nGraag een login.\n"));
        assertTrue(notice.textFallback().endsWith(notice.buttonUrl()));
        verify(rateLimiter).checkKey(PublicFormAction.ACCOUNT_NOTICE_HOUR, "GLOBAL", "staff", 6);
        verify(rateLimiter).checkKey(PublicFormAction.ACCOUNT_NOTICE_DAY, "GLOBAL", "staff", 20);
        verify(housekeeping, times(1)).purge();
    }

    @Test
    void aRequestThatCameWithAQuoteRingsNobodyAndAVanishedOneDoesNothing() {
        long id = stored(new Candidate(LoginRequestWriter.QUOTE, email(), "NL", "Bloemen Peeters BV",
                "BE", "BE0123456789", "An Peeters", null, null, 11L, 21L, "ENR-2026-0021"));

        notifier.process(id, false);

        verifyNoInteractions(sender);
        verify(push, never()).notifyAll(any(), any(), any(), any());
        verify(rateLimiter, never()).checkKey(any(), any(), any(), anyInt());
        verify(housekeeping, times(1)).purge();

        notifier.process(987_654_321L, false);
        verifyNoInteractions(sender);
        verify(housekeeping, times(1)).purge();
    }

    @Test
    void aNewLinkRequestHasItsOwnTexts() {
        String email = email();
        long customerId = customer("Bloemen Peeters BV");
        accounts.grant(customerId, email, "An Peeters", Language.DE);
        long id = stored(Candidate.newLink(email));

        notifier.process(id, false);

        verify(push).notifyAll("login-request", "Nieuwe loginlink gevraagd",
                "Stuur de link vanuit Login-aanvragen", "/klantlogins");
        TeamNotice notice = onlyNotice();
        assertEquals("Nieuwe loginlink gevraagd · Bloemen Peeters BV", notice.subject());
        assertEquals("Website · wachtwoord vergeten", notice.kicker());
        assertEquals("Bloemen Peeters BV vraagt een nieuwe loginlink", notice.title());
        assertEquals("Deze klant heeft al een login. Stuur met één tik een nieuwe link vanuit"
                + " Login-aanvragen.", notice.intro());
        assertEquals(List.of(new TeamFact("Bedrijf", "Bloemen Peeters BV"),
                new TeamFact("E-mail", email), new TeamFact("Taal", "DE")), notice.facts());
        assertNull(notice.message());
        assertNull(notice.messageTitle());
        assertTrue(notice.buttonUrl().endsWith("/klantlogins?open=" + id));
        verify(housekeeping, times(1)).purge();
    }

    @Test
    void aChangedRequestIsAnnouncedWithTheFactsOfTheVersionThatWasJustListed() {
        String email = email();
        long customerId = customer("Klant Met Login NV");
        accounts.grant(customerId, email, "An Peeters", Language.NL);
        /* The open request is a new-link request: the texts follow the last entry, not the source. */
        long id = stored(Candidate.newLink(email));
        stored(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", "Eerste Versie BV", "BE",
                "BE0111111111", "Eerste Contact", null, "Een bericht", null, null, null));
        stored(new Candidate(LoginRequestWriter.QUOTE, email, "NL", "Tweede Versie BV", "NL",
                "NL222222222B01", "Tweede Contact", null, null, 11L, 21L, "ENR-2026-0021"));

        notifier.process(id, true);

        verify(push).notifyAll("login-request", "Login-aanvraag opnieuw ingediend",
                "Met andere gegevens. Bekijk ze bij Login-aanvragen", "/klantlogins");
        TeamNotice notice = onlyNotice();
        assertEquals("Login-aanvraag " + reference(id) + " opnieuw ingediend met andere gegevens",
                notice.subject());
        assertEquals("Website · login-aanvraag", notice.kicker());
        assertEquals(email + " vroeg opnieuw een login, met andere gegevens", notice.title());
        assertEquals("De eerste aanvraag staat nog open en is niet gewijzigd. Vergelijk ze in het ERP"
                + " voor je goedkeurt of afwijst.", notice.intro());
        assertEquals(List.of(
                new TeamFact("Bedrijf", "Tweede Versie BV"),
                new TeamFact("Contact", "Tweede Contact"),
                new TeamFact("BTW-nummer", "NL222222222B01"),
                new TeamFact("Land", "NL"),
                new TeamFact("Bron", "Offerteaanvraag ENR-2026-0021")), notice.facts());
        assertNull(notice.message());
        assertTrue(notice.buttonUrl().endsWith("/klantlogins?open=" + id));
        verify(housekeeping, times(1)).purge();
    }

    @Test
    void aFormVersionListedLastNamesTheLoginForm() {
        String email = email();
        long id = stored(new Candidate(LoginRequestWriter.QUOTE, email, "NL", "Eerste BV", "BE",
                "BE0111111111", "Eerste Contact", null, null, 11L, 21L, "ENR-2026-0021"));
        stored(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", "Tweede BV", "BE",
                "BE0111111111", "Eerste Contact", null, null, null, null, null));

        notifier.process(id, true);

        verify(push).notifyAll("login-request", "Login-aanvraag opnieuw ingediend",
                "Met andere gegevens. Bekijk ze bij Login-aanvragen", "/klantlogins");
        TeamNotice notice = onlyNotice();
        assertEquals(new TeamFact("Bedrijf", "Tweede BV"), notice.facts().getFirst());
        assertEquals(new TeamFact("Bron", "Loginformulier"), notice.facts().getLast());
    }

    @Test
    void aNewLinkAskedOnAnOpenRequestNamesTheAddressAndTheCustomerOfTheLogin() {
        String email = email();
        long customerId = customer("Klant Met Login NV");
        accounts.grant(customerId, email, "An Peeters", Language.NL);
        long id = stored(new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", "Junk Ltd", "BE",
                "XX000", "Nobody", null, null, null, null, null));
        LoginRequestWriter.Stored marker = writer.storeDetached(Candidate.newLink(email));
        assertTrue(marker.notifyStaff() && marker.changed());

        notifier.process(id, true);

        verify(push).notifyAll("login-request", "Nieuwe loginlink gevraagd",
                "Bij een open login-aanvraag. Bekijk ze bij Login-aanvragen", "/klantlogins");
        TeamNotice notice = onlyNotice();
        assertEquals("Nieuwe loginlink gevraagd · " + email, notice.subject());
        assertEquals("Website · wachtwoord vergeten", notice.kicker());
        assertEquals(email + " vraagt een nieuwe loginlink", notice.title());
        assertEquals("Dit e-mailadres heeft al een login, en er staat ook nog een login-aanvraag voor"
                + " open. Goedkeuren van die aanvraag stuurt de nieuwe link; wijs je ze af, stuur de"
                + " link dan zelf via Klanten, blok Websitelogin.", notice.intro());
        assertEquals(List.of(new TeamFact("E-mail", email),
                new TeamFact("Klant met deze login", "Klant Met Login NV")), notice.facts());
        assertTrue(notice.buttonUrl().endsWith("/klantlogins?open=" + id));
    }

    @Test
    void theSeventhNoticeWithinAnHourArrivesSilently() throws InterruptedException {
        /* Fixed windows: do not start counting right before the hour ends. */
        long left = 3_600 - Instant.now().getEpochSecond() % 3_600;
        if (left < 10) Thread.sleep((left + 1) * 1_000);
        List<Long> ids = new ArrayList<>();
        for (int request = 0; request < 7; request++) {
            ids.add(stored(form(email())));
        }

        for (int request = 0; request < 6; request++) notifier.process(ids.get(request), false);
        verify(sender, times(6)).sendTeamNotice(any());
        verify(push, times(6)).notifyAll(any(), any(), any(), any());

        notifier.process(ids.get(6), false);

        verify(sender, times(6)).sendTeamNotice(any());
        verify(push, times(6)).notifyAll(any(), any(), any(), any());
        verify(housekeeping, times(7)).purge();
        assertEquals(7, requests.summary().pending(), "the silent request is in the list and the count");
    }

    @Test
    void theTwentyFirstNoticeWithinADayArrivesSilently() {
        /* Twenty-one notices never pass the hourly cap; only the daily bucket is real here. */
        doNothing().when(rateLimiter).checkKey(eq(PublicFormAction.ACCOUNT_NOTICE_HOUR), any(), any(), anyInt());
        List<Long> ids = new ArrayList<>();
        for (int request = 0; request < 21; request++) {
            ids.add(stored(form(email())));
        }

        for (int request = 0; request < 20; request++) notifier.process(ids.get(request), false);
        verify(sender, times(20)).sendTeamNotice(any());

        notifier.process(ids.get(20), false);

        verify(sender, times(20)).sendTeamNotice(any());
        verify(push, times(20)).notifyAll(any(), any(), any(), any());
        verify(housekeeping, times(21)).purge();
        assertEquals(21, requests.summary().pending());
    }

    @Test
    void thePushFindsTheDevicesOnAThreadWithoutRequestContextOrTransaction() throws InterruptedException {
        String endpoint = "https://push.invalid/" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            PushSubscriptionEntity device = new PushSubscriptionEntity();
            device.endpoint = endpoint;
            device.p256dh = "not-a-key";
            device.auth = "not-a-secret";
            device.persist();
        });
        AtomicBoolean returned = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            returned.set(true);
            return result;
        }).when(push).notifyAll(any(), any(), any(), any());
        long id = stored(form(email()));
        try {
            Thread plain = new Thread(() -> notifier.process(id, false));
            plain.start();
            plain.join(30_000);

            assertFalse(plain.isAlive());
            assertTrue(returned.get(), "the device lookup ran inside a transaction of its own");
            verify(sender).sendTeamNotice(any());
            verify(housekeeping).purge();
        } finally {
            QuarkusTransaction.requiringNew().run(() -> PushSubscriptionEntity.delete("endpoint", endpoint));
        }
    }

    @Test
    void theTickBoxOfAQuoteIsStoredInTheBackgroundAndRingsNobody() {
        notifier.useExecutor(captured::add);
        String email = email();
        WebsiteQuoteLoginRequested event = new WebsiteQuoteLoginRequested(11L, 21L, "ENR-2026-0021",
                "el", "Bloemen Peeters BV", "be", "BE0123456789", "An Peeters",
                " " + email.toUpperCase() + " ", "+32 14 00 00 00");

        requests.onQuoteLoginRequested(event);

        assertEquals(1, captured.size(), "the committing thread only enqueues");
        assertEquals(0, count(email));

        runCaptured();

        CustomerLoginRequestEntity row = only(email);
        assertEquals("PENDING", row.status);
        assertEquals("QUOTE", row.source);
        assertEquals("EL", row.language);
        assertEquals("Bloemen Peeters BV", row.companyName);
        assertEquals("BE", row.companyCountryCode);
        assertEquals("BE0123456789", row.vatNumber);
        assertEquals("An Peeters", row.contactName);
        assertEquals("+32 14 00 00 00", row.phone);
        assertEquals(11L, row.customerId);
        assertEquals(21L, row.salesOrderId);
        assertEquals("ENR-2026-0021", row.salesOrderNumber);
        verifyNoInteractions(sender);
        verify(push, never()).notifyAll(any(), any(), any(), any());
        verify(housekeeping, times(1)).purge();

        /* A second quote with other details is listed beside the first and does ring. */
        clearInvocations(housekeeping);
        requests.storeFromQuote(new WebsiteQuoteLoginRequested(12L, 22L, "ENR-2026-0022", "nl",
                "Andere Naam BV", "BE", "BE0123456789", "An Peeters", email, null));
        runCaptured();

        assertEquals(1, count(email));
        verify(push).notifyAll("login-request", "Login-aanvraag opnieuw ingediend",
                "Met andere gegevens. Bekijk ze bij Login-aanvragen", "/klantlogins");
        assertEquals(new TeamFact("Bron", "Offerteaanvraag ENR-2026-0022"), onlyNotice().facts().getLast());

        /* An address that cannot be used is logged and dropped; nothing is thrown at the quote. */
        requests.storeFromQuote(new WebsiteQuoteLoginRequested(13L, 23L, "ENR-2026-0023", "nl",
                "Bloemen Peeters BV", "BE", "BE0123456789", "An Peeters", "geen adres", null));
        assertTrue(captured.isEmpty());
    }

    @Test
    void aNewLinkRequestIsStoredOnlyForALoginAndThenAnnounced() {
        String stranger = email();
        requests.requestNewLink(stranger, "nl");
        assertEquals(0, count(stranger));
        verifyNoInteractions(sender);

        String email = email();
        long customerId = customer("Bloemen Peeters BV");
        accounts.grant(customerId, email, "An Peeters", Language.NL);

        requests.requestNewLink(" " + email.toUpperCase(), "nl");

        CustomerLoginRequestEntity row = only(email);
        assertEquals("NEW_LINK", row.source);
        assertEquals(customerId, row.customerId);
        verify(push).notifyAll("login-request", "Nieuwe loginlink gevraagd",
                "Stuur de link vanuit Login-aanvragen", "/klantlogins");
        assertEquals("Nieuwe loginlink gevraagd · Bloemen Peeters BV", onlyNotice().subject());
        verify(housekeeping, times(1)).purge();
    }

    // ------------------------------------------------------------------ fixtures

    private long stored(Candidate candidate) {
        return writer.storeDetached(candidate).requestId();
    }

    private static Candidate form(String email) {
        return new Candidate(LoginRequestWriter.ORDER_SCREEN, email, "NL", "Bloemen Peeters BV", "BE",
                "BE0123456789", "An Peeters", null, null, null, null, null);
    }

    private TeamNotice onlyNotice() {
        ArgumentCaptor<TeamNotice> notice = ArgumentCaptor.forClass(TeamNotice.class);
        verify(sender).sendTeamNotice(notice.capture());
        return notice.getValue();
    }

    private void runCaptured() {
        while (!captured.isEmpty()) captured.removeFirst().run();
    }

    private long customer(String company) {
        Customer customer = customers.create(new Customer(null, company, "Contact " + company, email(),
                null, "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));
        createdCustomers.add(customer.id());
        return customer.id();
    }

    private static String reference(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                CustomerLoginRequestEntity.<CustomerLoginRequestEntity>findById(id).reference);
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

    private static void clearRequestsAndNoticeBuckets() {
        QuarkusTransaction.requiringNew().run(() -> {
            CustomerLoginRequestEntity.deleteAll();
            PublicFormRateBucketEntity.delete("action in ?1", List.of(
                    PublicFormAction.ACCOUNT_NOTICE_HOUR.name(), PublicFormAction.ACCOUNT_NOTICE_DAY.name()));
        });
    }

    private static String email() {
        return "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
