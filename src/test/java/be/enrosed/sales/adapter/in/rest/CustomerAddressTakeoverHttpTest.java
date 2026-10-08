package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.adapter.out.persistence.SalesOrderDeliveryEntity;
import be.enrosed.sales.application.WebOrderDeliveries;
import be.enrosed.sales.application.WebOrderMails;
import be.enrosed.sales.application.WebOrderMailsExecutor;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A customer made at login approval has no address, and an invoice needs
 * one. The staff view of a document says what the record lacks and which
 * delivery address may fill it; "Leveradres overnemen" writes the empty
 * fields and nothing else, and the invoice can be issued straight after.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class CustomerAddressTakeoverHttpTest {
    private static final String BASE = "/api/sales-orders";
    private static final String NOT_POSSIBLE = "Het leveradres kan niet overgenomen worden. Vul het adres in bij de klant.";
    private static final String CHANGED = "Het leveradres is intussen gewijzigd. Controleer het adres opnieuw.";
    /** What Shop.place() delivers at. */
    private static final Map<String, String> TESSENDERLO = Map.of("address", "Industrieweg 1", "postalCode", "3980", "city", "Tessenderlo");

    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @Inject WebOrderMails webOrderMails;
    @Inject WebOrderDeliveries deliveries;

    private final Shop shop = new Shop();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
        WebOrderMailsExecutor.direct(webOrderMails);
    }

    @AfterEach
    void removeRows() {
        WebOrderMailsExecutor.direct(webOrderMails);
        shop.remove();
    }

    @Test
    void theViewOfOneDocumentSaysWhatIsMissingAndWhichAddressWouldBeWritten() {
        Shop.Placed order = placedWithoutAddress();
        String company = record(order.customerId()).getString("company");

        get(order.id())
                .body("invoiceCustomer.customerId", equalTo((int) order.customerId()))
                .body("invoiceCustomer.company", equalTo(company))
                .body("invoiceCustomer.missing", contains("ADDRESS", "POSTAL_CODE", "CITY"))
                .body("invoiceCustomer.takeover.address", equalTo("Industrieweg 1"))
                .body("invoiceCustomer.takeover.postalCode", equalTo("3980"))
                .body("invoiceCustomer.takeover.city", equalTo("Tessenderlo"))
                .body("invoiceCustomer.takeover.countryCode", equalTo("BE"))
                .body("invoiceCustomer.takeoverBlockedBy", nullValue());
        given().get(BASE).then().statusCode(200)
                .body("find { it.order.id == " + order.id() + " }.invoiceCustomer", nullValue());

        Shop.Placed complete = shop.place();
        get(complete.id()).body("invoiceCustomer", nullValue());
    }

    @Test
    void theTakeoverFillsTheEmptyFieldsLogsItOnceAndLeavesTheOrderWithItsCustomer() {
        Shop.Placed order = placedWithoutAddress();
        Map<String, Object> before = record(order.customerId()).getMap("$");

        takeOver(order.id(), TESSENDERLO).statusCode(200)
                .body("order.id", equalTo((int) order.id()))
                .body("invoiceCustomer", nullValue())
                .body("delivery.differsFromCustomerRecord", equalTo(false))
                .body("webOrder.revision", equalTo(1))
                .body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.customerEditable", equalTo(true));
        assertEquals(0, mailbox.getTotalMessagesSent(), "taking an address over mails nobody");

        Map<String, Object> expected = new LinkedHashMap<>(before);
        expected.putAll(TESSENDERLO);
        assertEquals(expected, record(order.customerId()).getMap("$"),
                "the three address fields and nothing else: name, VAT number, contact, e-mail and phone are as they were");
        assertEquals(List.of("Adres overgenomen van het leveradres van " + order.number()), addressLogEntries(order.customerId()));

        takeOver(order.id(), TESSENDERLO).statusCode(200).body("invoiceCustomer", nullValue());
        takeOver(order.id(), Map.of("address", "Elders 1", "postalCode", "1000", "city", "Brussel")).statusCode(200);
        assertEquals(expected, record(order.customerId()).getMap("$"), "a second call changes nothing");
        assertEquals(1, addressLogEntries(order.customerId()).size(), "and logs nothing");
        get(order.id()).body("webOrder.processingStartedAt", nullValue());
    }

    @Test
    void aFieldThatHoldsAValueIsNeverOverwritten() {
        Shop.Placed same = placedWithoutAddress();
        setAddress(same.customerId(), "industrieweg 1", null, null);
        get(same.id()).body("invoiceCustomer.missing", contains("POSTAL_CODE", "CITY"))
                .body("invoiceCustomer.takeover.address", equalTo("industrieweg 1"))
                .body("invoiceCustomer.takeover.city", equalTo("Tessenderlo"));
        takeOver(same.id(), TESSENDERLO).statusCode(200).body("invoiceCustomer", nullValue());
        JsonPath filled = record(same.customerId());
        assertEquals("industrieweg 1", filled.getString("address"), "the street staff typed stays as they typed it");
        assertEquals("3980", filled.getString("postalCode"));
        assertEquals("Tessenderlo", filled.getString("city"));

        Shop.Placed other = placedWithoutAddress();
        setAddress(other.customerId(), null, null, "Antwerpen");
        get(other.id()).body("invoiceCustomer.missing", contains("ADDRESS", "POSTAL_CODE"))
                .body("invoiceCustomer.takeover", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", equalTo("INCOMPLETE"));
        takeOver(other.id(), TESSENDERLO).statusCode(409).body("message", equalTo(NOT_POSSIBLE))
                .body("$", not(hasKey("code")));
        JsonPath untouched = record(other.customerId());
        assertNull(untouched.getString("address"));
        assertNull(untouched.getString("postalCode"));
        assertEquals("Antwerpen", untouched.getString("city"));
        assertEquals(List.of(), addressLogEntries(other.customerId()));
    }

    @Test
    void aPickUpOrderOffersNothingAndRefusesTheCall() {
        Shop.Placed order = placedWithoutAddress();
        shop.inTransaction(() -> deliveries.save(order.id(), new WebOrderDeliveries.Delivery(order.customerId(),
                WebOrderDeliveries.PICKUP, null, null, null, null, "Magazijn Enrosed", "Magazijnweg 1, 2000 Antwerpen",
                "Jan Besteller", "+32 13 00 00 00", null)));

        get(order.id()).body("invoiceCustomer.missing", contains("ADDRESS", "POSTAL_CODE", "CITY"))
                .body("invoiceCustomer.takeover", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", equalTo("PICKUP"));
        takeOver(order.id(), Map.of("address", "Magazijnweg 1", "postalCode", "2000", "city", "Antwerpen"))
                .statusCode(409).body("message", equalTo(NOT_POSSIBLE));
        assertNull(record(order.customerId()).getString("address"));
        assertNull(record(order.customerId()).getString("city"));
    }

    @Test
    void anAddressThatIsNoLongerTheOneStaffSawIsRefused() {
        Shop.Placed order = placedWithoutAddress();

        takeOver(order.id(), Map.of("address", "Industrieweg 2", "postalCode", "3980", "city", "Tessenderlo"))
                .statusCode(409).body("status", equalTo(409)).body("message", equalTo(CHANGED));
        given().contentType("application/json").post(BASE + "/{id}/customer-address-from-delivery", order.id())
                .then().statusCode(409).body("message", equalTo(CHANGED));
        assertNull(record(order.customerId()).getString("address"));
        assertEquals(List.of(), addressLogEntries(order.customerId()));

        takeOver(order.id(), Map.of("address", " industrieweg 1", "postalCode", "3980 ", "city", "TESSENDERLO"))
                .statusCode(200).body("invoiceCustomer", nullValue());
        assertEquals("Industrieweg 1", record(order.customerId()).getString("address"), "what is written is the delivery row, not the call");
    }

    @Test
    void aMissingDocumentADocumentWithoutDeliveryAndARowOfAnotherCustomerOfferNothing() {
        takeOver(987_654_321L, TESSENDERLO).statusCode(404);

        long plain = shop.legacyRequests(1).getFirst();
        long plainCustomer = ((Number) get(plain).extract().path("order.customerId")).longValue();
        setAddress(plainCustomer, null, null, null);
        get(plain).body("invoiceCustomer.missing", contains("ADDRESS", "POSTAL_CODE", "CITY"))
                .body("invoiceCustomer.takeover", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", equalTo("NO_DELIVERY"));
        takeOver(plain, TESSENDERLO).statusCode(409).body("message", equalTo(NOT_POSSIBLE));

        /* A delivery typed for another customer is void: it never fills this customer's record. */
        Shop.Placed order = placedWithoutAddress();
        long stranger = shop.customer("Andere klant", "Kaai 1", "9000", "Gent");
        shop.inTransaction(() -> em.find(SalesOrderDeliveryEntity.class, order.id()).customerId = stranger);
        get(order.id()).body("invoiceCustomer.takeover", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", equalTo("NO_DELIVERY"));
        takeOver(order.id(), TESSENDERLO).statusCode(409).body("message", equalTo(NOT_POSSIBLE));
        assertNull(record(order.customerId()).getString("address"));
        assertEquals("Kaai 1", record(stranger).getString("address"));
    }

    @Test
    void aCustomerWithAFullAddressIsLeftAlone() {
        Shop.Placed order = shop.place();
        Map<String, Object> before = record(order.customerId()).getMap("$");

        get(order.id()).body("invoiceCustomer", nullValue()).body("delivery.differsFromCustomerRecord", equalTo(true));
        takeOver(order.id(), TESSENDERLO).statusCode(200).body("invoiceCustomer", nullValue())
                .body("webOrder.processingStartedAt", nullValue());
        assertEquals(before, record(order.customerId()).getMap("$"), "Bloemenlaan 5 in Antwerpen stays the address of the record");
        assertEquals(List.of(), addressLogEntries(order.customerId()));
    }

    @Test
    void anotherCountryAndACancelledOrderOfferNothing() {
        Shop.Placed abroad = placedWithoutAddress();
        shop.inTransaction(() -> em.find(CustomerEntity.class, abroad.customerId()).countryCode = "NL");
        get(abroad.id()).body("invoiceCustomer.takeover", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", equalTo("OTHER_COUNTRY"));
        takeOver(abroad.id(), TESSENDERLO).statusCode(409).body("message", equalTo(NOT_POSSIBLE));

        Shop.Placed cancelled = placedWithoutAddress();
        shop.customerCancels(cancelled.id());
        get(cancelled.id()).body("invoiceCustomer", nullValue()).body("webOrder.customerCancelledAt", notNullValue());
        takeOver(cancelled.id(), TESSENDERLO).statusCode(409).body("message", equalTo(NOT_POSSIBLE));
        assertNull(record(cancelled.customerId()).getString("address"));
    }

    @Test
    void theInvoiceOfTheOrderIsRefusedInPlainDutchAndIssuesRightAfterTheTakeover() {
        Shop.Placed order = placedWithoutAddress();
        String company = record(order.customerId()).getString("company");
        long invoiceId = ((Number) given().queryParam("webOrderRevision", 1).contentType("application/json")
                .post(BASE + "/{id}/invoice", order.id()).then().statusCode(200)
                .body("order.docType", equalTo("FACTUUR"))
                .body("invoiceCustomer.missing", contains("ADDRESS", "POSTAL_CODE", "CITY"))
                .body("invoiceCustomer.takeover.address", equalTo("Industrieweg 1"))
                .extract().path("order.id")).longValue();

        String refusal = "De factuur kan niet uitgereikt worden: bij klant " + company
                + " ontbreken straat en nummer, postcode en stad. Vul dit in bij de klantgegevens.";
        issue(invoiceId).statusCode(409).body("message", equalTo(refusal)).body("$", not(hasKey("code")));
        given().contentType("application/json").body("{}").post(BASE + "/{id}/mark-sent", invoiceId)
                .then().statusCode(409).body("message", equalTo(refusal));

        takeOver(invoiceId, TESSENDERLO).statusCode(200)
                .body("order.id", equalTo((int) invoiceId))
                .body("order.status", equalTo("CONCEPT"))
                .body("invoiceCustomer", nullValue());
        get(order.id()).body("invoiceCustomer", nullValue());
        assertEquals(List.of("Adres overgenomen van het leveradres van " + get(invoiceId).extract().path("order.number")),
                addressLogEntries(order.customerId()));

        issue(invoiceId).statusCode(200).body("order.status", not(equalTo("CONCEPT")))
                .body("invoiceCustomer", nullValue());
    }

    @Test
    void aRecordWithoutCountryGetsItsAddressAndNoCountry() {
        Shop.Placed order = placedWithoutAddress();
        shop.inTransaction(() -> em.find(CustomerEntity.class, order.customerId()).countryCode = null);
        /* Staff can set any country on the document; it says nothing about where the customer is established. */
        shop.inTransaction(() -> em.find(be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity.class,
                order.id()).countryCode = "NL");

        get(order.id()).body("order.countryCode", equalTo("NL"))
                .body("invoiceCustomer.takeover.address", equalTo("Industrieweg 1"))
                .body("invoiceCustomer.takeover.countryCode", nullValue())
                .body("invoiceCustomer.takeoverBlockedBy", nullValue());
        takeOver(order.id(), TESSENDERLO).statusCode(200).body("invoiceCustomer", nullValue());

        JsonPath filled = record(order.customerId());
        assertEquals("Industrieweg 1", filled.getString("address"));
        assertEquals("Tessenderlo", filled.getString("city"));
        assertNull(filled.getString("countryCode"), "the country stays for staff to set on the record");
    }

    @Test
    void anIssuedInvoiceWhoseCustomerLostTheAddressIsRefusedItsSendingNotItsIssuing() {
        Shop.Placed order = shop.place();
        String company = record(order.customerId()).getString("company");
        long invoiceId = ((Number) given().queryParam("webOrderRevision", 1).contentType("application/json")
                .post(BASE + "/{id}/invoice", order.id()).then().statusCode(200).extract().path("order.id")).longValue();
        issue(invoiceId).statusCode(200).body("order.status", equalTo("UITGEREIKT"));
        setAddress(order.customerId(), null, null, null);

        given().contentType("application/json").body("{}").post(BASE + "/{id}/mark-sent", invoiceId)
                .then().statusCode(409).body("message", equalTo("De factuur kan niet verstuurd worden: bij klant " + company
                        + " ontbreken straat en nummer, postcode en stad. Vul dit in bij de klantgegevens."));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** A website order whose customer is as login approval makes it: no street, postal code or city. */
    private Shop.Placed placedWithoutAddress() {
        Shop.Placed order = shop.place();
        setAddress(order.customerId(), null, null, null);
        return order;
    }

    private void setAddress(long customerId, String address, String postalCode, String city) {
        shop.inTransaction(() -> {
            CustomerEntity customer = em.find(CustomerEntity.class, customerId);
            customer.address = address;
            customer.postalCode = postalCode;
            customer.city = city;
        });
    }

    private static ValidatableResponse get(long id) {
        return given().get(BASE + "/{id}", id).then().statusCode(200);
    }

    private static JsonPath record(long customerId) {
        return given().get("/api/customers/{id}", customerId).then().statusCode(200).extract().jsonPath();
    }

    private static ValidatableResponse takeOver(long id, Map<String, String> shown) {
        return given().contentType("application/json").body(shown)
                .post(BASE + "/{id}/customer-address-from-delivery", id).then();
    }

    private static ValidatableResponse issue(long invoiceId) {
        return given().contentType("application/json").body("{}").post(BASE + "/{id}/issue", invoiceId).then();
    }

    /** The summaries of the edits logged on the customer record, creation left out. */
    private static List<String> addressLogEntries(long customerId) {
        return given().queryParam("entityType", "CUSTOMER").queryParam("entityId", Long.toString(customerId))
                .get("/api/activity").then().statusCode(200)
                .extract().jsonPath().getList("items.findAll { it.action == 'UPDATED' }.summary");
    }
}
