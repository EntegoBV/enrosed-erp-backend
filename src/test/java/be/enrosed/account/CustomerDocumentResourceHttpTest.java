package be.enrosed.account;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.domain.CreditReason;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * "My orders" of a logged-in customer through the real HTTP pipeline: the session guard,
 * the switch, the budgets per account, the one answer for everything that is not the
 * customer's to see, and a PDF that says nothing about payments. Documents are made by the
 * real services; only the per-network bucket is stubbed.
 */
@QuarkusTest
class CustomerDocumentResourceHttpTest {
    private static final String BASE = "/api/v1/public/account/documents";
    private static final String PASSWORD = "roses-in-a-dome";
    private static final String SWITCH = "enrosed.website.ordering.enabled";
    private static final String SESSION_INVALID = "{\"code\":\"SESSION_INVALID\","
            + "\"message\":\"The session is no longer valid\",\"fieldErrors\":{}}";
    private static final String ORDER_NOT_FOUND = "{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"Not found\",\"fieldErrors\":{}}";
    private static final String NOT_FOUND = "{\"code\":\"NOT_FOUND\",\"message\":\"Not found\",\"fieldErrors\":{}}";
    private static final String UNAVAILABLE = "{\"code\":\"DOCUMENT_UNAVAILABLE\","
            + "\"message\":\"The document cannot be shown right now\",\"fieldErrors\":{}}";

    @Inject CustomerAccountService accounts;
    @Inject QuoteService quotes;
    @Inject IncomingPaymentService payments;
    @Inject ObjectMapper json;
    @InjectSpy SalesOrderService sales;
    @InjectSpy PublicFormRateLimiter rateLimiter;

    private final Shop shop = new Shop();

    @BeforeEach
    void openTheNetworkBucket() {
        doNothing().when(rateLimiter).checkIp(any(), any());
    }

    @AfterEach
    void removeRows() {
        System.clearProperty(SWITCH);
        shop.remove();
    }

    @Test
    void aSessionReadsItsOrdersTheDetailAndThePrefillAndNothingInternalLeaves() throws Exception {
        Shop.Placed order = shop.place();
        String token = session(order.customerId());

        String defaults = get(token, "/delivery-defaults").then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("source", equalTo("LAST_ORDER"))
                .body("fulfillment", equalTo("DELIVERY"))
                .body("pickupLocationId", nullValue())
                .body("destination.countryCode", equalTo("BE"))
                .body("destination.postalCode", equalTo("3980"))
                .body("destination.city", equalTo("Tessenderlo"))
                .body("destination.address", equalTo("Industrieweg 1"))
                .body("contactName", equalTo("Jan Besteller"))
                .body("phone", equalTo("+32 13 00 00 00"))
                .extract().asString();

        String list = get(token, "?kind=ORDERS").then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("items", hasSize(1))
                .body("items[0].id", equalTo((int) order.id()))
                .body("items[0].kind", equalTo("ORDER"))
                .body("items[0].number", equalTo(order.number()))
                .body("items[0].date", equalTo(LocalDate.now().toString()))
                .body("items[0].status", equalTo("RECEIVED"))
                .body("items[0].cancelledBy", nullValue())
                .body("items[0].totalExclVat", equalTo(1320.0f))
                .body("items[0].totalInclVat", equalTo(1597.2f))
                .body("items[0].currency", equalTo("EUR"))
                .body("items[0].validUntil", nullValue())
                .body("items[0].canChange", equalTo(true))
                .body("items[0].canCancel", equalTo(true))
                .body("items[0].hasDetail", equalTo(true))
                .body("items[0].hasPdf", equalTo(false))
                .body("items[0].relatedNumber", nullValue())
                .body("nextCursor", nullValue())
                .extract().asString();
        String invoices = get(token, "?kind=invoices&limit=50").then().statusCode(200)
                .body("items", empty()).body("nextCursor", nullValue()).extract().asString();

        String detail = get(token, "/" + order.id()).then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("id", equalTo((int) order.id()))
                .body("kind", equalTo("ORDER"))
                .body("status", equalTo("RECEIVED"))
                .body("cancellationMessage", nullValue())
                .body("revision", equalTo(1))
                .body("canChange", equalTo(true))
                .body("hasPdf", equalTo(false))
                .body("basis", equalTo("AS_ORDERED"))
                .body("basisDate", equalTo(LocalDate.now().toString()))
                .body("fulfillment", equalTo("DELIVERY"))
                .body("pickupLocation", nullValue())
                .body("destination.countryCode", equalTo("BE"))
                .body("destination.address", equalTo("Industrieweg 1"))
                .body("contactName", equalTo("Jan Besteller"))
                .body("notes", equalTo("Graag voor donderdag"))
                .body("lines", hasSize(1))
                .body("lines[0].productId", equalTo((int) order.productId()))
                .body("lines[0].sku", equalTo(order.sku()))
                .body("lines[0].cartons", equalTo(10))
                .body("lines[0].piecesPerCarton", equalTo(12))
                .body("lines[0].quantity", equalTo(120))
                .body("lines[0].unitPrice", equalTo(10.0f))
                .body("lines[0].net", equalTo(1200.0f))
                .body("extraLines", empty())
                .body("totals.goods", equalTo(1200.0f))
                .body("totals.extras", nullValue())
                .body("totals.shipping", equalTo(120.0f))
                .body("totals.shippingStatus", equalTo("CALCULATED"))
                .body("totals.totalExclVat", equalTo(1320.0f))
                .body("totals.vatRatePct", equalTo(21.0f))
                .body("totals.totalInclVat", equalTo(1597.2f))
                .body("totals.vatTreatment", notNullValue())
                .extract().asString();

        for (String answer : List.of(defaults, list, invoices, detail)) assertNothingInternal(answer);
        assertFalse(detail.contains("WEBSITE_AANVRAAG") || detail.contains("klantlogin"), "the internal note stays inside");

        get(token, "").then().statusCode(422).header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR")).body("fieldErrors.kind", equalTo("INVALID"));
        get(token, "?kind=QUOTES&cursor=abc&limit=51").then().statusCode(422)
                .body("fieldErrors.kind", equalTo("INVALID"))
                .body("fieldErrors.cursor", equalTo("INVALID"))
                .body("fieldErrors.limit", equalTo("INVALID"));
        get(token, "?kind=ORDERS&limit=0").then().statusCode(422).body("fieldErrors.limit", equalTo("INVALID"));
        get(token, "?kind=ORDERS&cursor=" + order.id()).then().statusCode(200).body("items", empty());
    }

    @Test
    void everythingThatIsNotTheCustomersToSeeAnswersTheSameNotFound() {
        Shop.Placed order = shop.place();
        String token = session(order.customerId());
        Shop.Placed foreign = shop.place();
        quotes.send(foreign.id(), null);
        SalesOrder draftQuote = sales.create(order.customerId(), "BE", "DAP");
        Shop.Placed invoiced = shop.place();
        String invoicedToken = session(invoiced.customerId());
        SalesOrder draftInvoice = sales.createInvoiceFrom(invoiced.id());

        List<String> answers = new ArrayList<>();
        for (String path : List.of("/987654321", "/" + foreign.id(), "/" + draftQuote.id(),
                "/987654321/pdf", "/" + foreign.id() + "/pdf", "/" + draftQuote.id() + "/pdf",
                "/" + order.id() + "/pdf"))
            answers.add(get(token, path).then().statusCode(404).header("Cache-Control", "no-store")
                    .contentType("application/json").extract().asString());
        for (String path : List.of("/" + draftInvoice.id(), "/" + draftInvoice.id() + "/pdf"))
            answers.add(get(invoicedToken, path).then().statusCode(404).extract().asString());
        SalesOrder issued = sales.issueInvoice(draftInvoice.id());
        answers.add(get(invoicedToken, "/" + issued.id()).then().statusCode(404).extract().asString());

        for (String answer : answers) assertEquals(ORDER_NOT_FOUND, answer, "byte for byte the same answer");
        get(invoicedToken, "/" + issued.id() + "/pdf").then().statusCode(200);
    }

    @Test
    void withoutASessionNothingAnswersAndTheSwitchClosesEveryRoute() {
        Shop.Placed order = shop.place();
        String token = session(order.customerId());
        List<String> routes = List.of("/delivery-defaults", "?kind=ORDERS", "/" + order.id(), "/" + order.id() + "/pdf");

        for (String route : routes) {
            assertEquals(SESSION_INVALID, given().when().get(BASE + route).then().statusCode(401)
                    .header("Cache-Control", "no-store").extract().asString(), route);
            assertEquals(SESSION_INVALID, given().header("Authorization", "Bearer ecs1_" + "A".repeat(43))
                    .when().get(BASE + route).then().statusCode(401).extract().asString(), route);
        }

        System.setProperty(SWITCH, "false");
        for (String route : routes) {
            assertEquals(NOT_FOUND, get(token, route).then().statusCode(404).header("Cache-Control", "no-store")
                    .contentType("application/json").extract().asString(), route);
            given().when().get(BASE + route).then().statusCode(401);
        }

        System.clearProperty(SWITCH);
        get(token, "/delivery-defaults").then().statusCode(200).body("source", equalTo("LAST_ORDER"));
    }

    @Test
    void theHistoryHasItsBudgetPerAccountAndNeverStarvesThePrefill() {
        long customerId = shop.customer("Veellezer BV", null, null, null);
        String token = session(customerId);
        String other = session(shop.customer("Andere lezer BV", null, null, null));

        for (int read = 0; read < 240; read++) get(token, "?kind=INVOICES").then().statusCode(200);
        get(token, "?kind=INVOICES").then().statusCode(429)
                .header("Cache-Control", "no-store")
                .header("Retry-After", notNullValue())
                .body("code", equalTo("RATE_LIMITED"));
        get(token, "/1").then().statusCode(429);

        get(token, "/delivery-defaults").then().statusCode(200).body("source", equalTo("NONE"))
                .body("destination", nullValue());
        get(other, "?kind=INVOICES").then().statusCode(200);
    }

    @Test
    void aSentVersionDownloadsWithoutTheSigningLink() throws Exception {
        Shop.Placed order = shop.place();
        String token = session(order.customerId());
        quotes.send(order.id(), null);
        SalesOrder sent = shop.inTransaction(() -> sales.get(order.id()));

        get(token, "/" + order.id()).then().statusCode(200)
                .body("status", equalTo("AWAITING_APPROVAL"))
                .body("basis", equalTo("CURRENT"))
                .body("hasPdf", equalTo(true));
        Response response = given().header("Authorization", "Bearer " + token).accept("application/pdf")
                .when().get(BASE + "/" + order.id() + "/pdf");
        response.then().statusCode(200)
                .contentType("application/pdf")
                .header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"" + order.number() + ".pdf\"");
        byte[] download = response.asByteArray();
        String account = text(download);
        String staff = text(shop.inTransaction(() -> quotes.document(order.id())).content());

        assertTrue(staff.contains(sent.portalToken()), "the staff copy carries the link, so this test would notice one");
        assertFalse(account.contains(sent.portalToken()) || account.contains("/offerte/"), account);
        assertFalse(new String(download, StandardCharsets.ISO_8859_1).contains(sent.portalToken()), "not in a link either");
        assertTrue(account.contains(order.number()), account);
        for (String line : account.lines().map(String::strip).filter(line -> !line.isBlank()).toList())
            assertTrue(staff.contains(line), "the same document as staff download, without the link: " + line);

        verify(rateLimiter).checkIp(eq(PublicFormAction.ACCOUNT_ORDER_PDF), any());
        SalesOrder after = shop.inTransaction(() -> sales.get(order.id()));
        assertEquals(QuoteStatus.VERZONDEN, after.status());
        assertEquals(0, after.viewCount(), "a download from the account is not a view of the portal");

        String english = text(get(token, "/" + order.id() + "/pdf?language=en").then().statusCode(200)
                .contentType("application/pdf").extract().asByteArray());
        assertFalse(english.equals(account), "the language asked for");
        get(token, "/" + order.id() + "/pdf?language=xx").then().statusCode(422)
                .contentType("application/json")
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.language", equalTo("UNSUPPORTED"));

        doThrow(new PublicFormRateLimitException(90)).when(rateLimiter)
                .checkKey(eq(PublicFormAction.ACCOUNT_ORDER_PDF), any(), any(), anyInt());
        get(token, "/" + order.id() + "/pdf").then().statusCode(429)
                .contentType("application/json")
                .header("Retry-After", "90")
                .header("Cache-Control", "no-store")
                .body("code", equalTo("RATE_LIMITED"));
    }

    @Test
    void theDownloadOfAnInvoiceOrCreditNoteSaysNothingAboutWhatWasPaid() throws Exception {
        Shop.Placed order = shop.place();
        String token = session(order.customerId());
        SalesOrder invoice = sales.issueInvoice(sales.createInvoiceFrom(order.id()).id());
        payments.add(invoice.id(), new IncomingPaymentService.Request(new BigDecimal("1000.00"), Instant.now(),
                "Europe/Brussels", "Eerste storting"));
        SalesOrder credit = sales.issueInvoice(sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.PRICE_CORRECTION, List.of(),
                List.of(new SalesOrderService.CreditAmount("Correctie", BigDecimal.TEN)), false, null)).id());
        payments.applyCredit(credit.id(), invoice.id(), new BigDecimal("12.10"));
        payments.add(invoice.id(), new IncomingPaymentService.Request(new BigDecimal("585.10"), Instant.now(),
                "Europe/Brussels", "Saldo"));
        assertEquals(QuoteStatus.BETAALD, shop.inTransaction(() -> sales.get(invoice.id())).status());

        String list = get(token, "?kind=INVOICES").then().statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].kind", equalTo("CREDIT_NOTE"))
                .body("items[0].status", equalTo("ISSUED"))
                .body("items[0].relatedNumber", equalTo(invoice.number()))
                .body("items[0].hasPdf", equalTo(true))
                .body("items[0].hasDetail", equalTo(false))
                .body("items[1].kind", equalTo("INVOICE"))
                .body("items[1].status", equalTo("ISSUED"))
                .body("items[1].totalInclVat", equalTo(1597.2f))
                .body("items[1].relatedNumber", equalTo(order.number()))
                .extract().asString();
        assertNothingInternal(list);
        get(token, "?kind=ORDERS").then().statusCode(200)
                .body("items[0].status", equalTo("CONFIRMED"))
                .body("items[0].relatedNumber", equalTo(invoice.number()));

        String staffInvoice = text(shop.inTransaction(() -> quotes.document(invoice.id())).content());
        String staffCredit = text(shop.inTransaction(() -> quotes.document(credit.id())).content());
        assertTrue(staffInvoice.contains("Volledig voldaan"), "the staff copy tells the payment state: " + staffInvoice);
        assertTrue(staffCredit.contains("Verrekend met factuur"), staffCredit);

        Response download = get(token, "/" + invoice.id() + "/pdf");
        download.then().statusCode(200).contentType("application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + invoice.number() + ".pdf\"");
        String accountInvoice = text(download.asByteArray());
        String accountCredit = text(get(token, "/" + credit.id() + "/pdf").then().statusCode(200).extract().asByteArray());
        for (String copy : List.of(accountInvoice, accountCredit)) {
            for (String told : List.of("Ontvangen", "Terugbetaald", "Volledig voldaan", "Te veel ontvangen", "Tegoed voor de klant",
                    "Verrekend met factuur", "Openstaand tegoed", "Volledig afgehandeld", "1.000,00", "585,10"))
                assertFalse(copy.contains(told), "'" + told + "' in the customer's copy: " + copy);
        }
        assertTrue(accountInvoice.contains("1.597,20"), "the invoice amount as issued: " + accountInvoice);
        assertTrue(accountInvoice.contains(invoice.number()));
        assertTrue(accountCredit.contains(invoice.number()), "the invoice the credit note corrects");
    }

    @Test
    void aDocumentThatCannotBePricedIsListedWithoutTotalsAndIsUnavailableOnItsOwn() {
        Shop.Placed broken = shop.place();
        String token = session(broken.customerId());
        quotes.send(broken.id(), null);
        SalesOrder fine = sales.create(broken.customerId(), "BE", "DAP");
        sales.update(fine.id(), shop.edited(shop.inTransaction(() -> sales.get(fine.id())), broken.productId(), 120,
                "10.00", "120.00"));
        quotes.send(fine.id(), null);

        doThrow(new BusinessRuleException("Deel 2 van de gesplitste levering ontbreekt")).when(sales).priceAll(any());
        doThrow(new BusinessRuleException("Deel 2 van de gesplitste levering ontbreekt")).when(sales)
                .price(argThat(order -> order != null && order.id() != null && order.id() == broken.id()));

        String list = get(token, "?kind=ORDERS").then().statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].id", equalTo((int) (long) fine.id()))
                .body("items[0].totalInclVat", equalTo(1597.2f))
                .body("items[1].id", equalTo((int) broken.id()))
                .body("items[1].status", equalTo("AWAITING_APPROVAL"))
                .body("items[1].totalExclVat", nullValue())
                .body("items[1].totalInclVat", nullValue())
                .extract().asString();
        assertFalse(list.contains("gesplitste"), list);

        assertEquals(UNAVAILABLE, get(token, "/" + broken.id()).then().statusCode(409)
                .header("Cache-Control", "no-store").contentType("application/json").extract().asString());
        assertEquals(UNAVAILABLE, get(token, "/" + broken.id() + "/pdf").then().statusCode(409)
                .header("Cache-Control", "no-store").contentType("application/json").extract().asString());
        get(token, "/" + fine.id()).then().statusCode(200).body("totals.totalInclVat", equalTo(1597.2f));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** A second login of the customer, activated, with one open session. */
    private String session(long customerId) {
        var grant = accounts.grant(customerId, "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com",
                "An Peeters", Language.NL);
        return accounts.activate(grant.rawToken(), PASSWORD).sessionToken();
    }

    private static Response get(String token, String path) {
        return given().header("Authorization", "Bearer " + token).when().get(BASE + path);
    }

    private static String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** No key anywhere in the answer may hint at something that is the staff's alone. */
    private void assertNothingInternal(String body) throws Exception {
        List<String> keys = new ArrayList<>();
        collectKeys(json.readTree(body), keys);
        for (String key : keys)
            for (String word : List.of("internal", "token", "cost", "margin", "payment", "paid", "sentat", "viewcount", "actor"))
                assertFalse(key.toLowerCase(Locale.ROOT).contains(word), "key '" + key + "' in " + body);
    }

    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            keys.add(field.getKey());
            collectKeys(field.getValue(), keys);
        });
        if (node.isArray()) node.forEach(element -> collectKeys(element, keys));
    }
}
