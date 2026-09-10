package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceContentsEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Immutable, nonfinancial container context shared by every advance document presentation. */
@ApplicationScoped
public class PartnerAdvanceContents {
    @Inject EntityManager entities;
    @Inject ObjectMapper json;
    @Inject SalesRepositories.Orders orders;
    @Inject SourcingRepositories.PurchaseOrders purchases;
    @Inject ProductRepository products;

    public record Item(Long productId, String sku, String productName, int quantity,
                       Integer cartons, BigDecimal cbm, BigDecimal weightKg) {}
    public record Totals(int pieces, Integer cartons, BigDecimal cbm, BigDecimal weightKg, Integer pallets) {}
    public record Delivery(String destinationCountry, String departurePort, String destinationPort,
                           String loadMode, String containerType, LocalDate expectedArrival,
                           LocalDate shippedOn, LocalDate receivedOn, String deliveryWeek) {}
    public record Snapshot(Long purchaseOrderId, String purchaseOrderNumber, Long sourceQuoteId,
                           List<Item> lines, Totals totals, Delivery delivery, Instant capturedAt) {
        public Snapshot { lines = List.copyOf(lines); }
    }

    public Optional<Snapshot> find(SalesOrder order) {
        if (order == null || order.id() == null || !order.isInvoice() || !order.isPartnerAdvance()) return Optional.empty();
        return find(order.id());
    }

    public Optional<Snapshot> find(long orderId) {
        var entity = entities.find(PartnerAdvanceContentsEntity.class, orderId);
        if (entity == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(entity.snapshotJson, Snapshot.class));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read advance cargo snapshot for invoice " + orderId, failure);
        }
    }

    /** Called in the invoice transaction. Repeated calls never replace the captured cargo. */
    @Transactional
    public Optional<Snapshot> capture(SalesOrder invoice) {
        return capture(invoice, null);
    }

    /** Preserve an explicitly chosen arrival week without creating a financial product line. */
    @Transactional
    public Optional<Snapshot> capture(SalesOrder invoice, String requestedDeliveryWeek) {
        if (invoice == null || invoice.id() == null || !invoice.isInvoice() || !invoice.isPartnerAdvance()) return Optional.empty();
        orders.lockById(invoice.id());
        var existing = find(invoice.id());
        if (existing.isPresent()) return existing;
        PurchaseOrder purchase = invoice.linkedPurchaseOrderId() == null ? null
                : purchases.findById(invoice.linkedPurchaseOrderId()).orElse(null);
        SalesOrder source = invoice.sourceQuoteId() == null ? null : orders.findById(invoice.sourceQuoteId()).orElse(null);
        // A historical quote is the quantity agreement. A changed/received PO must not overwrite it.
        List<SalesOrderLine> sourceLines = source != null && Objects.equals(source.linkedPurchaseOrderId(), invoice.linkedPurchaseOrderId())
                && !source.lines().isEmpty() ? source.lines() : invoice.lines();
        List<Item> items = sourceLines.isEmpty() && purchase != null
                ? purchase.lines().stream().filter(line -> line.ordered() > 0)
                    .map(line -> item(line.productId(), line.ordered())).toList()
                : sourceLines.stream().filter(line -> line.quantity() > 0)
                    .map(line -> item(line.productId(), line.quantity())).toList();
        List<String> weeks = sourceLines.stream().map(SalesOrderLine::deliveryWeek)
                .filter(Objects::nonNull).filter(week -> !week.isBlank()).distinct().toList();
        // Advance invoices describe the container; the sales order's default PALLETS is not a transport instruction.
        Delivery delivery = new Delivery(invoice.countryCode(), purchase == null ? null : purchase.recordedDeparturePort(),
                purchase == null ? null : purchase.recordedDestinationPort(), null,
                purchase == null || purchase.containerType() == null ? null : purchase.containerType().code(),
                purchase == null ? null : purchase.expectedArrival(), purchase == null ? null : purchase.shippedOn(),
                purchase == null ? null : purchase.receivedOn(), requestedDeliveryWeek != null ? requestedDeliveryWeek
                        : weeks.size() == 1 ? weeks.getFirst() : null);
        Snapshot snapshot = new Snapshot(invoice.linkedPurchaseOrderId(), purchase == null ? null : purchase.number(),
                source == null ? null : source.id(), items, totals(items), delivery, Instant.now());
        var entity = new PartnerAdvanceContentsEntity();
        entity.salesOrderId = invoice.id(); entity.capturedAt = snapshot.capturedAt();
        try {
            entity.snapshotJson = json.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot capture cargo for advance invoice " + invoice.id(), failure);
        }
        entities.persist(entity);
        entities.flush();
        return Optional.of(snapshot);
    }

    private Item item(Long productId, int quantity) {
        var product = productId == null ? null : products.findById(productId).orElse(null);
        Carton carton = product == null ? null : product.carton();
        Integer cartons = carton == null || carton.piecesPerCarton() <= 0 ? null : carton.cartonsFor(quantity);
        BigDecimal cbm = cartons == null || carton.dimensions() == null || carton.cbm().signum() <= 0
                ? null : carton.cbm().multiply(BigDecimal.valueOf(cartons));
        BigDecimal weight = cartons == null || carton.weightKg() == null || carton.weightKg().signum() <= 0
                ? null : carton.weightKg().multiply(BigDecimal.valueOf(cartons));
        return new Item(productId, product == null ? null : product.sku(),
                product == null ? "Artikel " + productId : product.name(), quantity, cartons, cbm, weight);
    }

    private static Totals totals(List<Item> items) {
        Integer cartons = items.isEmpty() || items.stream().anyMatch(item -> item.cartons() == null)
                ? null : items.stream().mapToInt(Item::cartons).sum();
        return new Totals(items.stream().mapToInt(Item::quantity).sum(), cartons,
                sumKnown(items, Item::cbm), sumKnown(items, Item::weightKg), null);
    }

    private static BigDecimal sumKnown(List<Item> items, Function<Item, BigDecimal> field) {
        if (items.isEmpty() || items.stream().anyMatch(item -> field.apply(item) == null)) return null;
        return items.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void delete(long orderId) {
        var entity = entities.find(PartnerAdvanceContentsEntity.class, orderId);
        if (entity != null) entities.remove(entity);
    }
}
