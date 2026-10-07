package be.enrosed.sales.application;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderPreviewRequest;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderReceipt;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderRequest;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.EstimateResponse;
import be.enrosed.sales.application.PublicQuoteService.OrderFacts;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a logged-in customer does to their own website order: place it, change it, cancel
 * it. Every write runs inside the transaction of the idempotency service, takes the
 * document lock without the staff gate, then the order row, and decides only on what it
 * reads after those locks. The customer is the session's, never the body's; "customer" is
 * never inferred from a missing staff identity.
 */
@ApplicationScoped
public class WebOrderService {
    /** A customer may change an order this often; the team gets a mail for each change. */
    static final int MAX_REVISION = 20;
    private static final String RECEIVED = "RECEIVED";
    private static final String CANCELLED = "CANCELLED";

    @Inject PublicQuoteService publicQuotes;
    @Inject SalesOrderService salesOrders;
    @Inject QuoteService quotes;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject Event<WebOrderEvents.Placed> placed;
    @Inject Event<WebOrderEvents.Changed> changed;
    @Inject Event<WebOrderEvents.Cancelled> cancelled;

    /** The estimate of a new order, or of an order being changed: its products keep their stored price. */
    public EstimateResponse previewByCustomer(OrderPreviewRequest request, CustomerSession session) {
        Map<Long, BigDecimal> kept = Map.of();
        if (request != null && request.orderId() != null) {
            SalesOrder order = changeable(request.orderId(), session);
            kept = PublicQuoteService.keptUnitPrices(order);
        }
        return publicQuotes.previewOrder(request, session.customerId(), kept);
    }

    /** Read-only, before the challenge is spent: the fields, the basket and the minimum order value. */
    public void validatePlace(OrderRequest request, CustomerSession session) {
        publicQuotes.validateOrder(request, session.customerId(), Map.of());
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public OrderReceipt placeByCustomer(OrderRequest request, CustomerSession session) {
        OrderFacts facts = publicQuotes.orderFacts(request, session.customerId(), Map.of());
        SalesOrder saved = publicQuotes.storeOrder(request, session.customerId(), session.email());
        deliveries.save(saved.id(), delivery(facts, session.customerId()));
        /* The ERP's own pricing of the saved document, after the delivery row: never the preview. */
        PricedOrder priced = salesOrders.price(saved);
        WebOrderSnapshot snapshot = snapshot(saved, priced, 1, facts);
        webOrders.create(saved.id(), session.customerId(), session.accountId(), session.email(),
                facts.language(), snapshot.toJson(), snapshot.complete() ? WebOrderTerms.of(priced) : null);
        events.add(new QuoteEvent(null, saved.id(), QuoteEvent.Type.KLANT_BESTELD, Instant.now(), null, true,
                "Bestelling geplaatst via klantlogin " + session.email(), null));
        placed.fire(new WebOrderEvents.Placed(saved.id(), saved.number()));
        return new OrderReceipt(saved.id(), saved.number(), 1, RECEIVED);
    }

    /**
     * Read-only, before the challenge is spent. The same refusals as under the lock, so a
     * customer whose order was taken hears that, and not that a field is wrong.
     */
    public void validateChange(long id, OrderRequest request, CustomerSession session) {
        SalesOrder order = changeable(id, session);
        if (request == null) throw new PublicQuoteValidationException(Map.of("request", "REQUIRED"));
        if (request.baseRevision() == null) throw new PublicQuoteValidationException(Map.of("baseRevision", "REQUIRED"));
        requireRevision(webOrders.find(id).orElseThrow(WebOrderService::notFound), request.baseRevision(), true);
        publicQuotes.validateOrder(request, session.customerId(), PublicQuoteService.keptUnitPrices(order));
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public OrderReceipt changeByCustomer(long id, OrderRequest request, CustomerSession session) {
        if (request == null || request.baseRevision() == null)
            throw new PublicQuoteValidationException(Map.of("baseRevision", "REQUIRED"));
        SalesOrder order = lockedOrder(id);
        WebOrders.Row row = lockedRow(id, order, session);
        requireRevision(row, request.baseRevision(), true);

        WebOrderSnapshot before = WebOrderSnapshot.fromJson(row.orderSnapshot());
        Optional<WebOrderDeliveries.Delivery> deliveryBefore = deliveries.find(id);
        Map<Long, BigDecimal> kept = PublicQuoteService.keptUnitPrices(order);
        OrderFacts facts = publicQuotes.orderFacts(request, session.customerId(), kept);
        SalesOrder saved = publicQuotes.replaceOrder(order, request, session.email());
        WebOrderDeliveries.Delivery delivery = delivery(facts, session.customerId());
        deliveries.save(id, delivery);
        PricedOrder priced = salesOrders.price(saved);
        int revision = row.revision() + 1;
        WebOrderSnapshot snapshot = snapshot(saved, priced, revision, facts);
        String summary = summary(before, snapshot, deliveryBefore.orElse(null), delivery, facts, kept);
        webOrders.recordChange(id, session.accountId(), session.email(), facts.language(), snapshot.toJson(),
                snapshot.complete() ? WebOrderTerms.of(priced) : null, summary);
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.KLANT_GEWIJZIGD, Instant.now(), null, true,
                "Bestelling gewijzigd door de klant (versie " + revision + ")", summary));
        changed.fire(new WebOrderEvents.Changed(id, saved.number(), revision, summary));
        return new OrderReceipt(id, saved.number(), revision, RECEIVED);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public OrderReceipt cancelByCustomer(long id, int baseRevision, CustomerSession session) {
        SalesOrder order = lockedOrder(id);
        WebOrders.Row row = lockedRow(id, order, session);
        requireRevision(row, baseRevision, false);
        quotes.cancelByCustomer(id);
        int revision = webOrders.recordCustomerCancel(id).revision();
        cancelled.fire(new WebOrderEvents.Cancelled(id, order.number()));
        return new OrderReceipt(id, order.number(), revision, CANCELLED);
    }

    /** The document lock, never the staff gate; then a fresh read. */
    private SalesOrder lockedOrder(long id) {
        try {
            salesOrders.lockDocumentForCustomer(id);
            return salesOrders.get(id);
        } catch (NotFoundException missing) {
            throw notFound();
        }
    }

    /** Whose order first, then whether it can still be changed; both on what the locks show. */
    private WebOrders.Row lockedRow(long id, SalesOrder order, CustomerSession session) {
        WebOrders.Row row = webOrders.lock(id).orElse(null);
        if (!WebOrders.owns(order, row, session.customerId())) throw notFound();
        if (!WebOrders.customerMayChange(order, row, orders.existsBySourceQuoteId(id)))
            throw new WebOrderRefusal(WebOrderRefusal.Code.LOCKED);
        return row;
    }

    /** The same two questions without a lock, for the estimate and the validation; they decide no write. */
    private SalesOrder changeable(long id, CustomerSession session) {
        SalesOrder order = orders.findById(id).orElseThrow(WebOrderService::notFound);
        WebOrders.Row row = webOrders.find(id).orElse(null);
        if (!WebOrders.owns(order, row, session.customerId())) throw notFound();
        if (!WebOrders.customerMayChange(order, row, orders.existsBySourceQuoteId(id)))
            throw new WebOrderRefusal(WebOrderRefusal.Code.LOCKED);
        return order;
    }

    private static void requireRevision(WebOrders.Row row, int baseRevision, boolean change) {
        if (row.revision() != baseRevision) throw new WebOrderRefusal(WebOrderRefusal.Code.CHANGED, row.revision());
        if (change && row.revision() >= MAX_REVISION)
            throw new WebOrderRefusal(WebOrderRefusal.Code.CHANGE_LIMIT, row.revision());
    }

    private static WebOrderRefusal notFound() {
        return new WebOrderRefusal(WebOrderRefusal.Code.NOT_FOUND);
    }

    private static WebOrderDeliveries.Delivery delivery(OrderFacts facts, long customerId) {
        return new WebOrderDeliveries.Delivery(customerId, facts.fulfillment(), facts.address(), facts.postalCode(),
                facts.city(), facts.pickupLocationId(), facts.pickupLabel(), facts.pickupAddress(),
                facts.contactName(), facts.phone(), null);
    }

    private static WebOrderSnapshot snapshot(SalesOrder saved, PricedOrder priced, int revision, OrderFacts facts) {
        Map<Long, Integer> unresolved = new LinkedHashMap<>();
        facts.cartons().forEach((productId, cartons) -> {
            if (facts.piecesPerCarton().getOrDefault(productId, 0) <= 0) unresolved.put(productId, cartons);
        });
        return WebOrderSnapshot.of(saved, priced, revision, Instant.now(), facts.language(), facts.fulfillment(),
                facts.piecesPerCarton(), unresolved);
    }

    /**
     * What the customer changed, for staff: the team mail, the push, the history and the
     * banner on the order. A kept price that is no longer the list price is named, so staff
     * decide about it before they take the order.
     */
    static String summary(WebOrderSnapshot before, WebOrderSnapshot after, WebOrderDeliveries.Delivery deliveryBefore,
                          WebOrderDeliveries.Delivery deliveryAfter, OrderFacts facts, Map<Long, BigDecimal> kept) {
        List<String> parts = new ArrayList<>();
        Map<Long, WebOrderSnapshot.Line> old = new LinkedHashMap<>();
        if (before != null) before.lines().forEach(line -> old.put(line.productId(), line));
        for (WebOrderSnapshot.Line line : after.lines()) {
            WebOrderSnapshot.Line known = old.remove(line.productId());
            if (known == null) {
                parts.add(line.sku() + " toegevoegd (" + dozen(line.cartons()) + " dozen)");
                continue;
            }
            if (!Objects.equals(known.cartons(), line.cartons()))
                parts.add(line.sku() + ": " + dozen(known.cartons()) + " → " + dozen(line.cartons()) + " dozen");
            BigDecimal keptPrice = kept == null ? null : kept.get(line.productId());
            BigDecimal listPrice = facts.listPrices().get(line.productId());
            if (keptPrice != null && listPrice != null && keptPrice.compareTo(listPrice) != 0)
                parts.add("prijs " + line.sku() + " behouden: " + WebOrderTerms.euro(keptPrice)
                        + ", prijslijst nu " + WebOrderTerms.euro(listPrice));
        }
        old.values().forEach(line -> parts.add(line.sku() + " verwijderd"));

        String fulfillmentBefore = deliveryBefore == null ? null : deliveryBefore.fulfillment();
        boolean pickupBefore = WebOrderDeliveries.PICKUP.equals(fulfillmentBefore);
        boolean pickupAfter = WebOrderDeliveries.PICKUP.equals(deliveryAfter.fulfillment());
        if (deliveryBefore != null && pickupBefore != pickupAfter) {
            parts.add(pickupAfter ? "levering gewijzigd naar afhaling" : "afhaling gewijzigd naar levering");
        } else if (deliveryBefore != null && pickupAfter) {
            if (!Objects.equals(deliveryBefore.pickupLocationId(), deliveryAfter.pickupLocationId()))
                parts.add("afhaalpunt gewijzigd");
        } else if (deliveryBefore != null && (differs(deliveryBefore.address(), deliveryAfter.address())
                || differs(deliveryBefore.postalCode(), deliveryAfter.postalCode())
                || differs(deliveryBefore.city(), deliveryAfter.city())
                || before != null && differs(before.countryCode(), after.countryCode()))) {
            parts.add("leveradres gewijzigd");
        }
        if (deliveryBefore != null && (differs(deliveryBefore.contactName(), deliveryAfter.contactName())
                || differs(deliveryBefore.contactPhone(), deliveryAfter.contactPhone())))
            parts.add("contact gewijzigd");
        if (before != null && differs(before.notes(), after.notes())) parts.add("opmerking gewijzigd");
        return parts.isEmpty() ? "geen inhoudelijke wijziging" : String.join(", ", parts);
    }

    private static String dozen(Integer cartons) {
        return cartons == null ? "?" : cartons.toString();
    }

    private static boolean differs(String left, String right) {
        String a = left == null ? "" : left.strip();
        String b = right == null ? "" : right.strip();
        return !a.equalsIgnoreCase(b);
    }
}
