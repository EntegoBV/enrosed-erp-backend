package be.enrosed.sales.application;

import be.enrosed.sales.application.CustomerInvoiceData.Offer;
import be.enrosed.sales.application.CustomerInvoiceData.Takeover;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an invoice needs of the customer record, how the refusal names it, and when the delivery address may fill it. */
class CustomerInvoiceDataTest {

    @Test
    void theMissingFieldsAreNamedInOrderAndInPlainDutch() {
        assertEquals(List.of("ADDRESS", "POSTAL_CODE", "CITY"), CustomerInvoiceData.missing(customer(null, " ", "", "BE")));
        assertEquals(List.of("POSTAL_CODE"), CustomerInvoiceData.missing(customer("Kaai 1", null, "Gent", "BE")));
        assertEquals(List.of(), CustomerInvoiceData.missing(customer("Kaai 1", "9000", "Gent", null)),
                "the country is not needed for an ordinary invoice");

        assertEquals("straat en nummer, postcode en stad",
                CustomerInvoiceData.missingText(List.of("ADDRESS", "POSTAL_CODE", "CITY")));
        assertEquals("postcode en stad", CustomerInvoiceData.missingText(List.of("POSTAL_CODE", "CITY")));
        assertEquals("stad", CustomerInvoiceData.missingText(List.of("CITY")));
    }

    @Test
    void theRefusalSaysWhichDocumentWhichCustomerAndWhatIsMissing() {
        assertEquals("De factuur kan niet uitgereikt worden: bij klant Bloemen Anna ontbreken straat en nummer, postcode en stad."
                        + " Vul dit in bij de klantgegevens.",
                CustomerInvoiceData.refusal(customer(null, null, null, "BE"), false));
        assertEquals("De creditnota kan niet uitgereikt worden: bij klant Bloemen Anna ontbreekt postcode."
                        + " Vul dit in bij de klantgegevens.",
                CustomerInvoiceData.refusal(customer("Kaai 1", "", "Gent", "BE"), true));
        assertNull(CustomerInvoiceData.refusal(customer("Kaai 1", "9000", "Gent", "BE"), false));
        assertEquals("De factuur kan niet verstuurd worden: bij klant Bloemen Anna ontbreekt stad."
                        + " Vul dit in bij de klantgegevens.",
                CustomerInvoiceData.refusal(customer("Kaai 1", "9000", null, "BE"), false, true),
                "an invoice that is issued already is not refused its issuing");
        assertEquals(CustomerInvoiceData.refusal(customer(null, null, null, "BE"), true),
                CustomerInvoiceData.refusal(customer(null, null, null, "BE"), true, false));
    }

    @Test
    void aDeliveryAtARealAddressCompletesAnEmptyRecord() {
        Offer offer = CustomerInvoiceData.offer(customer(null, null, null, "BE"), order(QuoteStatus.CONCEPT, DocumentType.OFFERTE),
                delivery(WebOrderDeliveries.DELIVERY, " Stationsstraat 9 ", "9000", "Gent"));
        assertEquals(new Takeover("Stationsstraat 9", "9000", "Gent", "BE"), offer.takeover());
        assertNull(offer.blockedBy());

        Offer noCountry = CustomerInvoiceData.offer(customer(null, null, null, " "), order(QuoteStatus.CONCEPT, DocumentType.OFFERTE),
                delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", "9000", "Gent"));
        assertEquals(new Takeover("Stationsstraat 9", "9000", "Gent", null), noCountry.takeover(),
                "the country of the document is no part of the delivery address: a record without country keeps none");
    }

    @Test
    void everyReasonNotToOfferTheTakeoverHasItsCode() {
        SalesOrder order = order(QuoteStatus.CONCEPT, DocumentType.OFFERTE);
        Customer empty = customer(null, null, null, "BE");

        assertEquals(new Offer(null, "NO_DELIVERY"), CustomerInvoiceData.offer(empty, order, null));
        assertEquals(new Offer(null, "PICKUP"), CustomerInvoiceData.offer(empty, order,
                delivery(WebOrderDeliveries.PICKUP, "Magazijnweg 1", "2000", "Antwerpen")));
        assertEquals(new Offer(null, "OTHER_COUNTRY"), CustomerInvoiceData.offer(customer(null, null, null, "NL"), order,
                delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", "9000", "Gent")));
        assertEquals(new Offer(null, "INCOMPLETE"), CustomerInvoiceData.offer(empty, order,
                delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", " ", "Gent")),
                "a delivery that cannot fill a missing field does not complete the record");
        assertEquals(new Offer(null, "INCOMPLETE"), CustomerInvoiceData.offer(customer(null, null, "Brussel", "BE"), order,
                delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", "9000", "Gent")),
                "a record that already names another city would end up with two addresses mixed");
    }

    @Test
    void aFieldTheRecordAlreadyHasMayOnlySayTheSameAsTheDelivery() {
        Offer offer = CustomerInvoiceData.offer(customer(null, null, " gent", "be"), order(QuoteStatus.CONCEPT, DocumentType.OFFERTE),
                delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", "9000", "Gent"));
        assertEquals("Stationsstraat 9", offer.takeover().address());
        assertEquals(" gent", offer.takeover().city(), "a field that is not written is shown as the record has it");
        assertEquals("be", offer.takeover().countryCode(), "the record's own country stays as it is");
    }

    @Test
    void theNoticeBelongsOnlyOnADocumentThatCanStillLeadToAnInvoice() {
        Customer empty = customer(null, null, null, "BE");
        var row = delivery(WebOrderDeliveries.DELIVERY, "Stationsstraat 9", "9000", "Gent");

        var notice = CustomerInvoiceData.notice(empty, order(QuoteStatus.VERZONDEN, DocumentType.OFFERTE), row);
        assertEquals(42L, notice.customerId());
        assertEquals("Bloemen Anna", notice.company());
        assertEquals(List.of("ADDRESS", "POSTAL_CODE", "CITY"), notice.missing());
        assertEquals("Gent", notice.takeover().city());
        assertNull(notice.takeoverBlockedBy());

        assertTrue(CustomerInvoiceData.appliesTo(order(QuoteStatus.GEACCEPTEERD, DocumentType.OFFERTE)));
        assertTrue(CustomerInvoiceData.appliesTo(order(QuoteStatus.CONCEPT, DocumentType.FACTUUR)));
        assertTrue(CustomerInvoiceData.appliesTo(order(QuoteStatus.CONCEPT, DocumentType.CREDITNOTA)));
        assertFalse(CustomerInvoiceData.appliesTo(order(QuoteStatus.UITGEREIKT, DocumentType.FACTUUR)), "an issued invoice asks nothing any more");
        assertFalse(CustomerInvoiceData.appliesTo(order(QuoteStatus.BETAALD, DocumentType.FACTUUR)));
        for (QuoteStatus closed : List.of(QuoteStatus.GEANNULEERD, QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN))
            assertNull(CustomerInvoiceData.notice(empty, order(closed, DocumentType.OFFERTE), row), closed.name());
        assertNull(CustomerInvoiceData.notice(customer("Kaai 1", "9000", "Gent", "BE"),
                order(QuoteStatus.CONCEPT, DocumentType.OFFERTE), row), "a complete record has no notice");
    }

    private static Customer customer(String address, String postalCode, String city, String countryCode) {
        return new Customer(42L, "Bloemen Anna", "Anna", "anna@example.com", "+32 9 000 00 00", "BE0123456789",
                countryCode, null, address, postalCode, city, null, null, null, LocalDate.now());
    }

    private static WebOrderDeliveries.Delivery delivery(String fulfillment, String address, String postalCode, String city) {
        return new WebOrderDeliveries.Delivery(42L, fulfillment, address, postalCode, city, null, null, null,
                "Jan Besteller", "+32 13 00 00 00", null);
    }

    private static SalesOrder order(QuoteStatus status, DocumentType type) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(7L, "ENR-2026-0007", 42L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT,
                new BigDecimal("45"), null, null, null, null, null, 0,
                null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.BEREKEND, null, LoadMode.PALLETS,
                PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                type, null, null, null, null, List.of(), List.of());
    }
}
