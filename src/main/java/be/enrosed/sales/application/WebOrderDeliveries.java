package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesOrderDeliveryEntity;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The delivery address and contact a customer typed for one website order,
 * and the copies its invoice, split part or new copy carry. The row is never
 * locked on its own: it is written under the lock of its order, or in the
 * transaction that creates the derived document.
 */
@ApplicationScoped
public class WebOrderDeliveries {
    public static final String DELIVERY = "DELIVERY";
    public static final String PICKUP = "PICKUP";

    public record Delivery(Long customerId, String fulfillment, String address, String postalCode, String city,
                           Long pickupLocationId, String pickupLabel, String pickupAddress,
                           String contactName, String contactPhone, Long copiedFromOrderId) {}

    @Inject EntityManager entities;
    @Inject Instance<WebOrderDeliveryCache> cache;

    /** The raw row, whoever it was typed for; everything that uses the address goes through {@link #forDocument}. */
    public Optional<Delivery> find(long orderId) {
        Map<Long, Delivery> preloaded = preloaded();
        if (preloaded != null) return Optional.ofNullable(preloaded.get(orderId));
        SalesOrderDeliveryEntity entity = entities.find(SalesOrderDeliveryEntity.class, orderId);
        return entity == null ? Optional.empty() : Optional.of(delivery(entity));
    }

    public Map<Long, Delivery> indexAll() {
        Map<Long, Delivery> rows = new LinkedHashMap<>();
        for (SalesOrderDeliveryEntity entity : entities.createQuery(
                "select d from SalesOrderDeliveryEntity d", SalesOrderDeliveryEntity.class).getResultList())
            rows.put(entity.salesOrderId, delivery(entity));
        return rows;
    }

    /** One query for a request that prices many documents; outside a request nothing happens. */
    public void preloadForRequest() {
        try {
            if (cache == null || !cache.isResolvable()) return;
            WebOrderDeliveryCache held = cache.get();
            if (held.rows() == null) held.fill(indexAll());
        } catch (RuntimeException outsideRequest) {
            // No request to keep the rows for: every lookup reads its own row.
        }
    }

    /**
     * The row that counts for this document: its own, or for a credit note
     * without one the row of the invoice it credits. A row typed for another
     * customer is void, so a re-linked document never shows, prices or ships
     * on the previous customer's address.
     */
    public Optional<Delivery> forDocument(SalesOrder order) {
        if (order == null || order.id() == null) return Optional.empty();
        Optional<Delivery> row = find(order.id());
        if (row.isEmpty() && order.isCreditNote() && order.creditedInvoiceId() != null)
            row = find(order.creditedInvoiceId());
        return row.filter(delivery -> delivery.customerId() == null
                || delivery.customerId().equals(order.customerId()));
    }

    /** Insert or replace; the caller holds the lock of the order. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(long orderId, Delivery delivery) {
        SalesOrderDeliveryEntity entity = entities.find(SalesOrderDeliveryEntity.class, orderId);
        boolean created = entity == null;
        if (created) {
            entity = new SalesOrderDeliveryEntity();
            entity.salesOrderId = orderId;
        }
        entity.customerId = delivery.customerId();
        entity.fulfillment = cut(delivery.fulfillment(), 16);
        entity.address = cut(delivery.address(), 200);
        entity.postalCode = cut(delivery.postalCode(), 24);
        entity.city = cut(delivery.city(), 100);
        entity.pickupLocationId = delivery.pickupLocationId();
        entity.pickupLabel = cut(delivery.pickupLabel(), 255);
        entity.pickupAddress = cut(delivery.pickupAddress(), 500);
        entity.contactName = cut(delivery.contactName(), 120);
        entity.contactPhone = cut(delivery.contactPhone(), 50);
        entity.copiedFromOrderId = delivery.copiedFromOrderId();
        entity.savedAt = Instant.now();
        if (created) entities.persist(entity);
        clearPreload();
    }

    /** A derived document carries the row of its source, with the same customer; nothing when either side says no. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void copy(long sourceOrderId, long targetOrderId) {
        SalesOrderDeliveryEntity source = entities.find(SalesOrderDeliveryEntity.class, sourceOrderId);
        if (source == null || entities.find(SalesOrderDeliveryEntity.class, targetOrderId) != null) return;
        Delivery row = delivery(source);
        save(targetOrderId, new Delivery(row.customerId(), row.fulfillment(), row.address(), row.postalCode(),
                row.city(), row.pickupLocationId(), row.pickupLabel(), row.pickupAddress(), row.contactName(),
                row.contactPhone(), sourceOrderId));
    }

    /**
     * The customer freight is priced for: the record, delivered at the
     * address of this website order when it has one. Company country, VAT
     * number, fiscal representative and language stay the record's.
     */
    public Customer pricingCustomer(SalesOrder order, Customer customer) {
        if (order == null || customer == null || !"WEBSITE".equalsIgnoreCase(order.rawSalesChannel())) return customer;
        return forDocument(order)
                .filter(row -> DELIVERY.equals(row.fulfillment()) && row.postalCode() != null && !row.postalCode().isBlank())
                .map(row -> customer.withDeliveryAddress(row.address(), row.postalCode(), row.city()))
                .orElse(customer);
    }

    private Map<Long, Delivery> preloaded() {
        try {
            return cache == null || !cache.isResolvable() ? null : cache.get().rows();
        } catch (RuntimeException outsideRequest) {
            return null;
        }
    }

    private void clearPreload() {
        try {
            if (cache != null && cache.isResolvable()) cache.get().clear();
        } catch (RuntimeException outsideRequest) {
            // Nothing was preloaded outside a request.
        }
    }

    private static Delivery delivery(SalesOrderDeliveryEntity entity) {
        return new Delivery(entity.customerId, entity.fulfillment, entity.address, entity.postalCode, entity.city,
                entity.pickupLocationId, entity.pickupLabel, entity.pickupAddress, entity.contactName,
                entity.contactPhone, entity.copiedFromOrderId);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
