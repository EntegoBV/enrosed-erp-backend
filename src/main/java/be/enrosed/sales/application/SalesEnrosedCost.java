package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockBatchSaleCost;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Enrosed kost inside a sales document's goods cost (Emre, 2026-11-06: Enrosed pays it to nobody, so Resultaat
 * counts it as revenue, not as cost). A line that left stock carries the batches' cost: its Enrosed kost is the takes'
 * value with it minus without it (none on a container sale, which counts without it), the pieces no batch could give
 * at the expected cost's part. Any other line froze a product's cost: its part is that of the newest cost-history row
 * with that cost ({@link ProductCostHistoryService.EnrosedParts}); a credit note that copied a shipment's stock cost
 * takes the shipment's part. A partner settlement counts the container's external cost and holds none.
 * Positive, between zero and the document's cost; the caller signs it.
 */
@ApplicationScoped
public class SalesEnrosedCost {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Inject Instance<ProductCostHistoryService> history;
    @Inject Instance<StockBatchService> batches;
    @Inject Instance<StockService> stock;
    @Inject Instance<SalesEnrosedCostCache> cache;

    /** One read of the parts and the takes for a request that values every document; outside a request nothing happens. */
    public void preloadForRequest() {
        try {
            if (cache == null || !cache.isResolvable() || !history.isResolvable()) return;
            SalesEnrosedCostCache held = cache.get();
            if (held.parts() != null) return;
            held.fill(history.get().enrosedParts(null), batches.isResolvable() ? batches.get().saleCosts() : Map.of());
        } catch (RuntimeException outsideRequest) {
            // No request to keep them for: each document reads its own.
        }
    }

    public BigDecimal of(SalesOrder order, PricedOrder priced) {
        if (order == null || priced == null || order.isPartnerAdvance() || order.purpose() == SalesPurpose.PARTNER_SETTLEMENT
                || history == null || !history.isResolvable()) return ZERO;
        List<PricedOrder.Line> lines = priced.lines().stream()
                .filter(line -> line.productId() != null && !line.unavailable() && line.quantity() > 0).toList();
        if (lines.isEmpty()) return ZERO;
        SalesEnrosedCostCache held = preloaded();
        ProductCostHistoryService.EnrosedParts parts = held != null ? held.parts()
                : history.get().enrosedParts(lines.stream().map(PricedOrder.Line::productId).distinct().toList());
        Map<Long, Map<Long, StockBatchSaleCost>> shipments = new HashMap<>();
        Map<Long, BigDecimal> stockUnits = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PricedOrder.Line line : lines) {
            BigDecimal unit;
            if (line.stockUnitCost() != null && order.id() != null) {
                StockBatchSaleCost taken = shipments.computeIfAbsent(order.id(), id -> takes(id, held)).get(line.productId());
                unit = stockUnits.computeIfAbsent(line.productId(), id -> stockUnit(order, line, taken, parts));
            } else {
                unit = parts.enrosedIn(line.productId(), line.landedUnitCost());
                if (unit.signum() == 0 && order.isCreditNote() && order.creditedInvoiceId() != null) {
                    unit = creditedUnit(line, shipments.computeIfAbsent(order.creditedInvoiceId(), id -> takes(id, held)).get(line.productId()));
                }
            }
            total = total.add(unit.min(Money.nz(line.landedUnitCost())).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(line.quantity())));
        }
        return Money.money(total.min(Money.nz(priced.totals().costTotal()).max(BigDecimal.ZERO)));
    }

    /**
     * The Enrosed kost in one unit of the cost stamped at shipment, by the stamp's own rule (SalesOrderService
     * stampStockCosts): the takes' value with it minus without it, plus the pieces without a batch or cost at the
     * expected cost's part, over the units that left.
     */
    private BigDecimal stockUnit(SalesOrder order, PricedOrder.Line line, StockBatchSaleCost cost, ProductCostHistoryService.EnrosedParts parts) {
        BigDecimal expected = parts.enrosedIn(line.productId(), line.expectedUnitCost());
        if (cost == null || cost.pieces() <= 0) return expected;
        BigDecimal factor = BigDecimal.valueOf(unitPieces(line.productId()));
        boolean exclEnrosed = order.sourcePurchaseOrderId() != null && order.partnerPurchaseOrderId() == null;
        BigDecimal batchPart = exclEnrosed || cost.valueEur() == null ? BigDecimal.ZERO
                : cost.valueEur().subtract(Objects.requireNonNullElse(cost.valueExclEnrosedEur(), cost.valueEur())).max(BigDecimal.ZERO);
        BigDecimal value = batchPart.add(expected.multiply(BigDecimal.valueOf(cost.piecesWithoutCost())).divide(factor, 10, RoundingMode.HALF_UP));
        return value.multiply(factor).divide(BigDecimal.valueOf(cost.pieces()), 4, RoundingMode.HALF_UP);
    }

    /**
     * A credit note's line copied its invoice's stock cost: when that is the takes' cost with the Enrosed kost (every
     * piece from a batch with a cost), the line holds the same part per unit; otherwise none is known.
     */
    private BigDecimal creditedUnit(PricedOrder.Line line, StockBatchSaleCost cost) {
        if (cost == null || cost.pieces() <= 0 || cost.piecesWithoutCost() > 0 || cost.valueEur() == null) return BigDecimal.ZERO;
        BigDecimal factor = BigDecimal.valueOf(unitPieces(line.productId()));
        BigDecimal pieces = BigDecimal.valueOf(cost.pieces());
        BigDecimal withEnrosed = cost.valueEur().multiply(factor).divide(pieces, 4, RoundingMode.HALF_UP);
        if (withEnrosed.compareTo(Money.unit(line.landedUnitCost())) != 0) return BigDecimal.ZERO;
        BigDecimal without = Objects.requireNonNullElse(cost.valueExclEnrosedEur(), cost.valueEur());
        return cost.valueEur().subtract(without).max(BigDecimal.ZERO).multiply(factor).divide(pieces, 4, RoundingMode.HALF_UP);
    }

    private Map<Long, StockBatchSaleCost> takes(long orderId, SalesEnrosedCostCache held) {
        if (held != null && held.takes() != null) return held.takes().getOrDefault(orderId, Map.of());
        return batches != null && batches.isResolvable() ? batches.get().saleCost(orderId) : Map.of();
    }

    private int unitPieces(long productId) {
        try {
            return stock != null && stock.isResolvable() ? Math.max(1, stock.get().unitPieces(productId)) : 1;
        } catch (RuntimeException gone) {
            return 1;
        }
    }

    private SalesEnrosedCostCache preloaded() {
        try {
            if (cache == null || !cache.isResolvable()) return null;
            SalesEnrosedCostCache held = cache.get();
            return held.parts() == null ? null : held;
        } catch (RuntimeException outsideRequest) {
            return null;
        }
    }
}
