package be.enrosed.sales.application;

import be.enrosed.account.CustomerAccountEntity;
import be.enrosed.sales.application.WebOrderRecipients.Recipient;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static be.enrosed.sales.application.WebOrderTermsTest.order;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whom a customer mail about a document reaches: the login that placed the
 * website order while it can still be trusted, the customer record in every
 * other case, and the record exactly as before for a document that is no
 * website order.
 */
@QuarkusTest
class WebOrderRecipientsTest {
    private static final AtomicLong IDS = new AtomicLong(7_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Inject WebOrderRecipients recipients;
    @Inject WebOrders webOrders;
    @Inject EntityManager em;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> loginIds = new ArrayList<>();

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            orderIds.forEach(id -> em.createQuery("delete from SalesWebOrderEntity w where w.salesOrderId = :id")
                    .setParameter("id", id).executeUpdate());
            loginIds.forEach(id -> em.createQuery("delete from CustomerAccountEntity a where a.id = :id")
                    .setParameter("id", id).executeUpdate());
        });
    }

    @Test
    void aSecondLoginWithItsOwnAddressGetsTheMailAndTheRecordACopy() {
        long customerId = IDS.incrementAndGet();
        long loginId = login(customerId, "buyer-" + customerId + "@login.example", "ACTIVE");
        long id = webOrder(customerId, loginId, "  Buyer-" + customerId + "@login.example ", "EL");

        Recipient recipient = recipients.of(order().id(id).customerId(customerId).build(), customer(customerId, Language.NL));

        assertEquals("Buyer-" + customerId + "@login.example", recipient.to());
        assertEquals("inkoop@record.example", recipient.cc());
        assertEquals(Language.EL, recipient.language(), "the language of the page the order was placed on");
    }

    @Test
    void aLoginThatIsGoneWithdrawnOrSomebodyElsesFallsBackToTheRecordWithoutCopy() {
        long customerId = IDS.incrementAndGet();
        Customer customer = customer(customerId, Language.FR);

        long disabled = login(customerId, "disabled-" + customerId + "@login.example", "DISABLED");
        long withdrawn = webOrder(customerId, disabled, "disabled-" + customerId + "@login.example", "DE");
        assertEquals(new Recipient("inkoop@record.example", null, Language.DE),
                recipients.of(order().id(withdrawn).customerId(customerId).build(), customer));

        long invited = login(customerId, "invited-" + customerId + "@login.example", "INVITED");
        long notActive = webOrder(customerId, invited, "invited-" + customerId + "@login.example", "DE");
        assertEquals(new Recipient("inkoop@record.example", null, Language.DE),
                recipients.of(order().id(notActive).customerId(customerId).build(), customer));

        long removed = webOrder(customerId, IDS.incrementAndGet(), "removed-" + customerId + "@login.example", "DE");
        assertEquals(new Recipient("inkoop@record.example", null, Language.DE),
                recipients.of(order().id(removed).customerId(customerId).build(), customer));

        long foreign = login(IDS.incrementAndGet(), "foreign-" + customerId + "@login.example", "ACTIVE");
        long moved = webOrder(customerId, foreign, "foreign-" + customerId + "@login.example", "DE");
        assertEquals(new Recipient("inkoop@record.example", null, Language.DE),
                recipients.of(order().id(moved).customerId(customerId).build(), customer),
                "the login was moved to another customer since it ordered");
    }

    @Test
    void aLoginWithTheRecordsOwnAddressGetsNoCopy() {
        long customerId = IDS.incrementAndGet();
        long loginId = login(customerId, "same-" + customerId + "@login.example", "ACTIVE");
        long id = webOrder(customerId, loginId, "inkoop@record.example", "NL");

        Recipient recipient = recipients.of(order().id(id).customerId(customerId).build(),
                customer(customerId, " Inkoop@Record.example ", Language.NL));

        assertEquals(new Recipient("inkoop@record.example", null, Language.NL), recipient);
    }

    @Test
    void aRecordWithoutAnAddressLeavesTheLoginWithoutCopy() {
        long customerId = IDS.incrementAndGet();
        long loginId = login(customerId, "only-" + customerId + "@login.example", "ACTIVE");
        long id = webOrder(customerId, loginId, "only-" + customerId + "@login.example", "NL");

        assertEquals(new Recipient("only-" + customerId + "@login.example", null, Language.NL),
                recipients.of(order().id(id).customerId(customerId).build(), customer(customerId, " ", Language.NL)));
    }

    @Test
    void aDocumentWithoutARowMailsTheRecordExactlyAsBefore() {
        long customerId = IDS.incrementAndGet();
        Customer customer = customer(customerId, Language.PT);

        assertEquals(new Recipient("inkoop@record.example", null, Language.PT),
                recipients.of(order().id(IDS.incrementAndGet()).customerId(customerId).build(), customer));
        assertEquals(new Recipient("inkoop@record.example", null, Language.PT),
                recipients.of(order().id(null).customerId(customerId).build(), customer), "a document not stored yet");
    }

    @Test
    void aDocumentRelinkedToAnotherCustomerIgnoresTheRow() {
        long placedFor = IDS.incrementAndGet();
        long loginId = login(placedFor, "buyer-" + placedFor + "@login.example", "ACTIVE");
        long id = webOrder(placedFor, loginId, "buyer-" + placedFor + "@login.example", "EL");
        long other = IDS.incrementAndGet();

        Recipient recipient = recipients.of(order().id(id).customerId(other).build(), customer(other, Language.ES));

        assertEquals(new Recipient("inkoop@record.example", null, Language.ES), recipient,
                "neither the login nor the page language of the first customer");
    }

    @Test
    void anUnknownOrMissingPageLanguageFallsBackToTheRecords() {
        long customerId = IDS.incrementAndGet();
        long loginId = login(customerId, "buyer-" + customerId + "@login.example", "ACTIVE");
        Customer customer = customer(customerId, Language.TR);

        long unknown = webOrder(customerId, loginId, "buyer-" + customerId + "@login.example", "XX");
        assertEquals(Language.TR, recipients.of(order().id(unknown).customerId(customerId).build(), customer).language());
        long missing = webOrder(customerId, loginId, "buyer-" + customerId + "@login.example", null);
        assertEquals(Language.TR, recipients.of(order().id(missing).customerId(customerId).build(), customer).language());
        long lower = webOrder(customerId, loginId, "buyer-" + customerId + "@login.example", "pl");
        assertEquals(Language.PL, recipients.of(order().id(lower).customerId(customerId).build(), customer).language());
    }

    // ------------------------------------------------------------------------------------------ fixtures

    private long webOrder(long customerId, long accountId, String accountEmail, String language) {
        long id = IDS.incrementAndGet();
        QuarkusTransaction.requiringNew().run(() -> webOrders.create(id, customerId, accountId, accountEmail, language, null, null));
        orderIds.add(id);
        return id;
    }

    private long login(long customerId, String email, String status) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            CustomerAccountEntity login = new CustomerAccountEntity();
            login.customerId = customerId;
            login.email = email;
            login.status = status;
            login.language = "nl";
            login.createdAt = Instant.now();
            em.persist(login);
            em.flush();
            return login.id;
        });
        loginIds.add(id);
        return id;
    }

    private static Customer customer(long id, Language language) {
        return customer(id, "inkoop@record.example", language);
    }

    private static Customer customer(long id, String email, Language language) {
        return new Customer(id, "Royal Garden Center Group", "Anne van den Berg", email, "+32 3 555 01 02",
                "BE 0123.456.789", "BE", language, "Bloemenlaan 112", "2000", "Antwerpen", "DAP",
                "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
    }
}
