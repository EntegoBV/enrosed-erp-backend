package be.enrosed.sales.application;

import be.enrosed.sales.adapter.in.rest.AccountOrderDtos;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DeliveryDefaults;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DocumentPage;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DocumentRow;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderDetail;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.AccountDocuments.ListKind;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.domain.CreditReason;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a logged-in customer reads under "my orders", against the real tables:
 * which documents show at all, the status each one reads, that a website
 * order stays what was ordered until Enrosed sends a version, and what the
 * delivery step starts from. Staff act through the real services; the
 * customer side of an order is played by the shared fixture.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class AccountDocumentsVisibilityTest {
    @Inject AccountDocuments documents;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject IncomingPaymentService payments;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject CountryService countries;
    @Inject EntityManager em;
    /** Real, except where a test offers a collection point of its own. */
    @InjectSpy StockService stock;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    // ------------------------------------------------------------------------------------------ website orders

    @Test
    void aWebsiteOrderIsListedInEveryStateWithTheStatusTheCustomerReads() {
        Shop.Placed untaken = shop.place();
        DocumentRow row = listed(untaken, ListKind.ORDERS);
        assertEquals("ORDER", row.kind());
        assertEquals("RECEIVED", row.status());
        assertNull(row.cancelledBy());
        assertTrue(row.canChange() && row.canCancel(), "nobody at Enrosed touched it");
        assertTrue(row.hasDetail());
        assertFalse(row.hasPdf(), "an order that was never sent has no document");
        assertEquals(untaken.number(), row.number());
        assertEquals(0, new BigDecimal("1320.00").compareTo(row.totalExclVat()), "the total as ordered");
        assertEquals(0, new BigDecimal("1597.20").compareTo(row.totalInclVat()));
        assertEquals("EUR", row.currency());
        assertNull(row.validUntil(), "the validity is only shown while an approval is asked");
        assertNull(row.relatedNumber());
        assertTrue(pdf(untaken.id(), untaken.customerId()).isEmpty());

        Shop.Placed taken = shop.place();
        sales.takeIntoProcessing(taken.id());
        row = listed(taken, ListKind.ORDERS);
        assertEquals("IN_PROCESSING", row.status());
        assertFalse(row.canChange() || row.canCancel());

        Shop.Placed reopened = shop.place();
        quotes.send(reopened.id(), null);
        quotes.reopen(reopened.id());
        row = listed(reopened, ListKind.ORDERS);
        assertEquals("IN_PROCESSING", row.status(), "a reopened order is being worked on");
        assertFalse(row.hasPdf(), "the version that was sent is withdrawn");
        assertEquals("AS_ORDERED", detail(reopened).basis());
        assertTrue(pdf(reopened.id(), reopened.customerId()).isEmpty());

        Shop.Placed cancelledByCustomer = shop.place();
        shop.customerCancels(cancelledByCustomer.id());
        row = listed(cancelledByCustomer, ListKind.ORDERS);
        assertEquals("CANCELLED", row.status());
        assertEquals("CUSTOMER", row.cancelledBy());
        assertFalse(row.canChange() || row.hasPdf());
        assertNull(detail(cancelledByCustomer).cancellationMessage());

        Shop.Placed cancelledByStaff = shop.place();
        quotes.cancel(cancelledByStaff.id(), "Niet meer leverbaar dit seizoen", false);
        row = listed(cancelledByStaff, ListKind.ORDERS);
        assertEquals("CANCELLED", row.status());
        assertEquals("ENROSED", row.cancelledBy());
        OrderDetail cancelled = detail(cancelledByStaff);
        assertEquals("AS_ORDERED", cancelled.basis(), "never sent: still what was ordered");
        assertEquals("Niet meer leverbaar dit seizoen", cancelled.cancellationMessage());
        assertFalse(cancelled.hasPdf());
        assertTrue(pdf(cancelledByStaff.id(), cancelledByStaff.customerId()).isEmpty());

        Shop.Placed invoiced = shop.place();
        SalesOrder draft = sales.createInvoiceFrom(invoiced.id());
        row = listed(invoiced, ListKind.ORDERS);
        assertEquals("IN_PROCESSING", row.status(), "a draft invoice is never revealed");
        assertNull(row.relatedNumber());
        assertTrue(page(ListKind.INVOICES, invoiced.customerId()).items().isEmpty(), "the draft invoice is hidden");
        assertHidden(draft.id(), invoiced.customerId());

        SalesOrder issued = sales.issueInvoice(draft.id());
        row = listed(invoiced, ListKind.ORDERS);
        assertEquals("CONFIRMED", row.status(), "the issued invoice is Enrosed's confirmation");
        assertEquals(issued.number(), row.relatedNumber());
        assertEquals(issued.number(), detail(invoiced).relatedNumber());
        DocumentRow invoice = page(ListKind.INVOICES, invoiced.customerId()).items().getFirst();
        assertEquals("INVOICE", invoice.kind());
        assertEquals("ISSUED", invoice.status());
        assertEquals(invoiced.number(), invoice.relatedNumber(), "the order the invoice was made from");
        assertTrue(invoice.hasPdf());
        assertFalse(invoice.hasDetail() || invoice.canChange() || invoice.canCancel());
        assertEquals(0, new BigDecimal("1597.20").compareTo(invoice.totalInclVat()));
        assertTrue(detail(issued.id(), invoiced.customerId()).isEmpty(), "an invoice has no detail");
        assertTrue(pdf(issued.id(), invoiced.customerId()).isPresent());
    }

    @Test
    void anOrderShowsWhatWasOrderedUntilEnrosedSendsAVersion() {
        Shop.Placed order = shop.place();
        sales.takeIntoProcessing(order.id());
        String otherCountry = shop.inTransaction(() -> countries.list()).stream().map(country -> country.code())
                .filter(code -> !"BE".equals(code)).findFirst().orElseThrow();
        SalesOrder raised = shop.edited(order(order.id()), 240, "12.50", "300.00");
        sales.update(order.id(), withCountryAndNote(raised, otherCountry, "Graag voor donderdag"));

        OrderDetail ordered = detail(order);
        assertEquals("ORDER", ordered.kind());
        assertEquals("IN_PROCESSING", ordered.status());
        assertEquals("AS_ORDERED", ordered.basis());
        assertEquals(LocalDate.now(AccountDocuments.BRUSSELS), ordered.basisDate());
        assertEquals(1, ordered.revision());
        assertEquals(1, ordered.lines().size());
        assertEquals(120, ordered.lines().getFirst().quantity(), "staff's unsent quantity never leaves");
        assertEquals(10, ordered.lines().getFirst().cartons());
        assertEquals(12, ordered.lines().getFirst().piecesPerCarton());
        assertEquals(0, BigDecimal.TEN.compareTo(ordered.lines().getFirst().unitPrice()));
        assertEquals(order.sku(), ordered.lines().getFirst().sku());
        assertTrue(ordered.extraLines().isEmpty());
        assertEquals(0, new BigDecimal("1200.00").compareTo(ordered.totals().goods()));
        assertNull(ordered.totals().extras());
        assertEquals(0, new BigDecimal("120.00").compareTo(ordered.totals().shipping()));
        assertEquals("CALCULATED", ordered.totals().shippingStatus());
        assertEquals(0, new BigDecimal("1320.00").compareTo(ordered.totals().totalExclVat()));
        assertEquals(0, new BigDecimal("1597.20").compareTo(ordered.totals().totalInclVat()));
        assertEquals("DELIVERY", ordered.fulfillment());
        assertNull(ordered.pickupLocation());
        assertEquals("BE", ordered.destination().countryCode(), "the country as ordered, not staff's unsent change");
        assertEquals("3980", ordered.destination().postalCode());
        assertEquals("Tessenderlo", ordered.destination().city());
        assertEquals("Industrieweg 1", ordered.destination().address());
        assertEquals("Jan Besteller", ordered.contactName());
        assertEquals("+32 13 00 00 00", ordered.phone());
        assertEquals("Graag voor donderdag", ordered.notes());
        assertFalse(ordered.hasPdf());
        assertEquals(0, new BigDecimal("1320.00").compareTo(listed(order, ListKind.ORDERS).totalExclVat()));
        assertEquals("BE", defaults(order.customerId()).destination().countryCode(),
                "the next order starts from the country that was ordered");

        /* Back to Belgium, then sent: from now on the customer reads the version Enrosed sent. */
        sales.update(order.id(), withCountryAndNote(order(order.id()), "BE", "Graag voor donderdag"));
        quotes.send(order.id(), null);
        OrderDetail current = detail(order);
        assertEquals("AWAITING_APPROVAL", current.status());
        assertEquals("CURRENT", current.basis());
        assertEquals(LocalDate.now(AccountDocuments.BRUSSELS), current.basisDate());
        assertNotNull(current.validUntil());
        assertEquals(240, current.lines().getFirst().quantity());
        assertEquals(20, current.lines().getFirst().cartons());
        assertEquals(12, current.lines().getFirst().piecesPerCarton());
        assertEquals(0, new BigDecimal("12.50").compareTo(current.lines().getFirst().unitPrice()));
        assertEquals(0, new BigDecimal("3000.00").compareTo(current.totals().goods()));
        assertEquals(0, new BigDecimal("300.00").compareTo(current.totals().shipping()));
        assertEquals(0, new BigDecimal("3300.00").compareTo(current.totals().totalExclVat()));
        assertEquals("Graag voor donderdag", current.notes(), "the customer's own remark");
        assertEquals("Industrieweg 1", current.destination().address());
        assertTrue(current.hasPdf());
        DocumentRow row = listed(order, ListKind.ORDERS);
        assertEquals(0, new BigDecimal("3300.00").compareTo(row.totalExclVat()), "the list follows the version sent");
        assertEquals(current.validUntil(), row.validUntil());
        assertTrue(pdf(order.id(), order.customerId()).isPresent());

        String token = order(order.id()).portalToken();
        quotes.acceptByCustomer(token, "An Peeters", null);
        assertEquals("CONFIRMED", listed(order, ListKind.ORDERS).status(), "an approved order is confirmed");
        assertNull(listed(order, ListKind.ORDERS).validUntil());
    }

    // ------------------------------------------------------------------------------------------ quotations

    @Test
    void aQuotationShowsOnceItWasSentAndADraftNeverDoes() {
        long customerId = shop.customer("Offerteklant BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();

        SalesOrder draft = quote(customerId, productId, "Eigen tekst van Enrosed");
        assertHidden(draft.id(), customerId);

        SalesOrder sent = quote(customerId, productId, "Eigen tekst van Enrosed");
        quotes.send(sent.id(), null);
        DocumentRow row = listed(sent.id(), customerId, ListKind.ORDERS);
        assertEquals("QUOTE", row.kind());
        assertEquals("AWAITING_APPROVAL", row.status());
        assertNotNull(row.validUntil());
        assertTrue(row.hasPdf() && row.hasDetail());
        assertFalse(row.canChange() || row.canCancel(), "a quotation is never the customer's to change");
        OrderDetail detail = detail(sent.id(), customerId).orElseThrow();
        assertEquals("CURRENT", detail.basis());
        assertNull(detail.revision());
        assertNull(detail.notes(), "the note staff wrote is Enrosed's own text");
        assertNull(detail.fulfillment());
        assertNull(detail.destination());
        assertNull(detail.contactName());
        assertNull(detail.phone());
        assertEquals(0, new BigDecimal("1320.00").compareTo(detail.totals().totalExclVat()));

        SalesOrder expired = quote(customerId, productId, null);
        quotes.send(expired.id(), null);
        shop.inTransaction(() -> entity(expired.id()).validUntil = LocalDate.now().minusDays(1));
        assertEquals("EXPIRED", listed(expired.id(), customerId, ListKind.ORDERS).status());
        assertNotNull(listed(expired.id(), customerId, ListKind.ORDERS).validUntil());

        SalesOrder accepted = quote(customerId, productId, null);
        quotes.acceptByCustomer(quotes.send(accepted.id(), null).portalToken(), "An Peeters", null);
        assertEquals("ACCEPTED", listed(accepted.id(), customerId, ListKind.ORDERS).status());

        SalesOrder declined = quote(customerId, productId, null);
        quotes.rejectByCustomer(quotes.send(declined.id(), null).portalToken(), "Te duur");
        assertEquals("DECLINED", listed(declined.id(), customerId, ListKind.ORDERS).status());

        SalesOrder changeAsked = quote(customerId, productId, null);
        quotes.send(changeAsked.id(), null);
        shop.inTransaction(() -> entity(changeAsked.id()).status = QuoteStatus.WIJZIGING_GEVRAAGD);
        assertEquals("CHANGE_REQUESTED", listed(changeAsked.id(), customerId, ListKind.ORDERS).status());

        SalesOrder cancelledAfterSend = quote(customerId, productId, null);
        quotes.send(cancelledAfterSend.id(), null);
        quotes.cancel(cancelledAfterSend.id(), "Vervangen door een nieuwe offerte", false);
        row = listed(cancelledAfterSend.id(), customerId, ListKind.ORDERS);
        assertEquals("CANCELLED", row.status());
        assertEquals("ENROSED", row.cancelledBy());
        assertEquals("Vervangen door een nieuwe offerte",
                detail(cancelledAfterSend.id(), customerId).orElseThrow().cancellationMessage());

        SalesOrder cancelledNeverSent = quote(customerId, productId, null);
        quotes.cancel(cancelledNeverSent.id(), null, false);
        assertHidden(cancelledNeverSent.id(), customerId);

        SalesOrder reopened = quote(customerId, productId, null);
        quotes.send(reopened.id(), null);
        quotes.reopen(reopened.id());
        assertHidden(reopened.id(), customerId);

        SalesOrder invoicedQuote = quote(customerId, productId, null);
        quotes.acceptByCustomer(quotes.send(invoicedQuote.id(), null).portalToken(), "An Peeters", null);
        SalesOrder invoice = sales.issueInvoice(sales.createInvoiceFrom(invoicedQuote.id()).id());
        assertTrue(order(invoicedQuote.id()).isArchived(), "the quote went to the archive with its invoice");
        row = listed(invoicedQuote.id(), customerId, ListKind.ORDERS);
        assertEquals("CONFIRMED", row.status());
        assertEquals(invoice.number(), row.relatedNumber());

        List<Long> shown = page(ListKind.ORDERS, customerId).items().stream().map(DocumentRow::id).toList();
        assertEquals(List.of(invoicedQuote.id(), cancelledAfterSend.id(), changeAsked.id(), declined.id(), accepted.id(),
                expired.id(), sent.id()), shown, "newest first, and nothing else");
    }

    @Test
    void aWebsiteRequestWithoutAnOrderRowIsAQuotationAndKeepsTheCustomersRemark() {
        long legacy = shop.legacyRequests(1).getFirst();
        long customerId = order(legacy).customerId();
        shop.inTransaction(() -> entity(legacy).notes = "Leveren na 14 uur");
        assertHidden(legacy, customerId);

        quotes.send(legacy, null);
        assertEquals("QUOTE", listed(legacy, customerId, ListKind.ORDERS).kind());
        OrderDetail detail = detail(legacy, customerId).orElseThrow();
        assertEquals("CURRENT", detail.basis());
        assertEquals("Leveren na 14 uur", detail.notes(), "what the customer typed in the request");
        assertNull(detail.destination(), "a delivery address belongs to an order only");
    }

    // ------------------------------------------------------------------------------------------ invoices and credit notes

    @Test
    void invoicesAndCreditNotesShowOnceIssuedAndNeverSayAnythingAboutPayment() {
        long customerId = shop.customer("Factuurklant BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();

        SalesOrder draft = invoiceDraft(customerId, productId);
        assertHidden(draft.id(), customerId);

        SalesOrder issued = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        assertEquals("ISSUED", listed(issued.id(), customerId, ListKind.INVOICES).status());

        SalesOrder viewed = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        shop.inTransaction(() -> entity(viewed.id()).status = QuoteStatus.BEKEKEN);
        assertEquals("ISSUED", listed(viewed.id(), customerId, ListKind.INVOICES).status());

        SalesOrder paid = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        payments.add(paid.id(), new IncomingPaymentService.Request(new BigDecimal("1597.20"), Instant.now(),
                "Europe/Brussels", "OGM"));
        assertEquals(QuoteStatus.BETAALD, order(paid.id()).status());
        DocumentRow paidRow = listed(paid.id(), customerId, ListKind.INVOICES);
        assertEquals("ISSUED", paidRow.status(), "paid or not is not the history's to tell");
        assertEquals(0, new BigDecimal("1597.20").compareTo(paidRow.totalInclVat()), "the invoice amount, not what is open");
        assertNull(paidRow.cancelledBy());

        SalesOrder credited = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        SalesOrder draftCredit = creditNote(credited.id());
        assertHidden(draftCredit.id(), customerId);
        SalesOrder issuedCredit = sales.issueInvoice(creditNote(credited.id()).id());
        DocumentRow credit = listed(issuedCredit.id(), customerId, ListKind.INVOICES);
        assertEquals("CREDIT_NOTE", credit.kind());
        assertEquals("ISSUED", credit.status());
        assertEquals(credited.number(), credit.relatedNumber(), "the invoice it corrects");
        assertTrue(credit.hasPdf());
        assertFalse(credit.hasDetail());
        SalesOrder cancelledCredit = sales.issueInvoice(creditNote(credited.id()).id());
        quotes.cancel(cancelledCredit.id(), "Dubbel opgemaakt", false);
        assertEquals(QuoteStatus.GEANNULEERD, order(cancelledCredit.id()).status());
        assertHidden(cancelledCredit.id(), customerId);

        /* A partner document never shows, whatever its status. */
        SalesOrder partnerAdvance = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        shop.inTransaction(() -> {
            entity(partnerAdvance.id()).purpose = SalesPurpose.PARTNER_ADVANCE;
            entity(partnerAdvance.id()).partnerPurchaseOrderId = 987_654L;
        });
        assertHidden(partnerAdvance.id(), customerId);
        SalesOrder partnerSettlement = sales.issueInvoice(invoiceDraft(customerId, productId).id());
        shop.inTransaction(() -> {
            entity(partnerSettlement.id()).purpose = SalesPurpose.PARTNER_SETTLEMENT;
            entity(partnerSettlement.id()).partnerSettlement = true;
        });
        assertHidden(partnerSettlement.id(), customerId);
        shop.inTransaction(() -> {
            /* Back to plain documents, so the fixture can remove them. */
            entity(partnerAdvance.id()).partnerPurchaseOrderId = null;
            entity(partnerAdvance.id()).purpose = null;
            entity(partnerSettlement.id()).partnerSettlement = null;
            entity(partnerSettlement.id()).purpose = null;
        });

        assertTrue(page(ListKind.ORDERS, customerId).items().isEmpty(), "none of these is an order or a quotation");
        for (DocumentRow row : page(ListKind.INVOICES, customerId).items())
            assertTrue(detail(row.id(), customerId).isEmpty(), "no detail for " + row.kind());
    }

    // ------------------------------------------------------------------------------------------ whose it is

    @Test
    void nothingOfAnotherCustomerNothingTrashedAndNoRelinkedOrderIsShown() {
        Shop.Placed order = shop.place();
        long stranger = shop.customer("Andere klant BV", "Kerkstraat 1", "9000", "Gent");

        assertTrue(page(ListKind.ORDERS, stranger).items().isEmpty());
        assertHidden(order.id(), stranger);
        assertHidden(987_654_321L, order.customerId());

        /* Staff link the document to another customer: it is nobody's order any more. */
        sales.update(order.id(), shop.relinked(order(order.id()), stranger));
        assertHidden(order.id(), order.customerId());
        assertHidden(order.id(), stranger);
        assertEquals("CUSTOMER_RECORD", defaults(order.customerId()).source(),
                "the next order of the first customer no longer starts from this one");
        quotes.send(order.id(), null);
        assertHidden(order.id(), order.customerId());
        DocumentRow relinked = listed(order.id(), stranger, ListKind.ORDERS);
        assertEquals("QUOTE", relinked.kind(), "for the new customer it is a quotation Enrosed sent");
        OrderDetail detail = detail(order.id(), stranger).orElseThrow();
        assertNull(detail.destination(), "the address the first customer typed stays with that customer");
        assertNull(detail.contactName());
        assertNull(detail.revision());

        Shop.Placed trashed = shop.place();
        quotes.send(trashed.id(), null);
        assertNotNull(listed(trashed, ListKind.ORDERS));
        shop.inTransaction(() -> em.createNativeQuery("update sales_order set deleted_at = current_timestamp where id = :id")
                .setParameter("id", trashed.id()).executeUpdate());
        assertHidden(trashed.id(), trashed.customerId());
    }

    @Test
    void readingCountsNoViewAndChangesNoStatus() {
        Shop.Placed order = shop.place();
        quotes.send(order.id(), null);
        SalesOrder before = order(order.id());

        listed(order, ListKind.ORDERS);
        detail(order);
        assertTrue(pdf(order.id(), order.customerId()).isPresent());

        SalesOrder after = order(order.id());
        assertEquals(QuoteStatus.VERZONDEN, after.status());
        assertEquals(before.viewCount(), after.viewCount());
        assertNull(after.viewedAt());
        assertEquals(before.portalToken(), after.portalToken());
    }

    // ------------------------------------------------------------------------------------------ paging

    @Test
    void theListPagesNewestFirstByIdAndPricesEveryRowThatCanBePriced() {
        long customerId = shop.customer("Paginaklant BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();
        long first = sent(customerId, productId);
        long second = sent(customerId, productId);
        long third = sent(customerId, productId);

        DocumentPage page = shop.inTransaction(() -> documents.page(ListKind.ORDERS, null, 2, customerId));
        assertEquals(List.of(third, second), page.items().stream().map(DocumentRow::id).toList());
        assertEquals(second, page.nextCursor());
        DocumentPage rest = shop.inTransaction(() -> documents.page(ListKind.ORDERS, page.nextCursor(), 2, customerId));
        assertEquals(List.of(first), rest.items().stream().map(DocumentRow::id).toList());
        assertNull(rest.nextCursor());
        for (DocumentRow row : page.items()) assertEquals(0, new BigDecimal("1597.20").compareTo(row.totalInclVat()));
        assertTrue(shop.inTransaction(() -> documents.page(ListKind.INVOICES, null, 2, customerId)).items().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ delivery defaults

    @Test
    void theDeliveryStepStartsFromTheLastOrderElseTheRecordElseNothing() {
        long bare = shop.customer("Zonder adres BV", null, null, null);
        DeliveryDefaults none = defaults(bare);
        assertEquals("NONE", none.source());
        assertNull(none.fulfillment());
        assertNull(none.pickupLocationId());
        assertNull(none.destination());
        assertNull(none.contactName());
        assertNull(none.phone());

        long recorded = shop.customer("Met adres BV", "Bloemenlaan 5", "2000", "Antwerpen");
        DeliveryDefaults record = defaults(recorded);
        assertEquals("CUSTOMER_RECORD", record.source());
        assertEquals("DELIVERY", record.fulfillment());
        assertEquals("BE", record.destination().countryCode());
        assertEquals("2000", record.destination().postalCode());
        assertEquals("Antwerpen", record.destination().city());
        assertEquals("Bloemenlaan 5", record.destination().address());
        assertNull(record.contactName(), "the page fills the contact from the profile");
        assertNull(record.phone());

        Shop.Placed order = shop.place();
        DeliveryDefaults last = defaults(order.customerId());
        assertEquals("LAST_ORDER", last.source());
        assertEquals("DELIVERY", last.fulfillment());
        assertNull(last.pickupLocationId());
        assertEquals("BE", last.destination().countryCode());
        assertEquals("3980", last.destination().postalCode());
        assertEquals("Tessenderlo", last.destination().city());
        assertEquals("Industrieweg 1", last.destination().address());
        assertEquals("Jan Besteller", last.contactName());
        assertEquals("+32 13 00 00 00", last.phone());

        /* A newer order wins, also once it is cancelled; a trashed one no longer counts. */
        long newer = secondOrder(order, "Havenlaan 9", "8400", "Oostende");
        assertEquals("Havenlaan 9", defaults(order.customerId()).destination().address());
        quotes.cancel(newer, null, false);
        assertEquals("Oostende", defaults(order.customerId()).destination().city());
        shop.inTransaction(() -> em.createNativeQuery("update sales_order set deleted_at = current_timestamp where id = :id")
                .setParameter("id", newer).executeUpdate());
        assertEquals("Industrieweg 1", defaults(order.customerId()).destination().address());
    }

    @Test
    void aValueTheOrderFormWouldRefuseIsLeftOutOfThePrefill() {
        long customerId = shop.customer("Vreemd adres BV", "Bloemenlaan 5", "2000", "Antwerpen");
        shop.inTransaction(() -> em.createQuery("update " + be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity.class.getName()
                        + " c set c.address = :address, c.postalCode = :postalCode, c.city = :city, c.countryCode = 'ZZ' where c.id = :id")
                .setParameter("address", "Eerste regel\nTweede regel").setParameter("postalCode", "1".repeat(25))
                .setParameter("city", "x".repeat(101)).setParameter("id", customerId).executeUpdate());
        DeliveryDefaults record = defaults(customerId);
        assertEquals("CUSTOMER_RECORD", record.source());
        assertNull(record.destination().address(), "an address of two lines");
        assertNull(record.destination().postalCode(), "a postal code that is too long");
        assertNull(record.destination().city(), "a city that is too long");
        assertNull(record.destination().countryCode(), "a country Enrosed does not deliver to");
        shop.inTransaction(() -> em.createQuery("update " + be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity.class.getName()
                + " c set c.countryCode = 'BE' where c.id = :id").setParameter("id", customerId).executeUpdate());

        /* A collection point that is no longer offered: the customer chooses again. */
        Shop.Placed order = shop.place();
        shop.inTransaction(() -> deliveries.save(order.id(), new WebOrderDeliveries.Delivery(order.customerId(),
                WebOrderDeliveries.PICKUP, null, null, null, 987_654L, "Oud afhaalpunt", "Oude straat 1",
                "Jan\tBesteller", "+32 13\n00 00 00", null)));
        DeliveryDefaults pickup = defaults(order.customerId());
        assertEquals("LAST_ORDER", pickup.source());
        assertNull(pickup.fulfillment());
        assertNull(pickup.pickupLocationId());
        assertNull(pickup.destination(), "the last order was collected: there is no address to start from");
        assertNull(pickup.contactName(), "a control character in the name");
        assertNull(pickup.phone(), "a phone number of two lines");
    }

    @Test
    void aCollectedOrderHasNoDestinationInTheDetailNorInThePrefill() {
        Shop.Placed order = shop.place();
        long point = 987_655L;
        org.mockito.Mockito.doReturn(List.of(new StockLocation(point, "LOC-PICKUP", "Intern magazijn",
                StockLocation.Kind.WAREHOUSE, "Intern adres", true, true, false, 0,
                true, "Magazijn Tessenderlo", "Industrieweg 1, 3980 Tessenderlo", null, 0)))
                .when(stock).publicPickupLocations();
        shop.inTransaction(() -> deliveries.save(order.id(), new WebOrderDeliveries.Delivery(order.customerId(),
                WebOrderDeliveries.PICKUP, null, null, null, point, "Magazijn Tessenderlo",
                "Industrieweg 1, 3980 Tessenderlo", "Jan Besteller", "+32 13 00 00 00", null)));

        OrderDetail detail = detail(order);
        assertEquals("PICKUP", detail.fulfillment());
        assertEquals(point, detail.pickupLocation().id());
        assertEquals("Magazijn Tessenderlo", detail.pickupLocation().label());
        assertNull(detail.destination(), "a collection goes nowhere: no country without an address");
        assertEquals("Jan Besteller", detail.contactName());

        DeliveryDefaults last = defaults(order.customerId());
        assertEquals("LAST_ORDER", last.source());
        assertEquals("PICKUP", last.fulfillment());
        assertEquals(point, last.pickupLocationId());
        assertNull(last.destination());
        assertEquals("Jan Besteller", last.contactName());
        assertEquals("+32 13 00 00 00", last.phone());
    }

    // ------------------------------------------------------------------------------------------ nothing internal

    @Test
    void noAnswerRecordHasAFieldThatCouldCarrySomethingInternal() {
        List<String> forbidden = List.of("internal", "token", "cost", "margin", "payment", "paid", "sentat",
                "viewcount", "actor");
        for (Class<?> answer : List.of(AccountOrderDtos.DeliveryDefaults.class, AccountOrderDtos.DocumentRow.class,
                AccountOrderDtos.DocumentPage.class, AccountOrderDtos.OrderDetail.class, AccountOrderDtos.DetailLine.class,
                AccountOrderDtos.DetailExtraLine.class, AccountOrderDtos.DetailTotals.class, AccountOrderDtos.PickupView.class,
                AccountOrderDtos.OrderReceipt.class)) {
            assertTrue(answer.isRecord(), answer.getSimpleName());
            Arrays.stream(answer.getRecordComponents()).map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .forEach(name -> forbidden.forEach(word -> assertFalse(name.contains(word),
                            answer.getSimpleName() + "." + name)));
        }
        assertEquals(List.of("id", "number", "revision", "status"),
                Arrays.stream(AccountOrderDtos.OrderReceipt.class.getRecordComponents()).map(c -> c.getName()).toList(),
                "the receipt of a write is exactly what its replay stores");
    }

    @Test
    void theCustomerReadsDatesByTheBelgianDayWhateverTheServersZone() {
        /* 00:30 in Brussels (summer time) is still the day before in UTC, the zone of the server. */
        assertEquals(LocalDate.of(2026, 10, 8), AccountDocuments.date(Instant.parse("2026-10-07T22:30:00Z")));
        assertEquals(LocalDate.of(2026, 12, 1), AccountDocuments.date(Instant.parse("2026-11-30T23:30:00Z")));
        assertEquals(LocalDate.of(2026, 10, 7), AccountDocuments.date(Instant.parse("2026-10-07T21:59:00Z")));
        assertNull(AccountDocuments.date(null));
    }

    @Test
    void theStatusFollowsTheFirstRuleThatMatches() {
        Shop.Placed placed = shop.place();
        SalesOrder order = order(placed.id());
        WebOrders.Row row = shop.inTransaction(() -> webOrders.find(placed.id()).orElseThrow());
        LocalDate today = LocalDate.now();

        assertEquals("RECEIVED", AccountDocuments.statusOf(order, row, false, false, today).code());
        assertEquals("IN_PROCESSING", AccountDocuments.statusOf(order, row, false, true, today).code(),
                "a draft invoice exists");
        assertEquals("CONFIRMED", AccountDocuments.statusOf(order, row, true, true, today).code());
        assertEquals("ORDER", AccountDocuments.kindOf(order, row, placed.customerId()));
        assertEquals("QUOTE", AccountDocuments.kindOf(order, null, placed.customerId()));
        assertTrue(AccountDocuments.visible(order, row, placed.customerId()));
        assertFalse(AccountDocuments.visible(order, null, placed.customerId()), "a concept without an order row");
        assertFalse(AccountDocuments.visible(order, row, placed.customerId() + 1));
        assertFalse(AccountDocuments.hasPdf(order));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void assertHidden(long id, long customerId) {
        for (ListKind kind : ListKind.values())
            assertTrue(page(kind, customerId).items().stream().noneMatch(row -> row.id() == id),
                    "document " + id + " is listed under " + kind);
        assertTrue(detail(id, customerId).isEmpty(), "document " + id + " has a detail");
        assertTrue(pdf(id, customerId).isEmpty(), "document " + id + " has a PDF");
    }

    private DocumentRow listed(Shop.Placed order, ListKind kind) {
        return listed(order.id(), order.customerId(), kind);
    }

    private DocumentRow listed(long id, long customerId, ListKind kind) {
        return page(kind, customerId).items().stream().filter(row -> row.id() == id).findFirst()
                .orElseThrow(() -> new AssertionError("document " + id + " is not listed under " + kind));
    }

    /** A fresh read: outside a transaction this test's own request would answer from what it read before. */
    private DocumentPage page(ListKind kind, long customerId) {
        return shop.inTransaction(() -> documents.page(kind, null, AccountDocuments.MAX_PAGE, customerId));
    }

    private OrderDetail detail(Shop.Placed order) {
        return detail(order.id(), order.customerId()).orElseThrow();
    }

    private java.util.Optional<OrderDetail> detail(long id, long customerId) {
        return shop.inTransaction(() -> documents.detail(id, customerId));
    }

    private java.util.Optional<be.enrosed.sales.application.port.out.QuoteDocumentRenderer.Document> pdf(long id, long customerId) {
        return shop.inTransaction(() -> documents.pdf(id, customerId, null));
    }

    private DeliveryDefaults defaults(long customerId) {
        return shop.inTransaction(() -> documents.deliveryDefaults(customerId));
    }

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private SalesOrderEntity entity(long id) {
        return em.find(SalesOrderEntity.class, id);
    }

    /** A staff quote: 120 pieces at 10,00 with 120,00 fixed freight, 1.320,00 excl. VAT. */
    private SalesOrder quote(long customerId, long productId, String note) {
        SalesOrder created = sales.create(customerId, "BE", "DAP");
        SalesOrder filled = shop.edited(order(created.id()), productId, 120, "10.00", "120.00");
        return sales.update(created.id(), note == null ? filled : withCountryAndNote(filled, "BE", note));
    }

    private long sent(long customerId, long productId) {
        SalesOrder quote = quote(customerId, productId, null);
        quotes.send(quote.id(), null);
        return quote.id();
    }

    private SalesOrder invoiceDraft(long customerId, long productId) {
        return sales.createInvoiceFrom(quote(customerId, productId, null).id());
    }

    private SalesOrder creditNote(long invoiceId) {
        return sales.createCreditNote(invoiceId, new SalesOrderService.CreditNoteRequest(CreditReason.PRICE_CORRECTION,
                List.of(), List.of(new SalesOrderService.CreditAmount("Correctie", BigDecimal.TEN)), false, null));
    }

    /** A second website order of the same customer, delivered somewhere else. @return its id */
    private long secondOrder(Shop.Placed first, String address, String postalCode, String city) {
        return shop.inTransaction(() -> {
            SalesOrder draft = sales.createWebsiteRequest(first.customerId(), "BE", "DAP");
            SalesOrder saved = sales.updateByCustomer(draft.id(), shop.edited(draft, first.productId(), 120, "10.00", "120.00"));
            deliveries.save(saved.id(), new WebOrderDeliveries.Delivery(first.customerId(), WebOrderDeliveries.DELIVERY,
                    address, postalCode, city, null, null, null, "Jan Besteller", "+32 13 00 00 00", null));
            PricedOrder priced = sales.price(saved);
            WebOrderSnapshot snapshot = WebOrderSnapshot.of(saved, priced, 1, Instant.now(), "NL",
                    WebOrderDeliveries.DELIVERY, Map.of(first.productId(), 12), Map.of());
            webOrders.create(saved.id(), first.customerId(), first.accountId(), "buyer@login.example", "NL",
                    snapshot.toJson(), snapshot.complete() ? WebOrderTerms.of(priced) : null);
            return saved.id();
        });
    }

    private static SalesOrder withCountryAndNote(SalesOrder order, String countryCode, String note) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), countryCode, order.orderDate(),
                order.validUntil(), order.status(), order.incoterm(), order.paymentTerms(), note,
                order.markupMode(), order.orderMarkupPct(), order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(), order.deliveryTerms(),
                order.freight(), order.manualFreightEur(), order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                FreightPricingStrategy.FIXED, order.freightRatePerCbmEur(), order.freightCarrierId(),
                order.freightCarrierExtraEur(), order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(), order.lines(), order.pallets()).carrying(order);
    }
}
