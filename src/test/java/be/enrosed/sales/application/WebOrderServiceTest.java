package be.enrosed.sales.application;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderPreviewRequest;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderReceipt;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderRequest;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.Destination;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.ItemRequest;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer write side on real rows: what placing stores, what the minimum order value
 * counts, what a change keeps and tells staff, and every refusal once the order is no
 * longer the customer's to change. The service is called as the idempotency service calls
 * it, inside one transaction; the HTTP rules are in CustomerOrderResourceHttpTest.
 */
@QuarkusTest
class WebOrderServiceTest {
    private static final Destination TESSENDERLO = new Destination("BE", "3980", "Tessenderlo", "Industrieweg 1");

    @Inject WebOrderService service;
    @Inject PublicQuoteService publicQuotes;
    @Inject SalesOrderService sales;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject SalesRepositories.Events events;
    @Inject StockService stock;
    @Inject EntityManager em;

    private final Shop shop = new Shop();
    private final List<Long> ownProducts = new ArrayList<>();
    private final List<Long> ownLocations = new ArrayList<>();
    private long customerId;
    private long roses;
    private CustomerSession session;

    @BeforeEach
    void aCustomerWithALogin() {
        customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        roses = shop.product();
        session = login("buyer@login.example");
    }

    @AfterEach
    void removeRows() {
        shop.remove();
        shop.inTransaction(() -> ownProducts.forEach(id -> {
            ProductEntity product = em.find(ProductEntity.class, id);
            if (product != null) em.remove(product);
        }));
        ownLocations.forEach(stock::deleteLocation);
        ownProducts.clear();
        ownLocations.clear();
    }

    @Test
    void placingStoresTheDocumentTheDeliveryTheOrderRowAndWhatTheErpPriced() {
        OrderRequest request = order(List.of(new ItemRequest(roses, 6)), with -> {});
        PublicQuoteDtos.EstimateResponse estimate = service.previewByCustomer(preview(request, null), session);

        OrderReceipt receipt = place(request);

        assertEquals(1, receipt.revision());
        assertEquals("RECEIVED", receipt.status());
        SalesOrder stored = read(receipt.id());
        assertEquals(receipt.number(), stored.number());
        assertEquals(customerId, stored.customerId());
        assertEquals(QuoteStatus.CONCEPT, stored.status());
        assertEquals("WEBSITE", stored.rawSalesChannel());
        assertEquals("DAP", stored.incoterm());
        assertEquals("Graag voor donderdag", stored.notes());
        assertEquals(72, stored.lines().getFirst().quantity());
        assertEquals(0, new BigDecimal("10").compareTo(stored.lines().getFirst().unitPriceEur()));
        assertEquals("[WEBSITE_AANVRAAG] " + stored.number()
                + "\nWebsitebestelling van een ingelogde klant; bindend na bevestiging door Enrosed."
                + "\nBesteld via klantlogin buyer@login.example"
                + "\nContact op bestelling: Jan Besteller · +32 13 00 00 00", stored.internalNotes());

        WebOrderDeliveries.Delivery delivery = delivery(receipt.id());
        assertEquals(new WebOrderDeliveries.Delivery(customerId, "DELIVERY", "Industrieweg 1", "3980", "Tessenderlo",
                null, null, null, "Jan Besteller", "+32 13 00 00 00", null), delivery);

        WebOrders.Row row = row(receipt.id());
        assertEquals(customerId, row.customerId());
        assertEquals(session.accountId(), row.accountId());
        assertEquals("buyer@login.example", row.accountEmail());
        assertEquals("NL", row.language());
        assertEquals(1, row.revision());
        assertNotNull(row.placedAt());
        assertNull(row.processingStartedAt());
        assertTrue(WebOrders.customerMayChange(stored, row, false));

        /* The snapshot is the ERP's own pricing of the saved document, and it is what the page showed. */
        PricedOrder priced = sales.price(stored);
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertTrue(snapshot.complete());
        assertEquals(WebOrderTerms.of(priced), row.orderedTerms());
        assertEquals("NL", snapshot.language());
        assertEquals("BE", snapshot.countryCode());
        assertEquals("DELIVERY", snapshot.fulfillment());
        assertEquals("Graag voor donderdag", snapshot.notes());
        assertEquals(6, snapshot.lines().getFirst().cartons());
        assertEquals(12, snapshot.lines().getFirst().piecesPerCarton());
        assertEquals(72, snapshot.lines().getFirst().quantity());
        assertEquals("CALCULATED", snapshot.totals().shippingStatus());
        assertEquals(0, new BigDecimal("720").compareTo(snapshot.totals().goods()));
        assertEquals(0, estimate.totals().goodsNet().compareTo(snapshot.totals().goods()));
        assertEquals(0, estimate.totals().shippingNet().compareTo(snapshot.totals().shipping()));
        assertEquals(0, estimate.totals().totalNet().compareTo(snapshot.totals().totalExclVat()));
        assertEquals(0, estimate.totals().totalInclVat().compareTo(snapshot.totals().totalInclVat()));
        assertEquals(0, priced.totals().total().compareTo(snapshot.totals().totalExclVat()));

        List<QuoteEvent> history = history(receipt.id());
        QuoteEvent ordered = history.stream().filter(event -> event.type() == QuoteEvent.Type.KLANT_BESTELD)
                .findFirst().orElseThrow();
        assertTrue(ordered.byCustomer());
        assertEquals("Bestelling geplaatst via klantlogin buyer@login.example", ordered.summary());
        assertTrue(history.stream().noneMatch(event -> event.type() == QuoteEvent.Type.IN_VERWERKING));
    }

    @Test
    void theMinimumCountsOnlyWhatTheErpCanPrice() {
        long unpriced = product(product -> {
            product.fixedSalesPriceEur = null;
            product.landedCostEur = null;
        });

        assertEquals(Map.of("items", "MINIMUM_NOT_MET"),
                refusedFields(order(List.of(new ItemRequest(roses, 2)), with -> {})));

        /* A product still to be priced adds nothing, however many cartons of it are asked. */
        OrderRequest partly = order(List.of(new ItemRequest(roses, 2), new ItemRequest(unpriced, 500)), with -> {});
        assertEquals(Map.of("items", "MINIMUM_NOT_MET"), refusedFields(partly));
        PublicQuoteDtos.EstimateResponse estimate = service.previewByCustomer(preview(partly, null), session);
        assertTrue(estimate.validation().messageCodes().contains("PRICE_TO_CONFIRM"), estimate.validation().toString());
        assertFalse(estimate.validation().meetsMinimum());
        assertEquals(0, new BigDecimal("600").compareTo(estimate.validation().minimumOrderNet()));
        assertEquals(0, new BigDecimal("360").compareTo(estimate.validation().minimumShortfallNet()));
        assertNull(estimate.totals().goodsNet());
        assertTrue(documents().isEmpty(), "a refused order leaves nothing behind");

        /* With enough priced cartons the same basket is an order, to be approved once the price is known. */
        OrderReceipt receipt = place(order(List.of(new ItemRequest(roses, 6), new ItemRequest(unpriced, 3)), with -> {}));
        WebOrders.Row row = row(receipt.id());
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertFalse(snapshot.complete());
        assertNull(row.orderedTerms());
        WebOrderSnapshot.Line open = snapshot.lines().stream().filter(line -> line.productId() == unpriced)
                .findFirst().orElseThrow();
        assertNull(open.unitPrice());
        assertNull(open.net());
        assertEquals(3, open.cartons());
    }

    @Test
    void unknownCartonContentLeavesTheOrderOpenForApproval() {
        long loose = product(product -> {
            product.piecesPerCarton = 1;
            product.cartonLengthCm = null;
            product.cartonWidthCm = null;
            product.cartonHeightCm = null;
            product.cartonWeightKg = null;
        });

        OrderReceipt receipt = place(order(List.of(new ItemRequest(roses, 6), new ItemRequest(loose, 4)), with -> {}));

        SalesOrder stored = read(receipt.id());
        SalesOrderLine line = stored.lines().stream().filter(candidate -> candidate.productId() == loose)
                .findFirst().orElseThrow();
        assertEquals(0, line.quantity(), "pieces are not invented from an unknown carton");
        assertEquals(FreightState.TE_BEPALEN, stored.freight());
        assertTrue(stored.internalNotes().contains("\n[DOOSINHOUD_TE_BEPALEN] productId=" + loose + "; sku="),
                stored.internalNotes());
        assertTrue(stored.internalNotes().contains("; cartons=4; quantityPieces=TE_BEPALEN\nBesteld via klantlogin "),
                stored.internalNotes());

        WebOrders.Row row = row(receipt.id());
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertFalse(snapshot.complete());
        assertNull(row.orderedTerms());
        assertEquals("TO_CONFIRM", snapshot.totals().shippingStatus());
        assertNull(snapshot.totals().totalExclVat());
        WebOrderSnapshot.Line open = snapshot.lines().stream().filter(candidate -> candidate.productId() == loose)
                .findFirst().orElseThrow();
        assertEquals(4, open.cartons(), "the cartons the customer asked for");
        assertNull(open.piecesPerCarton());
    }

    @Test
    void theDeliveryChoiceIsReadOnceAndTheAddressIsOneLine() {
        List<ItemRequest> items = List.of(new ItemRequest(roses, 6));
        Destination noStreet = new Destination("BE", "3980", null, " ");

        for (String delivery : new String[]{null, "", "  ", " delivery ", "Delivery"}) {
            assertEquals(Map.of("destination.address", "REQUIRED", "destination.city", "REQUIRED"),
                    refusedFields(order(items, with -> {
                        with.fulfillment = delivery;
                        with.destination = noStreet;
                    })), "fulfillment '" + delivery + "'");
        }
        assertEquals(Map.of("fulfillment", "INVALID"),
                refusedFields(order(items, with -> with.fulfillment = "COURIER")));
        assertEquals(Map.of("destination.address", "INVALID"), refusedFields(order(items,
                with -> with.destination = new Destination("BE", "3980", "Tessenderlo", "Industrieweg 1\nAchterdeur"))));
        assertEquals(Map.of("destination.postalCode", "REQUIRED", "destination.address", "REQUIRED",
                        "destination.city", "REQUIRED"),
                refusedFields(order(items, with -> with.destination = new Destination("BE", null, null, null))));
        assertEquals(Map.of("privacyAccepted", "REQUIRED", "contactName", "INVALID"),
                refusedFields(order(items, with -> {
                    with.privacyAccepted = false;
                    with.contactName = "Jan\nBesteller";
                })));

        /* A padded choice is the choice: it is stored as a delivery at the typed address. */
        OrderReceipt receipt = place(order(items, with -> with.fulfillment = " delivery "));
        assertEquals("DELIVERY", delivery(receipt.id()).fulfillment());
    }

    @Test
    void aChangeKeepsLinesAndPricesAndTellsStaffWhatChanged() {
        long tulips = product(product -> product.fixedSalesPriceEur = new BigDecimal("4.00"));
        long lilies = product(product -> product.fixedSalesPriceEur = new BigDecimal("6.00"));
        OrderReceipt placed = place(order(List.of(new ItemRequest(roses, 6), new ItemRequest(tulips, 5)), with -> {}));
        SalesOrder before = read(placed.id());
        SalesOrderLine rosesBefore = line(before, roses);
        String rosesSku = sku(roses);

        /* The price list moves after the order was placed. */
        shop.inTransaction(() -> em.find(ProductEntity.class, roses).fixedSalesPriceEur = new BigDecimal("12.50"));

        OrderRequest change = order(List.of(new ItemRequest(roses, 8), new ItemRequest(lilies, 2)), with -> {
            with.baseRevision = 1;
            with.notes = "Liever vrijdag";
            with.contactName = "Mia Besteller";
            with.destination = new Destination("BE", "3500", "Hasselt", "Kempische Steenweg 10");
            with.language = "FR";
        });
        PublicQuoteDtos.EstimateResponse estimate = service.previewByCustomer(preview(change, placed.id()), session);
        assertEquals(0, new BigDecimal("10").compareTo(estimate.lines().stream()
                .filter(line -> line.productId() == roses).findFirst().orElseThrow().unitPriceNet()),
                "the estimate of a change shows the kept price");
        service.validateChange(placed.id(), change, session);

        OrderReceipt receipt = shop.inTransaction(() -> service.changeByCustomer(placed.id(), change, session));

        assertEquals(new OrderReceipt(placed.id(), placed.number(), 2, "RECEIVED"), receipt);
        SalesOrder after = read(placed.id());
        assertEquals(2, after.lines().size());
        SalesOrderLine rosesAfter = line(after, roses);
        assertEquals(rosesBefore.id(), rosesAfter.id(), "a product that stays keeps its line");
        assertEquals(96, rosesAfter.quantity());
        assertEquals(0, new BigDecimal("10").compareTo(rosesAfter.unitPriceEur()), "and the price it was ordered at");
        assertEquals(0, rosesBefore.unitCostEur().compareTo(rosesAfter.unitCostEur()), "and its cost");
        assertEquals(0, new BigDecimal("6").compareTo(line(after, lilies).unitPriceEur()), "a new product gets today's price");
        assertEquals("Liever vrijdag", after.notes());
        assertEquals(before.validUntil(), after.validUntil(), "a change never extends the validity");
        assertEquals(before.orderDate(), after.orderDate());
        assertEquals(QuoteStatus.CONCEPT, after.status());
        assertTrue(after.internalNotes().contains("\nContact op bestelling: Mia Besteller · +32 13 00 00 00"),
                after.internalNotes());

        WebOrderDeliveries.Delivery delivery = delivery(placed.id());
        assertEquals("Kempische Steenweg 10", delivery.address());
        assertEquals("3500", delivery.postalCode());
        assertEquals("Hasselt", delivery.city());
        assertEquals("Mia Besteller", delivery.contactName());
        assertEquals(customerId, delivery.customerId());

        String summary = rosesSku + ": 6 → 8 dozen, prijs " + rosesSku + " behouden: € 10,00, prijslijst nu € 12,50, "
                + sku(lilies) + " toegevoegd (2 dozen), " + sku(tulips) + " verwijderd, leveradres gewijzigd, "
                + "contact gewijzigd, opmerking gewijzigd";
        WebOrders.Row row = row(placed.id());
        assertEquals(2, row.revision());
        assertEquals(summary, row.customerChangeSummary());
        assertNotNull(row.customerChangedAt());
        assertEquals("FR", row.language());
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertEquals(2, snapshot.revision());
        assertTrue(snapshot.complete());
        assertEquals(WebOrderTerms.of(sales.price(after)), row.orderedTerms());
        assertEquals(0, new BigDecimal("10").compareTo(snapshot.lines().stream()
                .filter(line -> line.productId() == roses).findFirst().orElseThrow().unitPrice()));
        assertEquals(0, estimate.totals().totalNet().compareTo(snapshot.totals().totalExclVat()),
                "the estimate of the change is what was stored");

        QuoteEvent changed = history(placed.id()).stream()
                .filter(event -> event.type() == QuoteEvent.Type.KLANT_GEWIJZIGD).findFirst().orElseThrow();
        assertTrue(changed.byCustomer());
        assertEquals("Bestelling gewijzigd door de klant (versie 2)", changed.summary());
        assertEquals(summary, changed.detail());

        /* Sending the same again is a change that changes nothing. */
        OrderRequest same = order(List.of(new ItemRequest(roses, 8), new ItemRequest(lilies, 2)), with -> {
            with.baseRevision = 2;
            with.notes = "Liever vrijdag";
            with.contactName = "Mia Besteller";
            with.destination = new Destination("BE", "3500", "Hasselt", "Kempische Steenweg 10");
        });
        shop.inTransaction(() -> service.changeByCustomer(placed.id(), same, session));
        assertEquals("prijs " + rosesSku + " behouden: € 10,00, prijslijst nu € 12,50",
                row(placed.id()).customerChangeSummary());
        shop.inTransaction(() -> em.find(ProductEntity.class, roses).fixedSalesPriceEur = BigDecimal.TEN);
        OrderRequest again = order(List.of(new ItemRequest(roses, 8), new ItemRequest(lilies, 2)), with -> {
            with.baseRevision = 3;
            with.notes = "Liever vrijdag";
            with.contactName = "Mia Besteller";
            with.destination = new Destination("BE", "3500", "Hasselt", "Kempische Steenweg 10");
        });
        shop.inTransaction(() -> service.changeByCustomer(placed.id(), again, session));
        assertEquals("geen inhoudelijke wijziging", row(placed.id()).customerChangeSummary());
    }

    @Test
    void aChangeBelowTheMinimumIsRefusedAndLeavesTheOrderAsItWas() {
        OrderReceipt placed = place(order(List.of(new ItemRequest(roses, 6)), with -> {}));
        OrderRequest small = order(List.of(new ItemRequest(roses, 2)), with -> with.baseRevision = 1);

        PublicQuoteValidationException early = assertThrows(PublicQuoteValidationException.class,
                () -> service.validateChange(placed.id(), small, session));
        assertEquals(Map.of("items", "MINIMUM_NOT_MET"), early.fieldErrors());
        PublicQuoteValidationException late = assertThrows(PublicQuoteValidationException.class,
                () -> shop.inTransaction(() -> service.changeByCustomer(placed.id(), small, session)));
        assertEquals(Map.of("items", "MINIMUM_NOT_MET"), late.fieldErrors());

        assertEquals(72, read(placed.id()).lines().getFirst().quantity());
        assertEquals(1, row(placed.id()).revision());
        assertEquals(Map.of("baseRevision", "REQUIRED"), assertThrows(PublicQuoteValidationException.class,
                () -> service.validateChange(placed.id(), order(List.of(new ItemRequest(roses, 6)), with -> {}), session))
                .fieldErrors());
    }

    @Test
    void aChangeOrCancelIsRefusedOnceTheOrderIsNoLongerTheCustomersToChange() {
        /* Taken into processing. */
        long taken = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, taken).processingStartedAt = Instant.now());
        assertRefused(WebOrderRefusal.Code.LOCKED, null, taken, 1, session);

        /* Sent for approval. */
        long sent = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        shop.inTransaction(() -> {
            SalesOrderEntity document = em.find(SalesOrderEntity.class, sent);
            document.sentAt = Instant.now();
            document.portalToken = UUID.randomUUID().toString();
        });
        assertRefused(WebOrderRefusal.Code.LOCKED, null, sent, 1, session);

        /* Invoiced, which archives the order too. */
        long invoiced = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        shop.inTransaction(() -> sales.createInvoiceFrom(invoiced));
        assertTrue(read(invoiced).isArchived());
        assertRefused(WebOrderRefusal.Code.LOCKED, null, invoiced, 1, session);

        /* Archived only. */
        long archived = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        shop.inTransaction(() -> sales.archive(archived));
        assertRefused(WebOrderRefusal.Code.LOCKED, null, archived, 1, session);

        /* Cancelled by the customer: a second cancel and a change are both too late. */
        long cancelled = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        shop.inTransaction(() -> service.cancelByCustomer(cancelled, 1, session));
        assertRefused(WebOrderRefusal.Code.LOCKED, null, cancelled, 2, session);

        /* Another customer's order, a document that is no order, and an id that does not exist. */
        long open = place(order(List.of(new ItemRequest(roses, 6)), with -> {})).id();
        long otherCustomer = shop.customer("Fleurs Dupont SRL", "Rue des Fleurs 1", "1000", "Bruxelles");
        CustomerSession stranger = new CustomerSession(9_002L, shop.login(otherCustomer, "stranger@login.example"),
                otherCustomer, "stranger@login.example");
        assertRefused(WebOrderRefusal.Code.NOT_FOUND, null, open, 1, stranger);
        long legacy = shop.legacyRequests(1).getFirst();
        assertRefused(WebOrderRefusal.Code.NOT_FOUND, null, legacy, 1, session);
        assertRefused(WebOrderRefusal.Code.NOT_FOUND, null, 987_654_321L, 1, session);

        /* A second login of the company that still shows the first version. */
        CustomerSession colleague = login("colleague@login.example");
        OrderReceipt changed = shop.inTransaction(() -> service.changeByCustomer(open,
                order(List.of(new ItemRequest(roses, 7)), with -> with.baseRevision = 1), colleague));
        assertEquals(2, changed.revision());
        assertEquals("colleague@login.example", row(open).accountEmail());
        assertRefused(WebOrderRefusal.Code.CHANGED, 2, open, 1, session);
        assertEquals(84, read(open).lines().getFirst().quantity(), "the colleague's version stands");

        /* Twenty versions is where it ends; cancelling stays possible. */
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, open).revision = 20);
        OrderRequest once = order(List.of(new ItemRequest(roses, 9)), with -> with.baseRevision = 20);
        assertEquals(WebOrderRefusal.Code.CHANGE_LIMIT, assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.validateChange(open, once, session))).code());
        WebOrderRefusal limit = assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.changeByCustomer(open, once, session)));
        assertEquals(WebOrderRefusal.Code.CHANGE_LIMIT, limit.code());
        assertEquals(20, limit.currentRevision());
        assertEquals(21, shop.inTransaction(() -> service.cancelByCustomer(open, 20, session)).revision());
    }

    @Test
    void deliveryAndCollectionCanBeSwitched() {
        StockLocation counter = stock.saveLocation(new StockLocation(null, null, "Web order test " + UUID.randomUUID(),
                StockLocation.Kind.WAREHOUSE, "Dock 4", true, false, false, 90,
                true, "ENROSED Mol", "Rozenstraat 12, 2400 Mol", "Meld u aan de balie", 90));
        ownLocations.add(counter.id());
        OrderReceipt placed = place(order(List.of(new ItemRequest(roses, 6)), with -> {}));

        OrderRequest collect = order(List.of(new ItemRequest(roses, 6)), with -> {
            with.baseRevision = 1;
            with.fulfillment = "PICKUP";
            with.pickupLocationId = counter.id();
            with.destination = null;
        });
        shop.inTransaction(() -> service.changeByCustomer(placed.id(), collect, session));

        SalesOrder collected = read(placed.id());
        assertEquals("EXW", collected.incoterm());
        WebOrderDeliveries.Delivery pickup = delivery(placed.id());
        assertEquals("PICKUP", pickup.fulfillment());
        assertEquals(counter.id(), pickup.pickupLocationId());
        assertEquals("ENROSED Mol", pickup.pickupLabel());
        assertEquals("Rozenstraat 12, 2400 Mol", pickup.pickupAddress());
        assertNull(pickup.address());
        assertNull(pickup.postalCode());
        WebOrders.Row row = row(placed.id());
        assertEquals("levering gewijzigd naar afhaling", row.customerChangeSummary());
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertEquals("PICKUP", snapshot.fulfillment());
        assertEquals("PICKUP", snapshot.totals().shippingStatus());
        assertTrue(snapshot.complete());
        assertEquals(0, new BigDecimal("720").compareTo(snapshot.totals().totalExclVat()));

        OrderRequest deliver = order(List.of(new ItemRequest(roses, 6)), with -> with.baseRevision = 2);
        shop.inTransaction(() -> service.changeByCustomer(placed.id(), deliver, session));

        assertEquals("DAP", read(placed.id()).incoterm());
        WebOrderDeliveries.Delivery delivery = delivery(placed.id());
        assertEquals("DELIVERY", delivery.fulfillment());
        assertNull(delivery.pickupLocationId());
        assertNull(delivery.pickupLabel());
        assertEquals("3980", delivery.postalCode());
        row = row(placed.id());
        assertEquals("afhaling gewijzigd naar levering", row.customerChangeSummary());
        snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        assertEquals("CALCULATED", snapshot.totals().shippingStatus());
        assertTrue(snapshot.totals().shipping().signum() > 0);
    }

    @Test
    void cancellingClosesTheOrderWithoutAPortalLink() {
        OrderReceipt placed = place(order(List.of(new ItemRequest(roses, 6)), with -> {}));
        assertEquals(WebOrderRefusal.Code.CHANGED, assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.cancelByCustomer(placed.id(), 4, session))).code());

        OrderReceipt receipt = shop.inTransaction(() -> service.cancelByCustomer(placed.id(), 1, session));

        assertEquals(new OrderReceipt(placed.id(), placed.number(), 2, "CANCELLED"), receipt);
        SalesOrder cancelled = read(placed.id());
        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        assertNotNull(cancelled.decidedAt());
        assertNull(cancelled.portalToken(), "no link is made for an order the customer withdrew");
        assertNull(cancelled.sentAt());
        WebOrders.Row row = row(placed.id());
        assertNotNull(row.customerCancelledAt());
        assertNull(row.processingStartedAt());
        assertEquals(2, row.revision());
        QuoteEvent event = history(placed.id()).stream()
                .filter(candidate -> candidate.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertTrue(event.byCustomer());
        assertEquals("Bestelling geannuleerd door de klant", event.summary());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private CustomerSession login(String email) {
        return new CustomerSession(9_001L, shop.login(customerId, email), customerId, email);
    }

    private OrderReceipt place(OrderRequest request) {
        service.validatePlace(request, session);
        return shop.inTransaction(() -> service.placeByCustomer(request, session));
    }

    private Map<String, String> refusedFields(OrderRequest request) {
        PublicQuoteValidationException early = assertThrows(PublicQuoteValidationException.class,
                () -> service.validatePlace(request, session));
        PublicQuoteValidationException late = assertThrows(PublicQuoteValidationException.class,
                () -> shop.inTransaction(() -> service.placeByCustomer(request, session)));
        assertEquals(early.fieldErrors(), late.fieldErrors(), "the write refuses what the validation refuses");
        return early.fieldErrors();
    }

    /** Change and cancel answer the same refusal, before the lock and under it, and write nothing. */
    private void assertRefused(WebOrderRefusal.Code code, Integer currentRevision, long id, int baseRevision,
                               CustomerSession who) {
        OrderRequest change = order(List.of(new ItemRequest(roses, 9)), with -> with.baseRevision = baseRevision);
        SalesOrder before = shop.inTransaction(() -> sales.list().stream()
                .filter(order -> order.id() == id).findFirst().orElse(null));
        assertEquals(code, assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.validateChange(id, change, who))).code());
        WebOrderRefusal onChange = assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.changeByCustomer(id, change, who)));
        assertEquals(code, onChange.code());
        assertEquals(currentRevision, onChange.currentRevision());
        assertEquals(code, assertThrows(WebOrderRefusal.class,
                () -> shop.inTransaction(() -> service.cancelByCustomer(id, baseRevision, who))).code());
        /* The estimate of a change asks whose order it is and whether it is open; it knows no revision. */
        if (code == WebOrderRefusal.Code.NOT_FOUND || code == WebOrderRefusal.Code.LOCKED)
            assertEquals(code, assertThrows(WebOrderRefusal.class,
                    () -> shop.inTransaction(() -> service.previewByCustomer(preview(change, id), who))).code());
        if (before != null) {
            SalesOrder after = read(id);
            assertEquals(before.lines(), after.lines());
            assertEquals(before.status(), after.status());
        }
    }

    /** Every read in a transaction of its own: the test's own session would answer what it saw before. */
    private SalesOrder read(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private WebOrders.Row row(long id) {
        return shop.inTransaction(() -> webOrders.find(id).orElseThrow());
    }

    private WebOrderDeliveries.Delivery delivery(long id) {
        return shop.inTransaction(() -> deliveries.find(id).orElseThrow());
    }

    private List<QuoteEvent> history(long id) {
        return shop.inTransaction(() -> events.findByOrder(id));
    }

    private List<SalesOrder> documents() {
        return shop.inTransaction(() -> sales.list().stream()
                .filter(order -> order.customerId() != null && order.customerId() == customerId).toList());
    }

    private static SalesOrderLine line(SalesOrder order, long productId) {
        return order.lines().stream().filter(line -> line.productId() == productId).findFirst().orElseThrow();
    }

    private String sku(long productId) {
        return shop.inTransaction(() -> em.find(ProductEntity.class, productId).sku);
    }

    /** A published product like the shop's (12 per carton, list price 10), changed where the test needs it. */
    private long product(Consumer<ProductEntity> change) {
        long id = shop.inTransaction(() -> {
            ProductEntity product = new ProductEntity();
            product.sku = "WEB-WRITE-" + UUID.randomUUID();
            product.name = "Website order write test";
            product.active = true;
            product.websiteStatus = PublicationState.PUBLISHED;
            product.piecesPerCarton = 12;
            product.inventoryKnown = true;
            product.stockQuantity = 100_000;
            product.cartonLengthCm = new BigDecimal("40");
            product.cartonWidthCm = new BigDecimal("30");
            product.cartonHeightCm = new BigDecimal("20");
            product.cartonWeightKg = new BigDecimal("5");
            product.landedCostEur = BigDecimal.ONE;
            product.fixedSalesPriceEur = BigDecimal.TEN;
            change.accept(product);
            em.persist(product);
            em.flush();
            return product.id;
        });
        ownProducts.add(id);
        return id;
    }

    private static OrderPreviewRequest preview(OrderRequest request, Long orderId) {
        return new OrderPreviewRequest(request.language(), request.fulfillment(), request.pickupLocationId(),
                request.destination(), request.items(), orderId);
    }

    /** What the page sends: a delivery in Tessenderlo with a contact and a remark, changed where the test needs it. */
    private static OrderRequest order(List<ItemRequest> items, Consumer<Body> change) {
        Body body = new Body();
        change.accept(body);
        return new OrderRequest(body.language, body.fulfillment, body.pickupLocationId, body.destination, items,
                body.contactName, body.phone, body.notes, body.privacyAccepted, "", null, null, body.baseRevision);
    }

    private static final class Body {
        String language = "NL";
        String fulfillment = "DELIVERY";
        Long pickupLocationId;
        Destination destination = TESSENDERLO;
        String contactName = "Jan Besteller";
        String phone = "+32 13 00 00 00";
        String notes = "Graag voor donderdag";
        Boolean privacyAccepted = true;
        Integer baseRevision;
    }
}
