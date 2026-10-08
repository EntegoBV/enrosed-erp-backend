package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.RevisionStatus;
import be.enrosed.sales.domain.SalesOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a customer's link answers after staff cancelled the quotation, over
 * HTTP through the real resources and tables.
 *
 * Cancelled as it was sent: the page, the PDF and the mail link stay.
 * Cancelled while staff had it reopened: the document holds edits nobody
 * sent, so every portal route only says that it is cancelled. And no answer
 * of the customer, withdrawing a proposal included, reopens a closed quote.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class CancelledQuotationPortalHttpTest {
    private static final String PORTAL = "/api/portal/";
    private static final String STAFF = "/api/sales-orders/";
    private static final String CANCELLED = "Deze offerte is geannuleerd.";
    private static final String UNSENT_NOTE = "ONVERZONDEN-NOTITIE";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesRepositories.Events events;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    private final Shop shop = new Shop();
    private long customerId;
    private long productId;

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    /* ------------------------------------------------------------ steps */

    /** Sent at 10.00 a piece; the customer holds the token. */
    private SalesOrder sentQuote() {
        customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        productId = shop.product();
        SalesOrder created = sales.create(customerId, "BE", "DAP");
        sales.update(created.id(), shop.edited(order(created.id()), productId, 120, "10.00", "120.00"));
        SalesOrder sent = quotes.send(created.id(), null);
        assertNotNull(sent.portalToken());
        mailbox.clear();
        return sent;
    }

    private void staffReopens(long id) {
        Response reopened = given().contentType("application/json").when().post(STAFF + id + "/reopen");
        assertEquals(200, reopened.statusCode(), reopened.asString());
        assertEquals("CONCEPT", reopened.jsonPath().getString("order.status"));
    }

    private void staffSends(long id) {
        Response sent = given().contentType("application/json").body(Map.of()).when().post(STAFF + id + "/send");
        assertEquals(200, sent.statusCode(), sent.asString());
        mailbox.clear();
    }

    private Response staffCancels(long id, String message, boolean notify) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("notifyCustomer", notify);
        Response cancelled = given().contentType("application/json").body(body).when().post(STAFF + id + "/cancel");
        assertEquals(200, cancelled.statusCode(), cancelled.asString());
        assertEquals("GEANNULEERD", cancelled.jsonPath().getString("order.status"));
        return cancelled;
    }

    /** The order as the staff screen holds it: the "order" part of the view, which is what it PUTs back. */
    private ObjectNode staffOrderJson(long id) throws Exception {
        Response view = given().when().get(STAFF + id);
        assertEquals(200, view.statusCode(), view.asString());
        return (ObjectNode) JSON.readTree(view.asString()).get("order");
    }

    private Response staffPuts(long id, JsonNode order) {
        return given().contentType("application/json").body(order.toString()).when().put(STAFF + id);
    }

    /** Staff edit the reopened draft: another price, an extra discount, a note. None of it is sent. */
    private void staffEditsTheDraft(long id) throws Exception {
        ObjectNode order = staffOrderJson(id);
        ((ObjectNode) order.get("lines").get(0)).put("unitPriceEur", 7.77);
        order.put("extraDiscountPct", 5);
        order.put("extraDiscountLabel", "ONVERZONDEN-KORTING");
        order.put("notes", UNSENT_NOTE);
        Response saved = staffPuts(id, order);
        assertEquals(200, saved.statusCode(), saved.asString());
        assertEquals(UNSENT_NOTE, saved.jsonPath().getString("order.notes"));
        assertEquals(0, new java.math.BigDecimal("7.77").compareTo(
                new java.math.BigDecimal(saved.jsonPath().getString("order.lines[0].unitPriceEur"))));
    }

    /* ------------------------------------------------------- assertions */

    private void refused(Response response, String expected, String step) {
        assertEquals(409, response.statusCode(), step + ": " + response.asString());
        String body = response.asString();
        if (body.startsWith("{\"")) {
            assertEquals(expected, response.jsonPath().getString("message"), step);
            /* The refusal is the whole answer: status, message, timestamp. */
            assertEquals(java.util.Set.of("status", "message", "timestamp"),
                    response.jsonPath().getMap("$").keySet(), step);
        } else {
            /* The photo and PDF routes do not produce JSON: the same three fields arrive as plain text. */
            assertTrue(body.startsWith("{") && body.contains("message=" + expected) && body.contains("status=409"),
                    step + ": " + body);
            assertFalse(response.contentType() != null && response.contentType().startsWith("application/pdf")
                    && body.startsWith("%PDF"), step);
        }
        for (String leak : List.of("7.77", "10.0", "unitPrice", "lines", "totals", "KORTING", UNSENT_NOTE, "%PDF")) {
            if (expected.contains(leak)) continue;
            assertFalse(body.contains(leak), step + " shows " + leak + ": " + body);
        }
    }

    /** Every portal route that returns data, the product list, a photo or the PDF, and the four answers. */
    private void hidden(String token, String expected, String step) {
        Response page = given().when().get(PORTAL + token);
        assertTrue(page.asString().startsWith("{\""), step + " page answers JSON: " + page.asString());
        refused(page, expected, step + " page");
        refused(given().when().get(PORTAL + token + "?language=EN"), expected, step + " page in English");
        refused(given().when().get(PORTAL + token + "/products"), expected, step + " products");
        refused(given().when().get(PORTAL + token + "/products/" + productId + "/photo"), expected, step + " photo");
        refused(given().when().get(PORTAL + token + "/pdf"), expected, step + " pdf");
        refused(given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept"), expected, step + " accept");
        refused(given().contentType("application/json").body(Map.of("message", "Te duur"))
                .when().post(PORTAL + token + "/reject"), expected, step + " reject");
        refused(given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Later graag", "lines", List.of()))
                .when().post(PORTAL + token + "/propose"), expected, step + " propose");
        refused(given().contentType("application/json").when().post(PORTAL + token + "/withdraw"),
                expected, step + " withdraw");
    }

    private Response visible(String token, String step) {
        Response page = given().when().get(PORTAL + token);
        assertEquals(200, page.statusCode(), step + ": " + page.asString());
        assertEquals("GEANNULEERD", page.jsonPath().getString("status"), step);
        assertEquals(1, page.jsonPath().getList("lines").size(), step);
        Response pdf = given().when().get(PORTAL + token + "/pdf");
        assertEquals(200, pdf.statusCode(), step + " pdf");
        assertTrue(pdf.contentType().startsWith("application/pdf"), step);
        assertEquals(200, given().when().get(PORTAL + token + "/products").statusCode(), step + " products");
        return page;
    }

    /** Staff keep their own document: the view with its lines, the PDF, and no link to hand out. */
    private void staffStillSeeTheDocument(long id, String unitPrice) {
        Response view = given().when().get(STAFF + id);
        assertEquals(200, view.statusCode(), view.asString());
        assertEquals("GEANNULEERD", view.jsonPath().getString("order.status"));
        assertEquals(0, new java.math.BigDecimal(unitPrice).compareTo(
                new java.math.BigDecimal(view.jsonPath().getString("order.lines[0].unitPriceEur"))));
        assertNotNull(view.jsonPath().getString("order.sentAt"));
        Response pdf = given().when().get(STAFF + id + "/pdf");
        assertEquals(200, pdf.statusCode());
        assertTrue(pdf.contentType().startsWith("application/pdf"));
        Response link = given().when().get(STAFF + id + "/portal-link");
        assertEquals(200, link.statusCode(), link.asString());
        assertFalse(link.jsonPath().getBoolean("available"), link.asString());
        assertFalse(link.asString().contains("/offerte/"), link.asString());
    }

    private Mail onlyMail() {
        List<Mail> mails = mailbox.getMailsSentTo(shop.recordEmail(customerId));
        assertEquals(1, mails.size());
        return mails.getFirst();
    }

    /* ------------------------------------------------- defect 1: withdraw */

    @Test
    void withdrawingAProposalOnACancelledQuotationIsRefusedAndTheCancellationStays() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        Response proposed = given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag een week later", "lines", List.of()))
                .when().post(PORTAL + token + "/propose");
        assertEquals(200, proposed.statusCode(), proposed.asString());
        assertEquals(QuoteStatus.WIJZIGING_GEVRAAGD, order(id).status());

        staffCancels(id, "Niet meer leverbaar", false);
        Instant decidedAt = order(id).decidedAt();
        assertNotNull(decidedAt);

        Response withdrawn = given().contentType("application/json").when().post(PORTAL + token + "/withdraw");
        assertEquals(409, withdrawn.statusCode(), withdrawn.asString());
        assertEquals("Deze offerte staat niet open voor een reactie (status geannuleerd)",
                withdrawn.jsonPath().getString("message"));

        SalesOrder after = order(id);
        assertEquals(QuoteStatus.GEANNULEERD, after.status());
        assertEquals(decidedAt, after.decidedAt());
        QuoteEvent cancellation = history(id).stream()
                .filter(event -> event.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertEquals("Niet meer leverbaar", cancellation.detail());
        assertTrue(history(id).stream().noneMatch(event -> event.type() == QuoteEvent.Type.VOORSTEL_INGETROKKEN));

        /* The cancel closed the open proposal as not adopted; nothing is left to withdraw or to handle. */
        QuoteRevision proposal = revisions(id).getFirst();
        assertEquals(RevisionStatus.AFGEWEZEN, proposal.status());
        assertNotNull(proposal.handledAt());
        Response handled = given().contentType("application/json").body(Map.of())
                .when().post(STAFF + "revisions/" + proposal.id() + "/reject");
        assertEquals(409, handled.statusCode(), handled.asString());
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());

        /* Cancelled as it was sent: the link still shows it, with what staff wrote, and takes no answer. */
        Response page = visible(token, "cancelled with an open proposal");
        assertEquals("Niet meer leverbaar", page.jsonPath().getString("cancellationMessage"));
        assertFalse(page.jsonPath().getBoolean("canRespond"));
        Response accepted = given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept");
        assertEquals(409, accepted.statusCode(), accepted.asString());
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());
    }

    @Test
    void aProposalLeftOpenByACancelBeforeThisRuleCannotBeWithdrawnEither() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        assertEquals(200, given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag een week later", "lines", List.of()))
                .when().post(PORTAL + token + "/propose").statusCode());
        /* As the old cancel left it: cancelled, the proposal still waiting. */
        Instant decidedAt = Instant.parse("2026-09-01T08:00:00Z");
        shop.inTransaction(() -> {
            SalesOrderEntity row = em.find(SalesOrderEntity.class, id);
            row.status = QuoteStatus.GEANNULEERD;
            row.decidedAt = decidedAt;
            events.add(new QuoteEvent(null, id, QuoteEvent.Type.GEANNULEERD, Instant.now(), "emre", false,
                    "Offerte geannuleerd", "Oude annulering"));
        });
        em.clear();
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions(id).getFirst().status());

        Response withdrawn = given().contentType("application/json").when().post(PORTAL + token + "/withdraw");

        assertEquals(409, withdrawn.statusCode(), withdrawn.asString());
        assertEquals("Deze offerte staat niet open voor een reactie (status geannuleerd)",
                withdrawn.jsonPath().getString("message"));
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());
        assertEquals(decidedAt, order(id).decidedAt());
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions(id).getFirst().status());
    }

    /* --------------------------------------- defect 2: unsent figures */

    @Test
    void aReopenedAndEditedQuotationCancelledWithNotifyShowsNothingAndItsMailHasNoLink() throws Exception {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        staffReopens(id);
        staffEditsTheDraft(id);

        staffCancels(id, "Het product is uit het gamma", true);

        hidden(token, CANCELLED + " Het product is uit het gamma", "edited draft cancelled with notify");
        Mail mail = onlyMail();
        assertEquals("Offerte " + sent.number() + " geannuleerd", mail.getSubject());
        assertTrue(mail.getHtml().contains("Het product is uit het gamma"));
        assertFalse(mail.getHtml().contains("/offerte/"), "the mail hands out no portal link");
        assertFalse(mail.getHtml().contains(token));
        assertFalse(mail.getHtml().contains("7.77") || mail.getHtml().contains("7,77"));
        assertEquals(token, order(id).portalToken(), "the token stays on the document");
        staffStillSeeTheDocument(id, "7.77");
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());
    }

    @Test
    void aReopenedAndEditedQuotationCancelledWithoutNotifyShowsNothingAndNoMailLeaves() throws Exception {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        staffReopens(id);
        staffEditsTheDraft(id);

        staffCancels(id, null, false);

        hidden(token, CANCELLED, "edited draft cancelled silently");
        assertEquals(0, mailbox.getTotalMessagesSent());
        staffStillSeeTheDocument(id, "7.77");
    }

    @Test
    void aReopenedQuotationCancelledWithoutAnyEditIsHiddenTooBecauseItWasAnUnsentDraft() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        staffReopens(id);

        staffCancels(id, "Toch niet", true);

        hidden(sent.portalToken(), CANCELLED + " Toch niet", "untouched draft cancelled");
        assertFalse(onlyMail().getHtml().contains("/offerte/"));
        staffStillSeeTheDocument(id, "10.00");
    }

    @Test
    void anAdoptedProposalThatWasNotSentAgainIsAnUnsentDraftAtTheCancel() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        assertEquals(200, given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag een week later", "lines", List.of()))
                .when().post(PORTAL + token + "/propose").statusCode());
        Response adopted = given().contentType("application/json").body(Map.of())
                .when().post(STAFF + "revisions/" + revisions(id).getFirst().id() + "/approve");
        assertEquals(200, adopted.statusCode(), adopted.asString());
        assertEquals(QuoteStatus.CONCEPT, order(id).status());

        staffCancels(id, null, true);

        hidden(token, CANCELLED, "adopted proposal cancelled before resending");
        assertFalse(onlyMail().getHtml().contains("/offerte/"));
    }

    @Test
    void theRefusalCarriesTheMessageOfTheLastCancel() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        staffCancels(id, "Eerste reden", false);
        assertEquals("Eerste reden", visible(token, "cancelled as sent").jsonPath().getString("cancellationMessage"));
        staffReopens(id);

        staffCancels(id, "Tweede reden", false);

        hidden(token, CANCELLED + " Tweede reden", "cancelled, reopened, cancelled again");
    }

    /* ------------------------------------------------ unchanged behaviour */

    @Test
    void aQuotationCancelledAsItWasSentKeepsItsPageItsPdfAndTheLinkInTheMail() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();

        staffCancels(id, "Niet meer leverbaar", true);

        Response page = visible(token, "cancelled as sent");
        assertEquals("Niet meer leverbaar", page.jsonPath().getString("cancellationMessage"));
        assertEquals(0, new java.math.BigDecimal("10.00").compareTo(
                new java.math.BigDecimal(page.jsonPath().getString("lines[0].unitPrice"))));
        Mail mail = onlyMail();
        assertEquals("Offerte " + sent.number() + " geannuleerd", mail.getSubject());
        assertTrue(mail.getHtml().contains("/offerte/" + token), "the mail keeps the portal link");
        Response link = given().when().get(STAFF + id + "/portal-link");
        assertTrue(link.jsonPath().getBoolean("available"), link.asString());
        assertFalse(quotes.cancelledAsUnsentDraft(order(id)));
    }

    @Test
    void aQuotationReopenedSentAgainAndThenCancelledStaysVisible() throws Exception {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        staffReopens(id);
        staffEditsTheDraft(id);
        staffSends(id);
        assertEquals(token, order(id).portalToken());

        staffCancels(id, null, true);

        Response page = visible(token, "reopened, resent, cancelled");
        assertEquals(UNSENT_NOTE, page.jsonPath().getString("notes"), "these edits were sent");
        assertTrue(onlyMail().getHtml().contains("/offerte/" + token));
    }

    /* --------------------------------------- rows cancelled before the rule */

    @Test
    void aRowCancelledBeforeThisRuleIsReadFromItsHistory() throws Exception {
        /* Reopened after the last sending, edited, cancelled by the old code: only the row and its history say so. */
        SalesOrder draft = sentQuote();
        long draftId = draft.id();
        staffReopens(draftId);
        staffEditsTheDraft(draftId);
        cancelledByTheOldCode(draftId, "Oude reden");
        assertTrue(quotes.cancelledAsUnsentDraft(order(draftId)));
        hidden(draft.portalToken(), CANCELLED + " Oude reden", "old row, reopened after the last sending");
        staffStillSeeTheDocument(draftId, "7.77");

        /* Cancelled as it was sent by the old code: the history shows no reopening after the sending. */
        long firstCustomer = customerId;
        SalesOrder plain = sentQuote();
        cancelledByTheOldCode(plain.id(), null);
        assertFalse(quotes.cancelledAsUnsentDraft(order(plain.id())));
        visible(plain.portalToken(), "old row, cancelled as sent");
        assertTrue(firstCustomer != customerId);
    }

    /** The row and the event exactly as the cancel wrote them before this rule. */
    private void cancelledByTheOldCode(long id, String message) {
        shop.inTransaction(() -> {
            SalesOrderEntity row = em.find(SalesOrderEntity.class, id);
            row.status = QuoteStatus.GEANNULEERD;
            row.decidedAt = Instant.now();
            events.add(new QuoteEvent(null, id, QuoteEvent.Type.GEANNULEERD, Instant.now(), "emre", false,
                    "Offerte geannuleerd", message));
        });
        em.clear();
    }

    /* ------------------------------------------- the staff screen's round trip */

    @Test
    void theStaffScreenSavesAReopenedQuotationUnchangedAndTheCustomersLinkOpensAfterTheSending() throws Exception {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        assertEquals(200, given().when().get(PORTAL + token).statusCode());
        staffReopens(id);
        assertEquals(409, given().when().get(PORTAL + token).statusCode());

        ObjectNode asTheScreenHoldsIt = staffOrderJson(id);
        Response saved = staffPuts(id, asTheScreenHoldsIt);

        assertEquals(200, saved.statusCode(), saved.asString());
        assertEquals(token, saved.jsonPath().getString("order.portalToken"));
        assertNotNull(saved.jsonPath().getString("order.sentAt"));
        assertEquals("CONCEPT", saved.jsonPath().getString("order.status"));
        assertEquals(409, given().when().get(PORTAL + token).statusCode(), "still a draft");
        staffSends(id);
        SalesOrder resent = order(id);
        assertEquals(token, resent.portalToken());
        Response page = given().when().get(PORTAL + token);
        assertEquals(200, page.statusCode(), page.asString());
        assertEquals("BEKEKEN", page.jsonPath().getString("status"));
        assertEquals(1, page.jsonPath().getList("lines").size());
        assertEquals(200, given().when().get(PORTAL + token + "/pdf").statusCode());
    }

    /* ---------------------------------------------------------- helpers */

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private List<QuoteEvent> history(long id) {
        return shop.inTransaction(() -> quotes.history(id));
    }

    private List<QuoteRevision> revisions(long id) {
        return shop.inTransaction(() -> quotes.revisionsFor(id));
    }
}
