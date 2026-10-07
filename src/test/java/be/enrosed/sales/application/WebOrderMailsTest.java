package be.enrosed.sales.application;

import be.enrosed.account.CustomerAccountEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.WebOrderDeliveries.Delivery;
import be.enrosed.sales.application.WebOrderMails.Due;
import be.enrosed.sales.application.WebOrderMails.Kind;
import be.enrosed.sales.application.WebOrders.Row;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static be.enrosed.sales.application.WebOrderTermsTest.order;
import static be.enrosed.sales.application.WebOrderTermsTest.ordered;
import static be.enrosed.sales.application.WebOrderTermsTest.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The two customer mails of a website order, against the real side tables
 * and the mock mailbox: which mail is due, that it is claimed before it
 * leaves and so leaves once, what a failure leaves behind, and that an order
 * the same staff action sent, cancelled or moved to another customer gets
 * none. The document and the customer record are stubbed.
 */
@QuarkusTest
class WebOrderMailsTest {
    private static final AtomicLong IDS = new AtomicLong(6_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final String RECORD = "inkoop@record.example";
    private static final String NUMBER = "OF-2026-0123";

    @Inject WebOrderMails mails;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @Inject Event<WebOrderEvents.Placed> placed;
    @Inject Event<WebOrderEvents.Changed> changed;
    @Inject Event<WebOrderEvents.Cancelled> cancelled;
    @Inject Event<WebOrderEvents.Taken> taken;
    @InjectMock SalesRepositories.Orders orders;
    @InjectMock SalesRepositories.Customers customers;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> loginIds = new ArrayList<>();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (long id : orderIds) {
                em.createQuery("delete from SalesWebOrderEntity w where w.salesOrderId = :id").setParameter("id", id).executeUpdate();
                em.createQuery("delete from SalesOrderDeliveryEntity d where d.salesOrderId = :id").setParameter("id", id).executeUpdate();
            }
            loginIds.forEach(id -> em.createQuery("delete from CustomerAccountEntity a where a.id = :id")
                    .setParameter("id", id).executeUpdate());
        });
    }

    // ------------------------------------------------------------------------------------------ which mail is due

    @Test
    void atMostOneMailIsDueAndOnlyForAnUnsentConceptOfThePlacingCustomer() {
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        Row fresh = row(4711, 2L, now.minusSeconds(30), null, null, null, null);
        SalesOrder concept = order().build();

        assertEquals(new Due(Kind.RECEIVED, false), WebOrderMails.due(concept, fresh, now));
        assertEquals(new Due(Kind.RECEIVED, false), WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(60), null, null, null, null), now));
        assertEquals(new Due(Kind.RECEIVED, true), WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(61), null, null, null, null), now),
                "after a minute staff are told");
        assertNull(WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(600), null, null, now, null), now), "received mail sent");

        Row taken = row(4711, 2L, now.minusSeconds(600), now.minusSeconds(10), null, null, null);
        assertEquals(new Due(Kind.PROCESSING, false), WebOrderMails.due(concept, taken, now),
                "never the received mail once the order is taken, and its own minute starts at the take");
        assertEquals(new Due(Kind.PROCESSING, true),
                WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(600), now.minusSeconds(90), null, null, null), now));
        assertNull(WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(600), now.minusSeconds(90), null, null, now), now));

        assertNull(WebOrderMails.due(null, fresh, now), "the document is gone");
        assertNull(WebOrderMails.due(concept, null, now), "no website order");
        assertNull(WebOrderMails.due(order().customerId(3L).build(), fresh, now), "re-linked to another customer");
        assertNull(WebOrderMails.due(order().status(QuoteStatus.GEANNULEERD).build(), taken, now));
        assertNull(WebOrderMails.due(order().status(QuoteStatus.VERZONDEN).sentAt(now).build(), taken, now));
        assertNull(WebOrderMails.due(order().sentAt(now).build(), taken, now), "sent, then reopened");
        assertNull(WebOrderMails.due(concept, row(4711, 2L, now.minusSeconds(600), null, now, null, null), now), "cancelled by the customer");
    }

    // ------------------------------------------------------------------------------------------ observers

    @Test
    void aPlacedOrderIsMailedAfterTheCommitToTheLoginThatOrderedInThePageLanguage() {
        Fixture f = fixture("EL", true);

        QuarkusTransaction.requiringNew().run(() -> {
            placed.fire(new WebOrderEvents.Placed(f.id, NUMBER));
            assertEquals(0, mailbox.getTotalMessagesSent(), "nothing leaves before the commit");
        });

        Mail mail = only(f.login);
        var text = DocumentText.of(Language.EL);
        assertEquals(text.get("mailOrderReceivedSubject").formatted(NUMBER), mail.getSubject());
        assertTrue(mail.getCc().isEmpty(), "the order mails carry no visible copy: " + mail.getCc());
        assertTrue(mail.getBcc().contains("admin@enrosed.com"), "the office reads along as for every customer mail");
        assertEquals(0, mailbox.getMailsSentTo(RECORD).size());
        String html = mail.getHtml();
        assertTrue(html.contains("<html lang=\"el\">"), html);
        assertTrue(html.contains("Rode roos") && html.contains("4 " + text.get("mailOrderCartons") + " × 24"), html);
        assertTrue(html.contains(DocumentText.money(new java.math.BigDecimal("254.97"), Language.EL) + " EUR"), html);
        assertTrue(html.contains("Industrieweg 1, 3980 Tessenderlo, Βέλγιο"), "the address typed for this order: " + html);
        assertTrue(html.contains(text.get("mailGreeting") + " Jan Besteller,"), "the contact of this order: " + html);
        assertFalse(html.contains("Bloemenlaan"), "never the address of the customer record");

        SalesWebOrderEntity stored = stored(f.id);
        assertNotNull(stored.receivedMailSentAt);
        assertNull(stored.processingMailSentAt);
        assertNull(stored.mailError);
        assertEquals(1, stored.revision, "the mail marker never touches the order state");
    }

    @Test
    void aRolledBackPlacementMailsNobody() {
        Fixture f = fixture("NL", true);

        QuarkusTransaction.begin();
        placed.fire(new WebOrderEvents.Placed(f.id, NUMBER));
        QuarkusTransaction.rollback();

        assertEquals(0, mailbox.getTotalMessagesSent());
        assertNull(stored(f.id).receivedMailSentAt);
    }

    @Test
    void aChangeOrACancellationByTheCustomerMailsNobody() {
        Fixture f = fixture("NL", true);
        claimed(f.id, e -> e.receivedMailSentAt = Instant.now().minusSeconds(120));
        Instant sentBefore = stored(f.id).receivedMailSentAt;

        QuarkusTransaction.requiringNew().run(() -> changed.fire(new WebOrderEvents.Changed(f.id, NUMBER, 2, "ER-RED: 4 → 6 dozen")));
        QuarkusTransaction.requiringNew().run(() -> cancelled.fire(new WebOrderEvents.Cancelled(f.id, NUMBER)));

        assertEquals(0, mailbox.getTotalMessagesSent());
        assertEquals(sentBefore, stored(f.id).receivedMailSentAt);
    }

    @Test
    void aTakenOrderGetsTheProcessingMailAndNeverTheReceivedMailAfterwards() {
        Fixture f = fixture("NL", true);
        claimed(f.id, e -> {
            e.mailError = "De mail kon niet verzonden worden via de maildienst: maildienst antwoordde 500";
            e.processingStartedAt = Instant.now();
            e.processingStartedBy = "Emre";
            e.processingTrigger = WebOrders.TRIGGER_BUTTON;
        });

        QuarkusTransaction.requiringNew().run(() -> taken.fire(new WebOrderEvents.Taken(f.id, NUMBER)));

        Mail mail = only(f.login);
        var text = DocumentText.of(Language.NL);
        assertEquals(text.get("mailOrderProcessingSubject").formatted(NUMBER), mail.getSubject());
        assertTrue(mail.getCc().isEmpty());
        assertTrue(mail.getHtml().contains(text.get("mailOrderProcessingNext")), mail.getHtml());
        assertFalse(mail.getHtml().contains("Rode roos"), "no lines table in this mail");
        SalesWebOrderEntity stored = stored(f.id);
        assertNotNull(stored.processingMailSentAt);
        assertNull(stored.receivedMailSentAt, "the received mail failed earlier and is no longer due");
        assertNull(stored.mailError, "the error belonged to a mail that is no longer due");

        mailbox.clear();
        mails.onPlaced(new WebOrderEvents.Placed(f.id, NUMBER));
        assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    void noProcessingMailWhenTheSameActionCancelledOrSentTheOrderOrTheDocumentIsGone() {
        Fixture cancelledByStaff = fixture("NL", true);
        Fixture sent = fixture("NL", true);
        Fixture gone = fixture("NL", true);
        for (Fixture f : List.of(cancelledByStaff, sent, gone)) claimed(f.id, e -> e.processingStartedAt = Instant.now());
        when(orders.findById(cancelledByStaff.id)).thenReturn(Optional.of(
                order().id(cancelledByStaff.id).customerId(cancelledByStaff.customerId).status(QuoteStatus.GEANNULEERD).build()));
        when(orders.findById(sent.id)).thenReturn(Optional.of(order().id(sent.id).customerId(sent.customerId)
                .status(QuoteStatus.VERZONDEN).sentAt(Instant.now()).portalToken("token").build()));
        when(orders.findById(gone.id)).thenReturn(Optional.empty());

        for (Fixture f : List.of(cancelledByStaff, sent, gone)) {
            QuarkusTransaction.requiringNew().run(() -> taken.fire(new WebOrderEvents.Taken(f.id, NUMBER)));
            SalesWebOrderEntity stored = stored(f.id);
            assertNull(stored.processingMailSentAt, "a mail that is skipped on purpose is never marked sent");
            assertNull(stored.receivedMailSentAt);
            assertNull(stored.mailError);
        }
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    void aDocumentRelinkedToAnotherCustomerMailsNobodyAndRecordsNoError() {
        Fixture f = fixture("NL", true);
        long other = IDS.incrementAndGet();
        when(orders.findById(f.id)).thenReturn(Optional.of(order().id(f.id).customerId(other).build()));
        when(customers.findById(other)).thenReturn(Optional.of(customer(other, "nieuw@other.example")));

        mails.onPlaced(new WebOrderEvents.Placed(f.id, NUMBER));
        claimed(f.id, e -> e.processingStartedAt = Instant.now());
        mails.onTaken(new WebOrderEvents.Taken(f.id, NUMBER));

        assertEquals(0, mailbox.getTotalMessagesSent());
        SalesWebOrderEntity stored = stored(f.id);
        assertNull(stored.receivedMailSentAt);
        assertNull(stored.processingMailSentAt);
        assertNull(stored.mailError);
        assertEquals(WebOrderMails.NOTHING_DUE, assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false)).getMessage());
        assertEquals(WebOrderMails.NOTHING_DUE, assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, true)).getMessage());
    }

    // ------------------------------------------------------------------------------------------ claim and failure

    @Test
    void twoCallersAtTheSameMomentSendTheMailOnce() throws Exception {
        Fixture f = fixture("NL", true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int caller = 0; caller < 2; caller++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    try {
                        mails.resend(f.id, false);
                        return "sent";
                    } catch (BusinessRuleException refused) {
                        return refused.getMessage();
                    }
                }));
            }
            start.countDown();
            List<String> outcome = new ArrayList<>();
            for (Future<String> answer : answers) outcome.add(answer.get(30, TimeUnit.SECONDS));
            outcome.sort(null);
            assertEquals(List.of(WebOrderMails.NOTHING_DUE, "sent"), outcome);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, mailbox.getMailsSentTo(f.login).size());
        assertNotNull(stored(f.id).receivedMailSentAt);
    }

    @Test
    void theMomentIsClaimedOnceAndOnlyForTheMailThatIsDue() {
        Fixture f = fixture("NL", true);
        Instant now = Instant.now();

        assertFalse(mails.claim(f.id, Kind.PROCESSING, now), "not taken: the processing mail is not due");
        assertNull(stored(f.id).processingMailSentAt);
        assertTrue(mails.claim(f.id, Kind.RECEIVED, now));
        assertFalse(mails.claim(f.id, Kind.RECEIVED, now), "somebody else owns it");

        Fixture takenFirst = fixture("NL", true);
        claimed(takenFirst.id, e -> e.processingStartedAt = now);
        assertFalse(mails.claim(takenFirst.id, Kind.RECEIVED, now), "taken: the received mail is no longer due");
        assertNull(stored(takenFirst.id).receivedMailSentAt);
        assertTrue(mails.claim(takenFirst.id, Kind.PROCESSING, now));
        assertFalse(mails.claim(takenFirst.id, Kind.PROCESSING, now));
        assertFalse(mails.claim(IDS.incrementAndGet(), Kind.RECEIVED, now), "no row");
    }

    @Test
    void aFailurePutsTheMomentBackAndLeavesTheReasonAndASuccessClearsIt() {
        Fixture f = fixture("NL", false);
        when(customers.findById(f.customerId)).thenReturn(Optional.of(customer(f.customerId, null)));

        mails.onPlaced(new WebOrderEvents.Placed(f.id, NUMBER));

        assertEquals(0, mailbox.getTotalMessagesSent());
        SalesWebOrderEntity failed = stored(f.id);
        assertNull(failed.receivedMailSentAt, "the mail is due again");
        assertEquals("De klant heeft geen e-mailadres; de e-mail over de bestelling is niet verstuurd.", failed.mailError);
        assertEquals(failed.mailError, assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false)).getMessage());

        when(customers.findById(f.customerId)).thenReturn(Optional.of(customer(f.customerId, RECORD)));
        mails.resend(f.id, false);

        Mail mail = only(RECORD);
        assertTrue(mail.getCc().isEmpty());
        SalesWebOrderEntity sent = stored(f.id);
        assertNotNull(sent.receivedMailSentAt);
        assertNull(sent.mailError);
        assertEquals(WebOrderMails.NOTHING_DUE, assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false)).getMessage(),
                "a second click finds nothing due");
        only(RECORD);
    }

    @Test
    void theMockMailerOfADeployedEnvironmentRefusesAndStaffReadItOnTheOrder() {
        Fixture f = fixture("NL", true);
        LaunchMode before = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            BusinessRuleException refused = assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false));
            assertEquals("De mailer staat in testmodus; de e-mail aan de klant is niet verstuurd", refused.getMessage());
        } finally {
            LaunchMode.set(before);
        }
        assertEquals(0, mailbox.getTotalMessagesSent());
        SalesWebOrderEntity stored = stored(f.id);
        assertNull(stored.receivedMailSentAt);
        assertEquals("De mailer staat in testmodus; de e-mail aan de klant is niet verstuurd", stored.mailError);
    }

    // ------------------------------------------------------------------------------------------ repeat

    @Test
    void aMomentThatWasClaimedWithoutAMailLeavingIsRepeatedOnRequest() {
        Fixture f = fixture("NL", true);
        Instant claimedAt = Instant.now().minusSeconds(3_600);
        claimed(f.id, e -> e.receivedMailSentAt = claimedAt);

        mails.onPlaced(new WebOrderEvents.Placed(f.id, NUMBER));
        assertEquals(WebOrderMails.NOTHING_DUE, assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, false)).getMessage());
        assertEquals(0, mailbox.getTotalMessagesSent(), "the crash window: marked sent, never left");

        mails.resend(f.id, true);

        assertEquals(DocumentText.of(Language.NL).get("mailOrderReceivedSubject").formatted(NUMBER), only(f.login).getSubject());
        assertTrue(stored(f.id).receivedMailSentAt.isAfter(claimedAt.plusSeconds(3_000)), "the moment moves to the repeat");

        claimed(f.id, e -> {
            e.processingStartedAt = Instant.now();
            e.processingMailSentAt = claimedAt;
        });
        mailbox.clear();
        mails.resend(f.id, true);
        assertEquals(DocumentText.of(Language.NL).get("mailOrderProcessingSubject").formatted(NUMBER), only(f.login).getSubject());
        assertTrue(stored(f.id).processingMailSentAt.isAfter(claimedAt.plusSeconds(3_000)));
    }

    @Test
    void aRepeatWithAMailStillDueSendsThatMailOnce() {
        Fixture f = fixture("NL", true);

        mails.resend(f.id, true);

        assertEquals(1, mailbox.getMailsSentTo(f.login).size());
        assertNotNull(stored(f.id).receivedMailSentAt);
    }

    @Test
    void nothingIsRepeatedForASentCancelledOrNeverMailedOrder() {
        Fixture never = fixture("NL", true);
        claimed(never.id, e -> e.processingStartedAt = Instant.now());
        claimed(never.id, e -> e.processingMailSentAt = null);
        Fixture sent = fixture("NL", true);
        Fixture cancelledByStaff = fixture("NL", true);
        Fixture cancelledByCustomer = fixture("NL", true);
        for (Fixture f : List.of(sent, cancelledByStaff, cancelledByCustomer)) claimed(f.id, e -> e.receivedMailSentAt = Instant.now());
        when(orders.findById(sent.id)).thenReturn(Optional.of(order().id(sent.id).customerId(sent.customerId)
                .status(QuoteStatus.VERZONDEN).sentAt(Instant.now()).build()));
        when(orders.findById(cancelledByStaff.id)).thenReturn(Optional.of(
                order().id(cancelledByStaff.id).customerId(cancelledByStaff.customerId).status(QuoteStatus.GEANNULEERD).build()));
        claimed(cancelledByCustomer.id, e -> e.customerCancelledAt = Instant.now());

        for (Fixture f : List.of(sent, cancelledByStaff, cancelledByCustomer)) {
            assertEquals(WebOrderMails.NOTHING_DUE,
                    assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, true)).getMessage());
        }
        assertEquals(WebOrderMails.NOTHING_DUE,
                assertThrows(BusinessRuleException.class, () -> mails.resend(IDS.incrementAndGet(), true)).getMessage(), "no row");
        assertEquals(0, mailbox.getTotalMessagesSent());

        mails.resend(never.id, true);
        assertEquals(1, mailbox.getMailsSentTo(never.login).size(), "taken and never mailed: the processing mail is simply due");
    }

    @Test
    void aFailingRepeatLeavesTheRowAsItWas() {
        Fixture f = fixture("NL", false);
        claimed(f.id, e -> e.receivedMailSentAt = Instant.now().minusSeconds(3_600));
        Instant before = stored(f.id).receivedMailSentAt;
        when(customers.findById(f.customerId)).thenReturn(Optional.of(customer(f.customerId, " ")));

        BusinessRuleException refused = assertThrows(BusinessRuleException.class, () -> mails.resend(f.id, true));

        assertEquals("De klant heeft geen e-mailadres; de e-mail over de bestelling is niet verstuurd.", refused.getMessage());
        SalesWebOrderEntity stored = stored(f.id);
        assertEquals(before, stored.receivedMailSentAt);
        assertNull(stored.mailError);
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    // ------------------------------------------------------------------------------------------ content

    @Test
    void anOrderWhoseFreightWasOpenShowsNoTotalsAndACollectionShowsThePickupPoint() {
        Fixture open = fixture("NL", true);
        WebOrderSnapshot complete = snapshot(ordered());
        WebOrderSnapshot.Totals totals = complete.totals();
        WebOrderSnapshot toConfirm = new WebOrderSnapshot(1, 1, complete.at(), "NL", "BE", "PICKUP", null, complete.lines(),
                new WebOrderSnapshot.Totals(totals.goods(), null, "TO_CONFIRM", null, totals.vatRatePct(), null, null,
                        totals.vatTreatment()), false);
        claimed(open.id, e -> e.orderSnapshot = toConfirm.toJson());
        QuarkusTransaction.requiringNew().run(() -> deliveries.save(open.id, new Delivery(open.customerId, "PICKUP", null, null,
                null, 3L, "Magazijn Tessenderlo", "Industrieweg 1, 3980 Tessenderlo", null, "+32 470 00 00 00", null)));

        mails.resend(open.id, false);

        var text = DocumentText.of(Language.NL);
        String html = only(open.login).getHtml();
        assertTrue(html.contains(text.get("mailOrderDeliveryCosts")) && html.contains(text.get("mailOrderShippingToConfirm")), html);
        assertFalse(html.contains(text.get("mailOrderTotalExclVat")), "no total the page never showed: " + html);
        assertFalse(html.contains(text.get("totalInclVat")), html);
        assertTrue(html.contains(text.get("mailOrderPickup")), html);
        assertTrue(html.contains("Magazijn Tessenderlo, Industrieweg 1, 3980 Tessenderlo"), html);
        assertTrue(html.contains(text.get("mailGreeting") + " Anne van den Berg,"), "no contact on the order: the record's");
    }

    // ------------------------------------------------------------------------------------------ fixtures

    private record Fixture(long id, long customerId, String login) {}

    /** A website order as it stands right after placement, with an active second login when asked. */
    private Fixture fixture(String language, boolean withLogin) {
        long id = IDS.incrementAndGet();
        long customerId = IDS.incrementAndGet();
        String login = "buyer-" + id + "@login.example";
        long accountId = withLogin ? QuarkusTransaction.requiringNew().call(() -> {
            CustomerAccountEntity account = new CustomerAccountEntity();
            account.customerId = customerId;
            account.email = login;
            account.status = "ACTIVE";
            account.language = "nl";
            account.createdAt = Instant.now();
            em.persist(account);
            em.flush();
            return account.id;
        }) : IDS.incrementAndGet();
        if (withLogin) loginIds.add(accountId);
        orderIds.add(id);
        QuarkusTransaction.requiringNew().run(() -> {
            webOrders.create(id, customerId, accountId, login, language, snapshot(ordered()).toJson(), WebOrderTerms.of(ordered()));
            deliveries.save(id, new Delivery(customerId, "DELIVERY", "Industrieweg 1", "3980", "Tessenderlo", null, null,
                    null, "Jan Besteller", "+32 470 00 00 00", null));
        });
        when(orders.findById(id)).thenReturn(Optional.of(order().id(id).customerId(customerId).build()));
        when(customers.findById(customerId)).thenReturn(Optional.of(customer(customerId, RECORD)));
        return new Fixture(id, customerId, login);
    }

    private static Customer customer(long id, String email) {
        return new Customer(id, "Royal Garden Center Group", "Anne van den Berg", email, "+32 3 555 01 02",
                "BE 0123.456.789", "BE", Language.NL, "Bloemenlaan 112", "2000", "Antwerpen", "DAP",
                "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
    }

    /** Writes the row as another transaction would have left it. */
    private void claimed(long id, Consumer<SalesWebOrderEntity> change) {
        QuarkusTransaction.requiringNew().run(() -> change.accept(em.find(SalesWebOrderEntity.class, id)));
    }

    private SalesWebOrderEntity stored(long id) {
        return QuarkusTransaction.requiringNew().call(() -> em.find(SalesWebOrderEntity.class, id));
    }

    /** The one mail that left; the mailbox counts it once per address it reached. */
    private Mail only(String address) {
        List<Mail> sent = mailbox.getMailsSentTo(address);
        assertEquals(1, sent.size(), "to " + address);
        Mail mail = sent.getFirst();
        assertEquals(1 + mail.getCc().size() + mail.getBcc().size(), mailbox.getTotalMessagesSent(), "exactly one mail left");
        return mail;
    }

    private static Row row(long id, Long customerId, Instant placedAt, Instant processingStartedAt,
                           Instant customerCancelledAt, Instant receivedMailSentAt, Instant processingMailSentAt) {
        return new Row(id, customerId, 9L, "buyer@login.example", "NL", 1, placedAt, null, null, customerCancelledAt,
                processingStartedAt, processingStartedAt == null ? null : "Emre",
                processingStartedAt == null ? null : WebOrders.TRIGGER_BUTTON, null, null, null, null,
                receivedMailSentAt, processingMailSentAt, null);
    }
}
