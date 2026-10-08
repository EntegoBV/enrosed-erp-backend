package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portal shows prices, so its link is for a party staff sent a quotation
 * to and for nobody else. A website request that staff turn down is told by
 * mail without a link; a link an older cancellation mail carried opens
 * nothing; a quotation that was sent keeps its link when it is cancelled.
 * Against the real tables, the rendered mail in the mock mailbox and the
 * portal over HTTP.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class CancelledRequestPortalLinkTest {
    private static final String PORTAL = "/api/portal/";

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesRepositories.Events events;
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

    @Test
    void aWebsiteRequestThatIsTurnedDownIsMailedWithoutALinkAndGetsNoToken() {
        long id = shop.legacyRequests(1).getFirst();
        SalesOrder request = order(id);
        assertEquals(QuoteStatus.CONCEPT, request.status());
        assertNull(request.sentAt());

        SalesOrder cancelled = quotes.cancel(id, "Wij leveren niet in uw regio", true);

        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        Mail mail = onlyMailTo(shop.recordEmail(request.customerId()));
        assertTrue(mail.getSubject().contains(request.number()), mail.getSubject());
        assertTrue(mail.getHtml().contains("Wij leveren niet in uw regio"), "the visitor still reads why");
        assertFalse(mail.getHtml().contains("/offerte/"), "no portal link for somebody who was never sent a quotation");
        assertFalse(mail.getHtml().contains("10,00") || mail.getHtml().contains("1.320"), "and no price");
        assertNull(entity(id).portalToken, "no token is made");
        assertNull(order(id).portalToken());
        assertTrue(quotes.activePortalUrl(order(id)).isEmpty());
        assertTrue(quotes.history(id).getFirst().summary().contains(shop.recordEmail(request.customerId())),
                "the history says the customer was told");
    }

    @Test
    void aWebsiteOrderOfALoggedInCustomerIsCancelledWithoutALinkToo() {
        Shop.Placed placed = shop.place();

        quotes.cancel(placed.id(), "Niet meer leverbaar", true);

        Mail mail = onlyMailTo(shop.recordEmail(placed.customerId()));
        assertFalse(mail.getHtml().contains("/offerte/"));
        assertNull(entity(placed.id()).portalToken);
    }

    @Test
    void aLinkAnOlderCancellationMailCarriedOpensNothingOnAnyRoute() {
        long id = shop.legacyRequests(1).getFirst();
        quotes.cancel(id, null, false);
        /* What the cancellation mail left behind before this rule: a token on a cancelled document that was never sent. */
        String token = "old-" + UUID.randomUUID();
        shop.inTransaction(() -> {
            entity(id).portalToken = token;
        });
        em.clear();
        SalesOrder stored = order(id);
        assertEquals(QuoteStatus.GEANNULEERD, stored.status());
        assertEquals(token, stored.portalToken());
        assertNull(stored.sentAt());
        long productId = stored.lines().getFirst().productId();

        String unknown = "unknown-" + UUID.randomUUID();
        Map<String, Response> forUnknown = everyRoute(unknown, productId);
        Map<String, Response> forOldLink = everyRoute(token, productId);

        assertEquals(8, forOldLink.size());
        assertTrue(forUnknown.get("GET page").asString().contains(unknown), "the reference is the unknown-link answer");
        forOldLink.forEach((route, answer) -> {
            Response reference = forUnknown.get(route);
            assertEquals(404, reference.statusCode(), route);
            assertEquals(404, answer.statusCode(), route + ": " + answer.asString());
            assertEquals(reference.contentType(), answer.contentType(), route);
            /* The same body, to the letter, but for the token it echoes and the clock. */
            assertEquals(withoutClock(reference.asString()).replace(unknown, token), withoutClock(answer.asString()), route);
            assertFalse(answer.asString().contains(stored.number()), route);
        });
        SalesOrder untouched = order(id);
        assertEquals(0, untouched.viewCount(), "nothing was opened");
        assertNull(untouched.viewedAt());
        assertEquals(QuoteStatus.GEANNULEERD, untouched.status());

        /* Reopened and still not sent, the old link stays just as unknown. */
        quotes.reopen(id);
        assertEquals(QuoteStatus.CONCEPT, order(id).status());
        everyRoute(token, productId).forEach((route, answer) ->
                assertEquals(404, answer.statusCode(), route + ": " + answer.asString()));
    }

    @Test
    void aQuotationThatWasSentKeepsItsLinkWhenItIsCancelled() {
        long customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();
        SalesOrder created = sales.create(customerId, "BE", "DAP");
        sales.update(created.id(), shop.edited(order(created.id()), productId, 120, "10.00", "120.00"));
        SalesOrder sent = quotes.send(created.id(), null);
        String token = sent.portalToken();
        assertNotNull(token);
        assertNotNull(sent.sentAt());
        mailbox.clear();

        SalesOrder cancelled = quotes.cancel(created.id(), "De collectie is uitverkocht", true);

        assertEquals(token, cancelled.portalToken(), "the link the customer holds stays theirs");
        Mail mail = onlyMailTo(shop.recordEmail(customerId));
        assertTrue(mail.getHtml().contains("/offerte/" + token), "the mail carries the customer's own link");

        Response page = given().when().get(PORTAL + token);
        assertEquals(200, page.statusCode(), page.asString());
        assertEquals("GEANNULEERD", page.jsonPath().getString("status"));
        assertEquals(sent.number(), page.jsonPath().getString("number"));
        assertEquals("De collectie is uitverkocht", page.jsonPath().getString("cancellationMessage"));
        assertEquals(1, page.jsonPath().getList("lines").size());
        assertFalse(page.jsonPath().getBoolean("canRespond"));
        Response pdf = given().when().get(PORTAL + token + "/pdf");
        assertEquals(200, pdf.statusCode());
        assertTrue(pdf.contentType().startsWith("application/pdf"), pdf.contentType());
        assertEquals(200, given().when().get(PORTAL + token + "/products").statusCode());
        assertTrue(quotes.activePortalUrl(order(created.id())).isPresent(), "staff can still copy the link");
    }

    @Test
    void aChangeSummaryLongerThanTheHistoryColumnIsCutInsteadOfFailingTheChange() {
        long id = shop.legacyRequests(1).getFirst();
        String summary = "ROSE-0001: 1 → 2 dozen, ".repeat(400);
        assertTrue(summary.length() > 4000);

        QuoteEvent stored = shop.inTransaction(() -> events.add(new QuoteEvent(null, id, QuoteEvent.Type.KLANT_GEWIJZIGD,
                Instant.now(), null, true, "Bestelling gewijzigd door de klant (versie 2)", summary)));

        assertEquals(4000, stored.detail().length());
        em.clear();
        QuoteEvent read = quotes.history(id).stream()
                .filter(event -> event.type() == QuoteEvent.Type.KLANT_GEWIJZIGD).findFirst().orElseThrow();
        assertEquals(summary.substring(0, 4000), read.detail());

        /* A detail that fits is stored as it was written. */
        String fits = "x".repeat(4000);
        assertEquals(fits, shop.inTransaction(() -> events.add(new QuoteEvent(null, id, QuoteEvent.Type.KLANT_GEWIJZIGD,
                Instant.now(), null, true, "Bestelling gewijzigd door de klant (versie 3)", fits))).detail());
    }

    /** Every portal route that returns data or a PDF, or acts on the document. */
    private static Map<String, Response> everyRoute(String token, long productId) {
        Map<String, Response> answers = new LinkedHashMap<>();
        answers.put("GET page", given().when().get(PORTAL + token));
        answers.put("GET pdf", given().when().get(PORTAL + token + "/pdf"));
        answers.put("GET products", given().when().get(PORTAL + token + "/products"));
        answers.put("GET photo", given().when().get(PORTAL + token + "/products/" + productId + "/photo"));
        answers.put("POST accept", given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept"));
        answers.put("POST reject", given().contentType("application/json").body(Map.of("message", "Te duur"))
                .when().post(PORTAL + token + "/reject"));
        answers.put("POST withdraw", given().contentType("application/json").when().post(PORTAL + token + "/withdraw"));
        answers.put("POST propose", given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag meer", "lines", List.of()))
                .when().post(PORTAL + token + "/propose"));
        return answers;
    }

    /** The error body is JSON, or the same map written out where the route produces a PDF. */
    private static String withoutClock(String body) {
        return body.replaceAll("timestamp[^,}]*", "timestamp");
    }

    private Mail onlyMailTo(String address) {
        List<Mail> mails = mailbox.getMailsSentTo(address);
        assertEquals(1, mails.size(), "the cancellation mail still leaves");
        return mails.getFirst();
    }

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private SalesOrderEntity entity(long id) {
        return em.find(SalesOrderEntity.class, id);
    }
}
