package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesOrderDeliveryEntity;
import be.enrosed.sales.application.WebOrderDeliveries.Delivery;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;
import io.quarkus.arc.Arc;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static be.enrosed.sales.application.WebOrderTermsTest.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery address of one website order: stored per document, carried by
 * its copies, void for another customer, and what its freight is priced on.
 */
@QuarkusTest
class WebOrderDeliveriesTest {
    private static final AtomicLong IDS = new AtomicLong(9_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final long CUSTOMER = 2L;

    @Inject WebOrderDeliveries deliveries;
    @Inject WebOrderDeliveryCache cache;
    @Inject EntityManager em;

    @Test @TestTransaction
    void aRowIsInsertedThenReplacedAndCutToItsColumns() {
        long id = IDS.incrementAndGet();
        assertTrue(deliveries.find(id).isEmpty());
        Instant before = Instant.now();
        deliveries.save(id, delivery("Industrieweg 1", "3980", "Tessenderlo"));
        assertEquals(delivery("Industrieweg 1", "3980", "Tessenderlo"), deliveries.find(id).orElseThrow());
        assertFalse(em.find(SalesOrderDeliveryEntity.class, id).savedAt.isBefore(before));

        Delivery pickup = new Delivery(CUSTOMER, "PICKUP", null, null, null, 3L, "L".repeat(300), "A".repeat(600),
                "N".repeat(150), "P".repeat(60), null);
        deliveries.save(id, pickup);
        em.flush();
        em.clear();
        Delivery stored = deliveries.find(id).orElseThrow();
        assertEquals("PICKUP", stored.fulfillment());
        assertNull(stored.address());
        assertEquals(3L, stored.pickupLocationId());
        assertEquals(255, stored.pickupLabel().length());
        assertEquals(500, stored.pickupAddress().length());
        assertEquals(120, stored.contactName().length());
        assertEquals(50, stored.contactPhone().length());
        assertEquals(stored, deliveries.indexAll().get(id));
    }

    @Test @TestTransaction
    void aPreloadedRequestAnswersWithoutAQueryUntilTheNextWrite() {
        long id = IDS.incrementAndGet();
        long other = IDS.incrementAndGet();
        deliveries.save(id, delivery("Industrieweg 1", "3980", "Tessenderlo"));
        em.flush();
        deliveries.preloadForRequest();
        assertNotNull(cache.rows());

        em.createQuery("delete from SalesOrderDeliveryEntity d where d.salesOrderId = :id").setParameter("id", id).executeUpdate();
        em.clear();
        assertEquals("3980", deliveries.find(id).orElseThrow().postalCode(), "answered from the preloaded rows, not from the table");
        assertEquals("3980", deliveries.forDocument(order().id(id).build()).orElseThrow().postalCode());
        assertTrue(deliveries.find(other).isEmpty(), "a document without a row costs no query either");
        var held = cache.rows();
        deliveries.preloadForRequest();
        assertSame(held, cache.rows(), "one load per request");

        deliveries.save(other, delivery("Vekeblok 17", "2400", "Mol"));
        assertNull(cache.rows(), "a write clears the preload");
        assertTrue(deliveries.find(id).isEmpty());
        assertEquals("2400", deliveries.find(other).orElseThrow().postalCode());

        deliveries.preloadForRequest();
        deliveries.copy(other, id);
        assertNull(cache.rows(), "a copy clears it too");
        assertEquals("2400", deliveries.find(id).orElseThrow().postalCode());
    }

    @Test @TestTransaction
    void outsideARequestNothingIsPreloadedAndEveryLookupReadsItsRow() {
        long id = IDS.incrementAndGet();
        var context = Arc.container().requestContext();
        var state = context.getState();
        context.deactivate();
        try {
            deliveries.preloadForRequest();
            deliveries.save(id, delivery("Industrieweg 1", "3980", "Tessenderlo"));
            assertEquals("3980", deliveries.find(id).orElseThrow().postalCode());
        } finally {
            context.activate(state);
        }
        assertNull(cache.rows());
    }

    @Test @TestTransaction
    void aCopyCarriesTheCustomerAndNamesItsSource() {
        long source = IDS.incrementAndGet();
        long invoice = IDS.incrementAndGet();
        long part = IDS.incrementAndGet();
        deliveries.copy(source, invoice);
        assertTrue(deliveries.find(invoice).isEmpty(), "no source row: nothing");

        Delivery typed = new Delivery(CUSTOMER, "DELIVERY", "Industrieweg 1", "3980", "Tessenderlo", null, null, null,
                "An Peeters", "+32 470 00 00 00", null);
        deliveries.save(source, typed);
        deliveries.copy(source, invoice);
        assertEquals(new Delivery(CUSTOMER, "DELIVERY", "Industrieweg 1", "3980", "Tessenderlo", null, null, null,
                "An Peeters", "+32 470 00 00 00", source), deliveries.find(invoice).orElseThrow());
        assertNull(deliveries.find(source).orElseThrow().copiedFromOrderId());

        deliveries.copy(invoice, part);
        assertEquals(invoice, deliveries.find(part).orElseThrow().copiedFromOrderId(), "the document it was copied from");
        assertEquals(CUSTOMER, deliveries.find(part).orElseThrow().customerId());

        deliveries.save(source, delivery("Vekeblok 17", "2400", "Mol"));
        deliveries.copy(source, invoice);
        assertEquals("3980", deliveries.find(invoice).orElseThrow().postalCode(), "a target that has a row keeps it");
    }

    @Test @TestTransaction
    void aRowTypedForAnotherCustomerIsVoidForTheDocument() {
        long id = IDS.incrementAndGet();
        deliveries.save(id, delivery("Industrieweg 1", "3980", "Tessenderlo"));
        SalesOrder own = order().id(id).build();
        SalesOrder relinked = order().id(id).customerId(3L).build();
        assertEquals("3980", deliveries.forDocument(own).orElseThrow().postalCode());
        assertTrue(deliveries.forDocument(relinked).isEmpty());
        assertTrue(deliveries.find(id).isPresent(), "the raw row stays");
        assertTrue(deliveries.forDocument(order().id(null).build()).isEmpty());

        Customer record = customer(3L);
        assertSame(record, deliveries.pricingCustomer(relinked, record), "freight on the new customer's own record");

        long legacy = IDS.incrementAndGet();
        deliveries.save(legacy, new Delivery(null, "DELIVERY", "Kade 2", "9000", "Gent", null, null, null, null, null, null));
        assertEquals("9000", deliveries.forDocument(order().id(legacy).customerId(3L).build()).orElseThrow().postalCode(),
                "only a row that names a customer can be another customer's");
    }

    @Test @TestTransaction
    void aCreditNoteWithoutARowFallsBackToItsInvoice() {
        long invoice = IDS.incrementAndGet();
        long creditNote = IDS.incrementAndGet();
        deliveries.save(invoice, delivery("Industrieweg 1", "3980", "Tessenderlo"));
        SalesOrder credit = order().id(creditNote).docType(DocumentType.CREDITNOTA).creditedInvoiceId(invoice).build();
        assertEquals("3980", deliveries.forDocument(credit).orElseThrow().postalCode());
        assertTrue(deliveries.forDocument(order().id(creditNote).docType(DocumentType.CREDITNOTA).creditedInvoiceId(invoice)
                .customerId(3L).build()).isEmpty(), "the customer rule holds for the fallback row");
        assertTrue(deliveries.forDocument(order().id(creditNote).docType(DocumentType.FACTUUR).creditedInvoiceId(invoice).build())
                .isEmpty(), "only a credit note looks at the credited invoice");

        deliveries.save(creditNote, delivery("Vekeblok 17", "2400", "Mol"));
        assertEquals("2400", deliveries.forDocument(credit).orElseThrow().postalCode(), "its own row wins");
    }

    @Test @TestTransaction
    void freightIsPricedOnTheOrderedAddressOfAWebsiteDelivery() {
        long id = IDS.incrementAndGet();
        Customer record = customer(CUSTOMER);
        SalesOrder website = order().id(id).build();
        assertSame(record, deliveries.pricingCustomer(website, record), "no row: the record");

        deliveries.save(id, delivery("Industrieweg 1", "3980", "Tessenderlo"));
        Customer priced = deliveries.pricingCustomer(website, record);
        assertNotSame(record, priced);
        assertEquals("Industrieweg 1", priced.address());
        assertEquals("3980", priced.postalCode());
        assertEquals("Tessenderlo", priced.city());
        assertEquals(record, priced.withDeliveryAddress(record.address(), record.postalCode(), record.city()),
                "company country, VAT number, fiscal representative and language stay the record's");

        assertSame(record, deliveries.pricingCustomer(order().id(id).salesChannel("DIRECT").build(), record), "only the website channel");
        assertSame(record, deliveries.pricingCustomer(order().id(id).salesChannel(null).build(), record));
        assertNull(deliveries.pricingCustomer(website, null));

        deliveries.save(id, new Delivery(CUSTOMER, "PICKUP", null, null, null, 3L, "Magazijn Mol", "Vekeblok 17, 2400 Mol",
                "An Peeters", null, null));
        assertSame(record, deliveries.pricingCustomer(website, record), "a collection has no destination");
        deliveries.save(id, delivery("Industrieweg 1", " ", "Tessenderlo"));
        assertSame(record, deliveries.pricingCustomer(website, record), "no postal code, no carrier zone");
    }

    @Test
    void theDeliveryBlockOfAPackingSlipReplacesContactAndAddressOnly() {
        Customer record = customer(CUSTOMER);
        Customer slip = record.withDelivery("An Peeters", "Industrieweg 1", "3980", "Tessenderlo", "NL");
        assertEquals("An Peeters", slip.contact());
        assertEquals("Industrieweg 1", slip.address());
        assertEquals("3980", slip.postalCode());
        assertEquals("Tessenderlo", slip.city());
        assertEquals("NL", slip.countryCode());
        assertEquals(record, slip.withDelivery(record.contact(), record.address(), record.postalCode(), record.city(), record.countryCode()),
                "nothing else changes");
        assertEquals("Jan Janssens", record.withDelivery(" ", "Industrieweg 1", "3980", "Tessenderlo", "BE").contact(),
                "a blank contact keeps the record's");
        assertEquals("Jan Janssens", record.withDelivery(null, "Industrieweg 1", "3980", "Tessenderlo", "BE").contact());

        Customer pricing = record.withDeliveryAddress("Industrieweg 1", "3980", "Tessenderlo");
        assertEquals("BE", pricing.countryCode());
        assertEquals("Jan Janssens", pricing.contact());
        assertEquals(record, pricing.withDeliveryAddress(record.address(), record.postalCode(), record.city()));
    }

    private static Delivery delivery(String address, String postalCode, String city) {
        return new Delivery(CUSTOMER, "DELIVERY", address, postalCode, city, null, null, null, "An Peeters", null, null);
    }

    private static Customer customer(long id) {
        return new Customer(id, "Bloemen Janssens", "Jan Janssens", "jan@example.com", "+32 14 00 00 00", "BE0123456789",
                "BE", Language.FR, "Vekeblok 17", "2400", "Mol", "DAP", "30 dagen", "vaste klant",
                LocalDate.of(2026, 1, 5), true, new BigDecimal("40"), new BigDecimal("80"), true, "Factuurnotitie");
    }
}
