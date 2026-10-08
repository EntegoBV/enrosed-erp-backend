package be.enrosed.sales.application;

import be.enrosed.account.CustomerAccountService;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.RevisionStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;
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
import java.util.UUID;

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
 * sent, so every portal route only says that it is cancelled, and the
 * customer's account shows none of it either. And no answer of the customer,
 * withdrawing a proposal included, and no handling of a proposal by staff
 * reopens a closed quote.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class CancelledQuotationPortalHttpTest {
    private static final String PORTAL = "/api/portal/";
    private static final String STAFF = "/api/sales-orders/";
    private static final String CANCELLED = "Deze offerte is geannuleerd.";
    private static final String ACCOUNT = "/api/v1/public/account/documents";
    private static final String UNSENT_NOTE = "ONVERZONDEN-NOTITIE";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesRepositories.Events events;
    @Inject MockMailbox mailbox;
    @Inject CustomerAccountService accounts;
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
        staffEditsTheDraft(id, true);
    }

    /** On a website order the note is the customer's own and read-only for staff: there only price and discount change. */
    private void staffEditsTheDraft(long id, boolean note) throws Exception {
        ObjectNode order = staffOrderJson(id);
        ((ObjectNode) order.get("lines").get(0)).put("unitPriceEur", 7.77);
        order.put("extraDiscountPct", 5);
        order.put("extraDiscountLabel", "ONVERZONDEN-KORTING");
        if (note) order.put("notes", UNSENT_NOTE);
        Response saved = staffPuts(id, order);
        assertEquals(200, saved.statusCode(), saved.asString());
        if (note) assertEquals(UNSENT_NOTE, saved.jsonPath().getString("order.notes"));
        assertEquals(0, new java.math.BigDecimal("7.77").compareTo(
                new java.math.BigDecimal(saved.jsonPath().getString("order.lines[0].unitPriceEur"))));
    }

    /* ------------------------------------------------------- assertions */

    private void refused(Response response, String expected, String step) {
        assertEquals(409, response.statusCode(), step + ": " + response.asString());
        String body = response.asString();
        /* On the photo and PDF routes too: JSON that says it is JSON, never a printed map under the type of a file. */
        assertTrue(response.contentType() != null && response.contentType().startsWith("application/json"),
                step + " answers " + response.contentType() + ": " + body);
        assertEquals(expected, response.jsonPath().getString("message"), step);
        /* The refusal is the whole answer: status, message, timestamp. */
        assertEquals(java.util.Set.of("status", "message", "timestamp"),
                response.jsonPath().getMap("$").keySet(), step);
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
        refused(given().accept("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .when().get(PORTAL + token + "/pdf"), expected, step + " pdf opened in a browser tab");
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
        refused(given().when().get(PORTAL + token + "/pdf"),
                "Deze offerte wordt momenteel bijgewerkt. De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.",
                "the PDF of a draft");
        staffSends(id);
        SalesOrder resent = order(id);
        assertEquals(token, resent.portalToken());
        Response page = given().when().get(PORTAL + token);
        assertEquals(200, page.statusCode(), page.asString());
        assertEquals("BEKEKEN", page.jsonPath().getString("status"));
        assertEquals(1, page.jsonPath().getList("lines").size());
        assertEquals(200, given().when().get(PORTAL + token + "/pdf").statusCode());
    }

    /* ------------------------------------- the logged-in customer's account */

    /** A second login of the customer, activated, with one open session. */
    private String session(long customer) {
        var grant = accounts.grant(customer, "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com",
                "An Peeters", Language.NL);
        return accounts.activate(grant.rawToken(), "roses-in-a-dome").sessionToken();
    }

    private static Response account(String session, String path) {
        return given().header("Authorization", "Bearer " + session).when().get(ACCOUNT + path);
    }

    private static void noUnsentFigure(Response response, String step) {
        String body = response.asString();
        for (String leak : List.of("7.77", "7,77", "KORTING", UNSENT_NOTE, "%PDF"))
            assertFalse(body.contains(leak), step + " shows " + leak + ": " + body);
    }

    @Test
    void theAccountShowsNothingOfAQuotationCancelledAsAnUnsentDraft() throws Exception {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String session = session(customerId);
        Response before = account(session, "?kind=ORDERS");
        assertEquals(200, before.statusCode(), before.asString());
        assertEquals(1, before.jsonPath().getList("items").size(), before.asString());
        assertEquals(1320.0f, before.jsonPath().getFloat("items[0].totalExclVat"));
        assertEquals(200, account(session, "/" + id).statusCode());
        assertEquals(200, account(session, "/" + id + "/pdf").statusCode());
        staffReopens(id);
        staffEditsTheDraft(id);

        staffCancels(id, "Uit het gamma", false);

        assertTrue(quotes.cancelledAsUnsentDraft(order(id)));
        Response list = account(session, "?kind=ORDERS");
        assertEquals(200, list.statusCode(), list.asString());
        assertEquals(0, list.jsonPath().getList("items").size(), list.asString());
        noUnsentFigure(list, "list");
        Response detail = account(session, "/" + id);
        assertEquals(404, detail.statusCode(), detail.asString());
        noUnsentFigure(detail, "detail");
        Response pdf = account(session, "/" + id + "/pdf");
        assertEquals(404, pdf.statusCode(), pdf.asString());
        noUnsentFigure(pdf, "pdf");

        /* Reopened and cancelled once more: still nothing. */
        staffReopens(id);
        staffCancels(id, null, false);
        assertEquals(0, account(session, "?kind=ORDERS").jsonPath().getList("items").size());
        assertEquals(404, account(session, "/" + id).statusCode());
        assertEquals(404, account(session, "/" + id + "/pdf").statusCode());
    }

    @Test
    void theAccountKeepsAQuotationCancelledAsItWasSent() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String session = session(customerId);

        staffCancels(id, "Niet meer leverbaar", false);

        Response list = account(session, "?kind=ORDERS");
        assertEquals(1, list.jsonPath().getList("items").size(), list.asString());
        assertEquals("CANCELLED", list.jsonPath().getString("items[0].status"));
        assertEquals(1320.0f, list.jsonPath().getFloat("items[0].totalExclVat"));
        assertTrue(list.jsonPath().getBoolean("items[0].hasPdf"));
        Response detail = account(session, "/" + id);
        assertEquals(200, detail.statusCode(), detail.asString());
        assertEquals("CURRENT", detail.jsonPath().getString("basis"));
        assertEquals("Niet meer leverbaar", detail.jsonPath().getString("cancellationMessage"));
        assertEquals(10.0f, detail.jsonPath().getFloat("lines[0].unitPrice"));
        Response pdf = account(session, "/" + id + "/pdf");
        assertEquals(200, pdf.statusCode());
        assertTrue(pdf.contentType().startsWith("application/pdf"));
    }

    @Test
    void theAccountShowsAWebsiteOrderCancelledAsAnUnsentDraftAsItWasOrderedAndWithoutAPdf() throws Exception {
        Shop.Placed placed = shop.place();
        long id = placed.id();
        productId = placed.productId();
        String session = session(placed.customerId());
        shop.inTransaction(() -> quotes.send(id, null));
        Response sentDetail = account(session, "/" + id);
        assertEquals(200, sentDetail.statusCode(), sentDetail.asString());
        assertEquals("CURRENT", sentDetail.jsonPath().getString("basis"));
        assertTrue(sentDetail.jsonPath().getBoolean("hasPdf"));
        staffReopens(id);
        staffEditsTheDraft(id, false);

        staffCancels(id, "Uit het gamma", false);

        assertTrue(quotes.cancelledAsUnsentDraft(order(id)));
        Response list = account(session, "?kind=ORDERS");
        assertEquals(200, list.statusCode(), list.asString());
        assertEquals(1, list.jsonPath().getList("items").size(), list.asString());
        assertEquals("ORDER", list.jsonPath().getString("items[0].kind"));
        assertEquals("CANCELLED", list.jsonPath().getString("items[0].status"));
        assertEquals(1320.0f, list.jsonPath().getFloat("items[0].totalExclVat"), "the total the customer ordered");
        assertFalse(list.jsonPath().getBoolean("items[0].hasPdf"));
        noUnsentFigure(list, "list");
        Response detail = account(session, "/" + id);
        assertEquals(200, detail.statusCode(), detail.asString());
        assertEquals("CANCELLED", detail.jsonPath().getString("status"));
        assertEquals("AS_ORDERED", detail.jsonPath().getString("basis"));
        assertEquals("Uit het gamma", detail.jsonPath().getString("cancellationMessage"));
        assertEquals(10.0f, detail.jsonPath().getFloat("lines[0].unitPrice"));
        assertEquals(1320.0f, detail.jsonPath().getFloat("totals.totalExclVat"));
        assertEquals("Graag voor donderdag", detail.jsonPath().getString("notes"), "the customer's own note");
        assertFalse(detail.jsonPath().getBoolean("hasPdf"));
        noUnsentFigure(detail, "detail");
        Response pdf = account(session, "/" + id + "/pdf");
        assertEquals(404, pdf.statusCode(), pdf.asString());
        noUnsentFigure(pdf, "pdf");
    }

    @Test
    void aQuotationRelinkedWhileReopenedAndThenCancelledIsNotInTheOtherCustomersAccount() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        long other = shop.customer("Rozen Janssens NV", "Rozenstraat 1", "9000", "Gent");
        String session = session(other);
        staffReopens(id);
        sales.update(id, shop.relinked(order(id), other));
        assertEquals(other, order(id).customerId());

        staffCancels(id, null, false);

        assertTrue(quotes.cancelledAsUnsentDraft(order(id)));
        Response list = account(session, "?kind=ORDERS");
        assertEquals(200, list.statusCode(), list.asString());
        assertEquals(0, list.jsonPath().getList("items").size(), list.asString());
        assertEquals(404, account(session, "/" + id).statusCode());
        assertEquals(404, account(session, "/" + id + "/pdf").statusCode());
    }

    /* --------------------------- staff handle a proposal on a closed quote */

    private long proposalOn(long id, String token) {
        Response proposed = given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag een week later", "lines", List.of()))
                .when().post(PORTAL + token + "/propose");
        assertEquals(200, proposed.statusCode(), proposed.asString());
        return revisions(id).getFirst().id();
    }

    private Response staffHandles(long proposalId, String action) {
        return given().contentType("application/json").body(Map.of()).when()
                .post(STAFF + "revisions/" + proposalId + "/" + action);
    }

    /** As the cancel of before this rule left it: cancelled, the proposal still waiting. */
    private void cancelledByTheOldCodeAt(long id, Instant decidedAt) {
        shop.inTransaction(() -> {
            SalesOrderEntity row = em.find(SalesOrderEntity.class, id);
            row.status = QuoteStatus.GEANNULEERD;
            row.decidedAt = decidedAt;
            events.add(new QuoteEvent(null, id, QuoteEvent.Type.GEANNULEERD, Instant.now(), "emre", false,
                    "Offerte geannuleerd", "Oude annulering"));
        });
        em.clear();
    }

    @Test
    void staffRejectingAProposalThatOutlivedACancelClosesItAndTheQuotationStaysCancelled() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        long proposalId = proposalOn(id, token);
        Instant decidedAt = Instant.parse("2026-09-01T08:00:00Z");
        cancelledByTheOldCodeAt(id, decidedAt);
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions(id).getFirst().status());

        Response rejected = staffHandles(proposalId, "reject");

        assertEquals(200, rejected.statusCode(), rejected.asString());
        assertEquals(RevisionStatus.AFGEWEZEN, revisions(id).getFirst().status());
        SalesOrder after = order(id);
        assertEquals(QuoteStatus.GEANNULEERD, after.status());
        assertEquals(decidedAt, after.decidedAt());
        Response page = visible(token, "old row after the proposal was closed");
        assertFalse(page.jsonPath().getBoolean("canRespond"));
        assertEquals(409, given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept").statusCode());
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());
        /* With the proposal closed staff can reopen it themselves, which is the one way back. */
        staffReopens(id);
    }

    @Test
    void staffCannotAdoptAProposalOnACancelledQuotation() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        long proposalId = proposalOn(id, sent.portalToken());
        Instant decidedAt = Instant.parse("2026-09-01T08:00:00Z");
        cancelledByTheOldCodeAt(id, decidedAt);

        Response adopted = staffHandles(proposalId, "approve");

        assertEquals(409, adopted.statusCode(), adopted.asString());
        assertEquals("Offerte " + sent.number() + " staat op geannuleerd; dit voorstel kan niet meer overgenomen worden."
                + " Wijs het af, de offerte blijft dan zoals ze is.", adopted.jsonPath().getString("message"));
        assertEquals(QuoteStatus.GEANNULEERD, order(id).status());
        assertEquals(decidedAt, order(id).decidedAt());
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions(id).getFirst().status());
        assertTrue(history(id).stream().noneMatch(event -> event.type() == QuoteEvent.Type.VOORSTEL_OVERGENOMEN));
    }

    @Test
    void handlingAProposalTheCustomerLeftOpenWhenSigningKeepsTheSignature() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        String token = sent.portalToken();
        long proposalId = proposalOn(id, token);
        Response accepted = given().contentType("application/json").body(Map.of("signedByName", "An Peeters"))
                .when().post(PORTAL + token + "/accept");
        assertEquals(200, accepted.statusCode(), accepted.asString());
        SalesOrder signed = order(id);
        assertEquals(QuoteStatus.GEACCEPTEERD, signed.status());
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions(id).getFirst().status());

        assertEquals(409, staffHandles(proposalId, "approve").statusCode());
        Response rejected = staffHandles(proposalId, "reject");

        assertEquals(200, rejected.statusCode(), rejected.asString());
        assertEquals(RevisionStatus.AFGEWEZEN, revisions(id).getFirst().status());
        SalesOrder after = order(id);
        assertEquals(QuoteStatus.GEACCEPTEERD, after.status());
        assertEquals("An Peeters", after.signedByName());
        assertEquals(signed.decidedAt(), after.decidedAt());
    }

    @Test
    void rejectingAProposalOnAQuotationThatWaitsOnItStillPutsItBackOnSent() {
        SalesOrder sent = sentQuote();
        long id = sent.id();
        long proposalId = proposalOn(id, sent.portalToken());
        assertEquals(QuoteStatus.WIJZIGING_GEVRAAGD, order(id).status());

        Response rejected = staffHandles(proposalId, "reject");

        assertEquals(200, rejected.statusCode(), rejected.asString());
        assertEquals(QuoteStatus.VERZONDEN, order(id).status());
        assertEquals(RevisionStatus.AFGEWEZEN, revisions(id).getFirst().status());
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
