package be.enrosed.account;

import be.enrosed.account.CustomerQuoteResourceHttpTest.QuoteFixture;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.PublicQuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.mail.InternalMessageSender;
import be.enrosed.shared.mail.InternalMessageSender.TeamFact;
import be.enrosed.shared.mail.InternalMessageSender.TeamNotice;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The tick box of the quote form from end to end, in process: the real quote service, the real
 * observer and writer, and a capturing executor in place of the background pool. The quote is
 * answered and committed first; the login request follows on its own and tells nobody twice.
 */
@QuarkusTest
class QuoteLoginRequestFlowTest {
    @Inject PublicQuoteService quotes;
    @Inject LoginRequestNotifier notifier;
    @Inject CustomerService customers;
    @Inject SalesOrderService salesOrders;
    @Inject EntityManager em;
    @InjectMock InternalMessageSender sender;
    @InjectSpy WebPushNotifier push;
    @InjectSpy AccountHousekeeping housekeeping;

    private final QuoteFixture fixture = new QuoteFixture();
    private final List<Runnable> captured = new ArrayList<>();
    private long productId;

    @BeforeEach
    void captureBackgroundWork() {
        doNothing().when(push).notifyAll(any(), any(), any(), any());
        notifier.useExecutor(captured::add);
        clearRequests();
        productId = fixture.orderableProduct(em);
    }

    @AfterEach
    void cleanUp() {
        notifier.useExecutor(ForkJoinPool.commonPool());
        clearRequests();
        fixture.remove(em, customers);
    }

    @Test
    void theQuoteIsAnsweredFirstAndTheLoginRequestFollowsWithoutASecondNotice() {
        String email = QuoteFixture.email();

        PublicQuoteDtos.SubmissionResponse response = quotes.submit(request(email, true));

        assertNotNull(response.reference());
        Customer created = createdCustomer(email);
        SalesOrder order = fixture.order(salesOrders, response.reference());
        assertEquals(created.id(), order.customerId());
        assertTrue(order.internalNotes().endsWith(
                "\n[LOGIN_AANVRAAG] De klant vraagt ook een login; goedkeuren bij Login-aanvragen."),
                order.internalNotes());
        assertEquals(1, captured.size(), "the committed quote enqueued exactly one task");
        assertEquals(0, requests(email).size(), "nothing about the login is stored on the quote's thread");
        verify(housekeeping, never()).purge();

        runCapturedTasks();

        List<CustomerLoginRequestEntity> rows = requests(email);
        assertEquals(1, rows.size());
        CustomerLoginRequestEntity row = rows.getFirst();
        assertEquals(LoginRequestWriter.PENDING, row.status);
        assertEquals(LoginRequestWriter.QUOTE, row.source);
        assertEquals(created.id(), row.customerId);
        assertEquals(order.id(), row.salesOrderId);
        assertEquals(response.reference(), row.salesOrderNumber);
        assertEquals("Typed Company BV", row.companyName);
        assertEquals("An Peeters", row.contactName);
        assertEquals("BE", row.companyCountryCode);
        assertEquals("BE0123456789", row.vatNumber);
        assertEquals("NL", row.language);
        assertNull(row.accountId);
        assertEquals(0, row.repeatCount);
        assertNotNull(row.privacyAcceptedAt);

        /* One visitor action, one notice: the quote's own push and team mail, nothing for the login. */
        verify(push, never()).notifyAll(eq("login-request"), any(), any(), any());
        verify(push, times(1)).notifyAll(eq("sale-quote"), any(), any(), any());
        ArgumentCaptor<TeamNotice> notices = ArgumentCaptor.forClass(TeamNotice.class);
        verify(sender, times(1)).sendTeamNotice(notices.capture());
        TeamNotice quoteMail = notices.getValue();
        assertTrue(quoteMail.subject().startsWith("Nieuwe websiteaanvraag " + response.reference()),
                quoteMail.subject());
        assertTrue(quoteMail.facts().contains(
                new TeamFact("Login gevraagd", "Ja · goedkeuren bij Login-aanvragen")));
        verify(housekeeping).purge();
    }

    @Test
    void aQuoteWithoutTheTickBoxEnqueuesNothing() {
        String email = QuoteFixture.email();

        PublicQuoteDtos.SubmissionResponse response = quotes.submit(request(email, false));

        createdCustomer(email);
        assertFalse(fixture.order(salesOrders, response.reference()).internalNotes()
                .contains("[LOGIN_AANVRAAG]"));
        assertTrue(captured.isEmpty());
        assertEquals(0, requests(email).size());
        ArgumentCaptor<TeamNotice> notices = ArgumentCaptor.forClass(TeamNotice.class);
        verify(sender).sendTeamNotice(notices.capture());
        assertTrue(notices.getValue().facts().stream()
                .noneMatch(fact -> fact.label().equals("Login gevraagd")));
    }

    @Test
    void aQuoteWhoseTransactionRollsBackLeavesNoLoginRequestBehind() {
        String email = QuoteFixture.email();

        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            quotes.submit(request(email, true));
            throw new IllegalStateException("the idempotency row could not be written");
        }));

        assertTrue(captured.isEmpty(), "the observer never ran");
        assertTrue(customersWith(email).isEmpty(), "the quote's customer rolled back");
        assertEquals(0, requests(email).size());
        verifyNoInteractions(sender);
        verify(push, never()).notifyAll(any(), any(), any(), any());
    }

    /** A stored request fires a second task (the notice decision); drain until nothing is left. */
    private void runCapturedTasks() {
        int ran = 0;
        while (!captured.isEmpty()) {
            captured.removeFirst().run();
            ran++;
        }
        assertEquals(2, ran, "one task stores the request, the next decides about the notice");
    }

    private PublicQuoteDtos.SubmitRequest request(String email, boolean loginRequested) {
        return new PublicQuoteDtos.SubmitRequest(
                "NL", "DELIVERY", "BE 0123.456.789",
                new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Markt 1"),
                List.of(new PublicQuoteDtos.ItemRequest(productId, 2)),
                "BE", "Typed Company BV", "An Peeters", email, null, null, true, "",
                null, null, null, loginRequested);
    }

    /** The customer the anonymous quote created; registered so that it is removed afterwards. */
    private Customer createdCustomer(String email) {
        List<Customer> found = customersWith(email);
        assertEquals(1, found.size());
        fixture.adopt(found.getFirst().id());
        return found.getFirst();
    }

    private List<Customer> customersWith(String email) {
        return customers.list().stream().filter(customer -> email.equals(customer.email())).toList();
    }

    private static List<CustomerLoginRequestEntity> requests(String email) {
        return QuarkusTransaction.requiringNew().call(() -> CustomerLoginRequestEntity.list("email", email));
    }

    private static void clearRequests() {
        QuarkusTransaction.requiringNew().run(() -> CustomerLoginRequestEntity.deleteAll());
    }
}
