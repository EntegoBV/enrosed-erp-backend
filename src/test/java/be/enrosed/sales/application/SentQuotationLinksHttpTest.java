package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every link a customer legitimately holds keeps opening over HTTP, against
 * the real tables: through every status, every reopening and every resend.
 * A reopened draft answers "being updated", never the 404 of an unknown link.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class SentQuotationLinksHttpTest {
    private static final String PORTAL = "/api/portal/";

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject QuoteMailer mailer;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    private final Shop shop = new Shop();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    private SalesOrder sentQuote(long customerId, long productId) {
        SalesOrder created = sales.create(customerId, "BE", "DAP");
        sales.update(created.id(), shop.edited(order(created.id()), productId, 120, "10.00", "120.00"));
        return quotes.send(created.id(), null);
    }

    private void opens(String token, String status, String step) {
        Response page = given().when().get(PORTAL + token);
        assertEquals(200, page.statusCode(), step + ": " + page.asString());
        assertEquals(status, page.jsonPath().getString("status"), step);
        assertEquals(1, page.jsonPath().getList("lines").size(), step);
        Response pdf = given().when().get(PORTAL + token + "/pdf");
        assertEquals(200, pdf.statusCode(), step + " pdf");
        assertTrue(pdf.contentType().startsWith("application/pdf"), step);
        Response products = given().when().get(PORTAL + token + "/products");
        assertEquals(200, products.statusCode(), step + " products");
    }

    private void beingUpdated(String token, String step) {
        Response page = given().when().get(PORTAL + token);
        assertNotEquals(404, page.statusCode(), step + ": " + page.asString());
        assertNotEquals(200, page.statusCode(), step);
        assertTrue(page.asString().contains("wordt momenteel bijgewerkt"), step + ": " + page.asString());
        assertEquals(409, page.statusCode(), step);
        assertEquals("QUOTE_BEING_UPDATED", page.jsonPath().getString("code"), step);
        assertNull(page.jsonPath().getString("cancellationMessage"), step);
    }

    @Test
    void aSentQuotationOpensInEveryStatusAndThroughEveryReopenAndResend() {
        long customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();
        SalesOrder sent = sentQuote(customerId, productId);
        long id = sent.id();
        String token = sent.portalToken();
        assertNotNull(token);
        assertNotNull(sent.sentAt());

        Response first = given().when().get(PORTAL + token);
        assertEquals(200, first.statusCode());
        opens(token, "BEKEKEN", "first opening");
        assertTrue(given().when().get(PORTAL + token + "/products").asString().contains("unitPrice"), "price list");

        /* The customer proposes a change, staff adopt it: draft, then sent again. */
        Response proposed = given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag een week later", "lines", List.of()))
                .when().post(PORTAL + token + "/propose");
        assertEquals(200, proposed.statusCode(), proposed.asString());
        opens(token, "WIJZIGING_GEVRAAGD", "after proposal");
        QuoteRevision pending = quotes.revisionsFor(id).getFirst();
        quotes.approveRevision(pending.id(), "emre", null);
        assertEquals(QuoteStatus.CONCEPT, order(id).status());
        beingUpdated(token, "adopted proposal");
        SalesOrder resent = quotes.send(id, null);
        assertEquals(token, resent.portalToken());
        opens(token, "BEKEKEN", "after resend");

        /* Rejected by the customer, reopened, edited and sent again. */
        Response rejected = given().contentType("application/json").body(Map.of("message", "Te duur"))
                .when().post(PORTAL + token + "/reject");
        assertEquals(200, rejected.statusCode(), rejected.asString());
        opens(token, "AFGEWEZEN", "rejected");
        quotes.reopen(id);
        beingUpdated(token, "reopened after reject");
        sales.update(id, shop.edited(order(id), productId, 240, "9.00", "120.00"));
        beingUpdated(token, "edited draft");
        assertEquals(token, quotes.send(id, null).portalToken());
        opens(token, "BEKEKEN", "edited and resent");

        /* Expired. */
        shop.inTransaction(() -> {
            em.find(SalesOrderEntity.class, id).status = QuoteStatus.VERLOPEN;
        });
        em.clear();
        opens(token, "VERLOPEN", "expired");
        quotes.reopen(id);
        beingUpdated(token, "reopened after expiry");
        quotes.send(id, null);

        /* Accepted. */
        Response accepted = given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept");
        assertEquals(200, accepted.statusCode(), accepted.asString());
        opens(token, "GEACCEPTEERD", "accepted");

        /* The invoice made from it is a document of its own without a token; the quote link stays. */
        SalesOrder invoice = sales.createInvoiceFrom(id);
        assertNull(invoice.portalToken());
        SalesOrder issued = sales.issueInvoice(invoice.id());
        assertEquals(QuoteStatus.UITGEREIKT, issued.status());
        assertNull(issued.portalToken());
        assertNull(issued.sentAt(), "issued without mailing has no sent timestamp");
        assertTrue(quotes.activePortalUrl(issued).isEmpty());
        SalesOrder mailedInvoice = quotes.send(invoice.id(), null);
        assertNull(mailedInvoice.portalToken());
        assertNotNull(mailedInvoice.sentAt());
        opens(token, "GEACCEPTEERD", "accepted, invoiced");
    }

    @Test
    void aSentQuotationCancelledWithoutNotifyKeepsItsLinkAndNoMailLeaves() {
        long customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        SalesOrder sent = sentQuote(customerId, shop.product());
        String token = sent.portalToken();
        opens(token, "BEKEKEN", "viewed");
        mailbox.clear();

        SalesOrder cancelled = quotes.cancel(sent.id(), "Niet meer leverbaar", false);

        assertEquals(token, cancelled.portalToken());
        assertEquals(0, mailbox.getTotalMessagesSent());
        QuoteEvent event = quotes.history(sent.id()).stream()
                .filter(e -> e.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertEquals("Offerte geannuleerd", event.summary());
        opens(token, "GEANNULEERD", "cancelled silently");
        assertEquals("Niet meer leverbaar", given().when().get(PORTAL + token).jsonPath().getString("cancellationMessage"));

        /* Reopened after the cancellation: the old refusal, not a 404; sent again: open. */
        quotes.reopen(sent.id());
        beingUpdated(token, "cancelled then reopened");
        assertEquals(token, quotes.send(sent.id(), null).portalToken());
        opens(token, "BEKEKEN", "cancelled, reopened, resent");
    }

    @Test
    void anUnsentRequestCancelledWithoutNotifySendsNothingAndStoresNoToken() {
        long id = shop.legacyRequests(1).getFirst();

        SalesOrder cancelled = quotes.cancel(id, "Dubbel", false);

        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        assertNull(cancelled.portalToken());
        assertEquals(0, mailbox.getTotalMessagesSent());
        QuoteEvent event = quotes.history(id).stream()
                .filter(e -> e.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertEquals("Offerte geannuleerd", event.summary());
        assertEquals("Dubbel", event.detail());

        /* Reopened and really sent: a fresh token that opens. */
        quotes.reopen(id);
        SalesOrder sent = quotes.send(id, null);
        assertNotNull(sent.portalToken());
        opens(sent.portalToken(), "BEKEKEN", "request reopened and sent");
    }

    @Test
    void aWebsiteOrderSentForApprovalKeepsItsLinkAlsoAfterCancelling() {
        Shop.Placed placed = shop.place("buyer@login.example");
        sales.update(placed.id(), shop.edited(order(placed.id()), 120, "10.00", "140.00"));
        SalesOrder sent = quotes.send(placed.id(), null);
        String token = sent.portalToken();
        assertNotNull(token);
        opens(token, "BEKEKEN", "web order sent for approval");
        mailbox.clear();

        SalesOrder cancelled = quotes.cancel(placed.id(), "Niet leverbaar", true);

        assertEquals(token, cancelled.portalToken());
        List<Mail> mails = mailbox.getMailsSentTo("buyer@login.example");
        assertEquals(1, mails.size());
        opens(token, "GEANNULEERD", "web order cancelled after sending");
        QuoteEvent event = quotes.history(placed.id()).stream()
                .filter(e -> e.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertEquals("Offerte geannuleerd, klant verwittigd op buyer@login.example", event.summary());
    }

    @Test
    void theCancellationMailRendersInAllNineLanguagesWithAndWithoutTheLink() {
        long requestId = shop.legacyRequests(1).getFirst();
        SalesOrder request = order(requestId);
        long customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        SalesOrder sent = sentQuote(customerId, shop.product());
        assertEquals(9, Language.values().length);

        for (Language language : Language.values()) {
            mailbox.clear();
            String to = "lang-" + language.code() + "@record.example";
            Customer customer = new Customer(request.customerId(), "Bloemen " + language.code(), "An Peeters", to,
                    "+32 3 000 00 00", "BE0123456789", "BE", language, "Bloemenlaan 5", "2000", "Antwerpen", "DAP",
                    null, null, null);
            Map<String, String> text = DocumentText.of(language);

            mailer.sendCancellation(request, customer, null, "Reden-zonder-link");
            Mail without = mailbox.getMailsSentTo(to).getFirst();
            assertEquals(text.get("mailSubjectRequestCancelled").formatted(request.number()), without.getSubject(), language.name());
            String html = without.getHtml();
            assertTrue(html.contains("Reden-zonder-link"), language.name());
            assertFalse(html.contains("/offerte/"), language.name());
            assertFalse(html.contains("{"), language.name() + " unrendered template expression");
            assertFalse(html.contains("NOT_FOUND"), language.name());
            assertFalse(html.contains(">null<") || html.contains("\"null\""), language.name() + " prints null");
            assertTrue(html.contains("lang=\"" + language.code() + "\"") || html.contains(language.code()), language.name());
            assertTrue(html.contains(text.get("mailCancelledButtonRequest")), language.name() + " button");

            mailbox.clear();
            Customer other = new Customer(sent.customerId(), "Bloemen " + language.code(), "An Peeters", to,
                    "+32 3 000 00 00", "BE0123456789", "BE", language, "Bloemenlaan 5", "2000", "Antwerpen", "DAP",
                    null, null, null);
            mailer.sendCancellation(sent, other, "https://klant.example/offerte/" + sent.portalToken(), "Reden-met-link");
            Mail with = mailbox.getMailsSentTo(to).getFirst();
            assertEquals(text.get("mailSubjectCancelled").formatted(sent.number()), with.getSubject(), language.name());
            assertTrue(with.getHtml().contains("/offerte/" + sent.portalToken()), language.name());
            assertFalse(with.getHtml().contains("{"), language.name());
        }
    }

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }
}
