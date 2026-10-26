package be.enrosed.inventory.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.catalog.domain.StockBatchRun;
import be.enrosed.catalog.domain.StockBatchTotals;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the screens show of the batches: the catalog's batches with the container's name and today's calculation
 * (sourcing), the supplier's name, the product and the sales order around them. Reads only, no locks.
 *
 * <p>Container names follow the one sales rule ("Herkenbare naam", else the number); a container that no longer
 * exists keeps the number the batch froze and is marked gone. A live receipt batch of a container that still exists
 * is compared with the container's calculation of today: {@code stale} when the two differ in cents (the cost
 * build-up fix's rule), so "Opnieuw toepassen" can be offered.
 */
@ApplicationScoped
public class StockBatchViews {

    private static final Logger LOG = Logger.getLogger(StockBatchViews.class);
    private static final int COST_SCALE = 4;

    private final StockBatchService batches;
    private final StockService stock;
    private final ProductService products;
    private final EntityManager entities;

    @Inject Instance<PurchaseOrderService> purchaseOrders;
    @Inject Instance<SupplierService> suppliers;
    @Inject Instance<SalesOrderService> salesOrders;

    public StockBatchViews(StockBatchService batches, StockService stock, ProductService products,
                           EntityManager entities) {
        this.batches = batches;
        this.stock = stock;
        this.products = products;
        this.entities = entities;
    }

    /* ---------------------------------------------------------------- shapes */

    /** One unit of a batch's cost, part by part; null as a whole when no part is known. */
    public record Parts(BigDecimal goodsEur, BigDecimal originEur, BigDecimal freightEur, BigDecimal dutyEur,
                        BigDecimal destinationEur, BigDecimal enrosedEur, BigDecimal separateEur) {
        static Parts of(ProductCostBreakdown parts) {
            if (parts == null || parts.isEmpty()) return null;
            return new Parts(parts.goodsEur(), parts.originEur(), parts.freightEur(), parts.dutyEur(),
                    parts.destinationEur(), parts.enrosedEur(), parts.separateEur());
        }
    }

    /** A batch as every screen shows it (batches contract §5.2, field for field). */
    public record BatchView(long id, long productId, StockBatch.Kind kind, StockBatch.Owner owner,
                            Long purchaseOrderId, String orderNumber, String containerName, boolean containerGone,
                            Long supplierId, String supplierName, Long productSupplierId, LocalDate receivedOn,
                            Instant bookedAt, int unitPieces, int piecesIn, int piecesLeft, int soldPieces,
                            int lostPieces, int earlierPieces, int otherPieces, StockBatch.CostState costState,
                            BigDecimal unitCostEur, BigDecimal unitCostExclEnrosedEur, Parts parts,
                            Instant costAppliedAt, String costAppliedBy, BigDecimal valueLeftEur, boolean next,
                            BigDecimal calcUnitEur, boolean stale, String note) {}

    public record ProductSummary(int stockPieces, int batchPieces, int unassignedPieces, int differencePieces,
                                 BigDecimal valueEur, BigDecimal partnerValueEur, boolean valueComplete,
                                 BigDecimal nextUnitCostEur, Long nextBatchId) {}

    /** The product page's Batches card. */
    public record ProductBatches(long productId, boolean started, int unitPieces, List<BatchView> batches,
                                 ProductSummary summary) {}

    public record MoveView(long id, Long batchId, String kind, int pieces, int unitPieces, BigDecimal unitCostEur,
                           String reference, Long salesOrderId, Long ticaSaleId, Long purchaseOrderId, boolean pinned,
                           boolean settles, boolean restores, boolean movementGone, Instant at, String actor) {}

    public record Moves(int total, List<MoveView> moves) {}

    /** A container's batch row: every batch field plus the product it is of. */
    public record PurchaseRow(@JsonUnwrapped BatchView batch, String sku, String productName) {}

    public record PurchaseTotals(int piecesIn, int piecesLeft, BigDecimal valueLeftEur, boolean valueComplete) {}

    /** Live batches only; {@code staleLargestEur} is signed (today's calculation minus the batch cost). */
    public record PurchaseCosts(int applied, int calculated, int card, int none, int stale,
                                BigDecimal staleLargestEur) {}

    public record PurchaseBatches(boolean started, boolean booked, List<PurchaseRow> rows, PurchaseTotals totals,
                                  PurchaseCosts costs) {}

    public record SaleBatch(Long batchId, StockBatch.Kind kind, String containerName, String orderNumber, int pieces,
                            int unitPieces, BigDecimal unitCostEur, BigDecimal unitCostExclEnrosedEur) {}

    public record SaleLine(long productId, int unitPieces, int pieces, boolean exclEnrosed, int withoutBatchPieces,
                           int withoutCostPieces, List<SaleBatch> batches) {}

    public record SaleCost(long salesOrderId, boolean shipped, List<SaleLine> lines) {}

    public record Status(boolean started, boolean paused, Instant startedAt, String startedBy, Instant pausedAt,
                         String pausedBy, int differences) {}

    public record CheckRow(long productId, String sku, String name, int unitPieces, int stockPieces, int batchPieces,
                           int unassignedPieces, int differencePieces) {}

    /* ---------------------------------------------------------------- status */

    public Status status() {
        StockBatchRun run = batches.startRun().orElse(null);
        if (run == null) return new Status(false, false, null, null, null, null, 0);
        return new Status(true, run.paused(), run.ranAt(), run.ranBy(), run.pausedAt(), run.pausedBy(),
                batches.check().size());
    }

    /* --------------------------------------------------------------- product */

    public ProductBatches forProduct(long productId) {
        Product product = products.get(productId);
        int factor = stock.unitPieces(productId);
        if (!batches.started()) {
            return new ProductBatches(productId, false, factor, List.of(), new ProductSummary(0, 0, 0, 0,
                    money(BigDecimal.ZERO), money(BigDecimal.ZERO), true, null, null));
        }
        List<StockBatch> list = batches.forProduct(productId);
        Context context = new Context(list);
        Long nextId = list.stream().filter(batch -> batch.piecesLeft() > 0).map(StockBatch::id).findFirst()
                .orElse(null);
        List<BatchView> views = new ArrayList<>(list.size());
        BigDecimal own = BigDecimal.ZERO;
        BigDecimal partner = BigDecimal.ZERO;
        boolean complete = true;
        StockBatch next = null;
        for (StockBatch batch : list) {
            BatchView view = context.view(batch, Objects.equals(batch.id(), nextId), factor);
            views.add(view);
            if (Objects.equals(batch.id(), nextId)) next = batch;
            if (batch.piecesLeft() <= 0) continue;
            BigDecimal value = batch.valueLeftEur();
            if (batch.owner() == StockBatch.Owner.PARTNER) {
                if (value != null) partner = partner.add(value);
            } else if (value == null) {
                complete = false;
            } else {
                own = own.add(value);
            }
        }
        StockBatchTotals totals = batches.totals(productId);
        BigDecimal nextUnit = next == null || next.unitCostEur() == null ? null
                : convert(next.unitCostEur(), factor, next.unitPieces());
        return new ProductBatches(product.id(), true, factor, views, new ProductSummary(totals.stockPieces(),
                totals.batchPieces(), totals.unassignedPieces(), totals.differencePieces(), money(own),
                money(partner), complete, nextUnit, nextId));
    }

    /* ----------------------------------------------------------------- moves */

    public Moves moves(long batchId, int limit) {
        if (batches.batch(batchId).isEmpty()) throw new NotFoundException("Batch", batchId);
        int size = Math.max(1, Math.min(500, limit));
        List<StockBatchMove> list = batches.moves(batchId, size);
        Set<Long> existing = existingMovements(list.stream().map(StockBatchMove::stockMovementId)
                .filter(Objects::nonNull).distinct().toList());
        List<MoveView> views = list.stream().map(move -> new MoveView(move.id(), move.batchId(), move.kind(),
                move.pieces(), move.unitPieces(), move.unitCostEur(), move.reference(), move.salesOrderId(),
                move.ticaSaleId(), move.purchaseOrderId(), move.pinned(),
                move.linkMoveId() != null && move.pieces() < 0,
                move.linkMoveId() != null && move.pieces() > 0,
                move.stockMovementId() != null && !existing.contains(move.stockMovementId()),
                move.at(), move.actor())).toList();
        return new Moves(batches.moveCount(batchId), views);
    }

    /* -------------------------------------------------------- purchase order */

    public PurchaseBatches forPurchaseOrder(long purchaseOrderId) {
        PurchaseOrder order = purchaseOrders.get().get(purchaseOrderId);
        boolean booked = order.status() == PurchaseOrderStatus.ONTVANGEN && order.isStockBooked();
        if (!batches.started()) {
            return new PurchaseBatches(false, booked, List.of(), new PurchaseTotals(0, 0, money(BigDecimal.ZERO),
                    true), new PurchaseCosts(0, 0, 0, 0, 0, money4(BigDecimal.ZERO)));
        }
        Map<Long, Integer> linePosition = new HashMap<>();
        for (int i = 0; i < order.lines().size(); i++) {
            PurchaseOrderLine line = order.lines().get(i);
            if (line.id() != null) linePosition.putIfAbsent(line.id(), i);
        }
        List<StockBatch> list = new ArrayList<>(batches.forPurchaseOrder(purchaseOrderId));
        list.sort(Comparator.comparing((StockBatch batch) -> batch.purchaseOrderLineId() == null ? null
                        : linePosition.get(batch.purchaseOrderLineId()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockBatch::id, Comparator.nullsLast(Comparator.naturalOrder())));
        Context context = new Context(list);
        Map<Long, Product> byId = new HashMap<>();
        int piecesIn = 0;
        int piecesLeft = 0;
        BigDecimal value = BigDecimal.ZERO;
        boolean complete = true;
        int applied = 0, calculated = 0, card = 0, none = 0, stale = 0;
        BigDecimal largest = BigDecimal.ZERO;
        List<PurchaseRow> rows = new ArrayList<>(list.size());
        for (StockBatch batch : list) {
            Product product = byId.computeIfAbsent(batch.productId(), this::productOrNull);
            int factor = product == null ? 1 : factorOf(batch.productId());
            BatchView view = context.view(batch, false, factor);
            rows.add(new PurchaseRow(view, product == null ? "" : nz(product.sku()),
                    product == null ? "Product #" + batch.productId() : nz(product.name())));
            piecesIn += batch.piecesIn();
            piecesLeft += batch.piecesLeft();
            if (batch.piecesLeft() <= 0) continue;
            BigDecimal left = batch.valueLeftEur();
            if (left == null) complete = false;
            else value = value.add(left);
            switch (batch.costState()) {
                case APPLIED -> applied++;
                case CALCULATED -> {
                    if (view.calcUnitEur() != null && view.calcUnitEur().signum() > 0) calculated++;
                }
                case CARD -> card++;
                case NONE -> none++;
            }
            if (view.stale()) {
                stale++;
                BigDecimal change = view.calcUnitEur().subtract(batch.unitCostEur());
                if (change.abs().compareTo(largest.abs()) > 0) largest = change;
            }
        }
        return new PurchaseBatches(true, booked, rows, new PurchaseTotals(piecesIn, piecesLeft, money(value),
                complete), new PurchaseCosts(applied, calculated, card, none, stale, money4(largest)));
    }

    /* ----------------------------------------------------------- sales order */

    public SaleCost forSalesOrder(long salesOrderId) {
        SalesOrder order = salesOrders.get().get(salesOrderId);
        if (order.goodsShippedAt() == null) return new SaleCost(salesOrderId, false, List.of());
        boolean exclEnrosed = order.sourcePurchaseOrderId() != null && order.partnerPurchaseOrderId() == null;
        List<StockBatchMove> takes = batches.takesForSalesOrder(salesOrderId);
        Map<Long, StockBatch> batchById = new HashMap<>();
        for (StockBatchMove take : takes) {
            if (take.batchId() != null) batchById.computeIfAbsent(take.batchId(),
                    id -> batches.batch(id).orElse(null));
        }
        Map<Long, String> names = containerNames(batchById.values().stream().filter(Objects::nonNull)
                .map(StockBatch::purchaseOrderId).filter(Objects::nonNull).toList());
        Map<Long, LineAcc> lines = new LinkedHashMap<>();
        for (StockBatchMove take : takes) {
            int pieces = Math.abs(take.pieces());
            LineAcc line = lines.computeIfAbsent(take.productId(), LineAcc::new);
            line.pieces += pieces;
            if (take.batchId() == null) {
                line.withoutBatch += pieces;
                continue;
            }
            if (take.unitCostEur() == null) line.withoutCost += pieces;
            StockBatch batch = batchById.get(take.batchId());
            String key = take.batchId() + "|" + take.unitPieces() + "|" + take.unitCostEur() + "|"
                    + take.unitCostExclEnrosedEur();
            SaleBatchAcc acc = line.batches.computeIfAbsent(key, ignored -> new SaleBatchAcc(take, batch));
            acc.pieces += pieces;
        }
        List<SaleLine> result = new ArrayList<>(lines.size());
        for (LineAcc line : lines.values()) {
            List<SaleBatch> list = new ArrayList<>();
            for (SaleBatchAcc acc : line.batches.values()) {
                StockBatch batch = acc.batch;
                String containerName = batch == null || batch.purchaseOrderId() == null ? null
                        : names.getOrDefault(batch.purchaseOrderId(), batch.orderNumber());
                list.add(new SaleBatch(acc.take.batchId(), batch == null ? null : batch.kind(), containerName,
                        batch == null ? null : batch.orderNumber(), acc.pieces, acc.take.unitPieces(),
                        acc.take.unitCostEur(), acc.take.unitCostExclEnrosedEur()));
            }
            int factor = productOrNull(line.productId) == null ? 1 : factorOf(line.productId);
            result.add(new SaleLine(line.productId, factor, line.pieces, exclEnrosed, line.withoutBatch,
                    line.withoutCost, list));
        }
        return new SaleCost(salesOrderId, true, result);
    }

    /* ----------------------------------------------------------------- check */

    public List<CheckRow> check() {
        List<CheckRow> rows = new ArrayList<>();
        for (StockBatchTotals totals : batches.check()) {
            Product product = productOrNull(totals.productId());
            rows.add(new CheckRow(totals.productId(), product == null ? "" : nz(product.sku()),
                    product == null ? "Product #" + totals.productId() : nz(product.name()),
                    product == null ? 1 : factorOf(totals.productId()), totals.stockPieces(), totals.batchPieces(),
                    totals.unassignedPieces(), totals.differencePieces()));
        }
        return rows;
    }

    /**
     * "Herstellen": books the listed products' differences (all of {@link #check()} when none are named), in one
     * transaction; refused while paused or before the start. Returns the check list afterwards.
     */
    @Transactional
    public List<CheckRow> repair(Collection<Long> productIds) {
        StockBatchRun run = batches.startRun().orElse(null);
        if (run == null) throw new BusinessRuleException("Batches zijn nog niet gestart.");
        if (run.paused()) throw new BusinessRuleException("Batches staan op pauze.");
        List<Long> ids = productIds == null || productIds.isEmpty()
                ? batches.check().stream().map(StockBatchTotals::productId).toList()
                : productIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long id : ids) batches.repair(id);
        return check();
    }

    /* ============================================================ internals */

    /** Names, suppliers and calculations for one list of batches, each read once. */
    private final class Context {
        private final Map<Long, String> names;
        private final Map<Long, String> supplierNames = new HashMap<>();
        private final Map<Long, LandedCost> calculations = new HashMap<>();
        private final Set<Long> failed = new HashSet<>();
        private Map<Long, Product> catalogue;
        private final Map<Long, PurchaseOrder> orders = new HashMap<>();

        Context(List<StockBatch> list) {
            this.names = containerNames(list.stream().map(StockBatch::purchaseOrderId).filter(Objects::nonNull)
                    .distinct().toList());
        }

        BatchView view(StockBatch batch, boolean next, int currentFactor) {
            boolean gone = batch.purchaseOrderId() != null && !names.containsKey(batch.purchaseOrderId());
            String containerName = batch.purchaseOrderId() == null ? null
                    : gone ? batch.orderNumber() : names.get(batch.purchaseOrderId());
            BigDecimal calc = null;
            boolean stale = false;
            if (batch.kind() == StockBatch.Kind.RECEIPT && batch.piecesLeft() > 0 && batch.purchaseOrderId() != null
                    && !gone) {
                BigDecimal landed = landedUnit(batch);
                if (landed != null && landed.signum() > 0) {
                    calc = landed.multiply(BigDecimal.valueOf(batch.unitPieces()))
                            .divide(BigDecimal.valueOf(Math.max(1, currentFactor)), COST_SCALE, RoundingMode.HALF_UP);
                    stale = batch.unitCostEur() != null && batch.unitCostEur().setScale(2, RoundingMode.HALF_UP)
                            .compareTo(calc.setScale(2, RoundingMode.HALF_UP)) != 0;
                }
            }
            return new BatchView(batch.id(), batch.productId(), batch.kind(), batch.owner(), batch.purchaseOrderId(),
                    batch.orderNumber(), containerName, gone, batch.supplierId(), supplierName(batch.supplierId()),
                    batch.productSupplierId(), batch.receivedOn(), batch.bookedAt(), batch.unitPieces(),
                    batch.piecesIn(), batch.piecesLeft(), batch.soldPieces(), batch.lostPieces(),
                    batch.earlierPieces(), batch.otherPieces(), batch.costState(), batch.unitCostEur(),
                    batch.unitCostExclEnrosedEur(), Parts.of(batch.parts()), batch.costAppliedAt(),
                    batch.costAppliedBy(), batch.valueLeftEur(), next, calc, stale, batch.note());
        }

        private String supplierName(Long supplierId) {
            if (supplierId == null || suppliers == null || !suppliers.isResolvable()) return null;
            return supplierNames.computeIfAbsent(supplierId, id -> {
                Supplier supplier = suppliers.get().find(id);
                return supplier == null ? null : supplier.name();
            });
        }

        /** The container's landed cost per unit for this batch's line, as calculated today; null when unknown. */
        private BigDecimal landedUnit(StockBatch batch) {
            long orderId = batch.purchaseOrderId();
            if (failed.contains(orderId)) return null;
            LandedCost costing = calculations.get(orderId);
            PurchaseOrder order = orders.get(orderId);
            if (costing == null) {
                try {
                    order = purchaseOrders.get().get(orderId);
                    if (catalogue == null) catalogue = purchaseOrders.get().productsById();
                    costing = purchaseOrders.get().calculate(order, catalogue);
                } catch (RuntimeException unavailable) {
                    LOG.debugf(unavailable, "Geen berekening voor container %d", orderId);
                    failed.add(orderId);
                    return null;
                }
                orders.put(orderId, order);
                calculations.put(orderId, costing);
            }
            LandedCost.Line line = StockBatchBackfillService.costingLine(order, costing, batch.purchaseOrderLineId(),
                    batch.productId());
            return line == null ? null : line.landedUnitEur();
        }
    }

    private Map<Long, String> containerNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (Map.Entry<Long, PurchaseOrderName> entry : purchaseOrders.get().names(ids).entrySet()) {
            result.put(entry.getKey(), entry.getValue().displayName());
        }
        return result;
    }

    private Set<Long> existingMovements(List<Long> ids) {
        Set<Long> result = new HashSet<>();
        if (ids.isEmpty()) return result;
        result.addAll(entities.createQuery("select m.id from StockMovementEntity m where m.id in :ids", Long.class)
                .setParameter("ids", ids).getResultList());
        return result;
    }

    private Product productOrNull(long productId) {
        try {
            return products.get(productId);
        } catch (NotFoundException gone) {
            return null;
        }
    }

    private int factorOf(long productId) {
        return stock.unitPieces(productId);
    }

    private static BigDecimal convert(BigDecimal value, int to, int from) {
        if (value == null) return null;
        if (to == from) return value;
        return value.multiply(BigDecimal.valueOf(to)).divide(BigDecimal.valueOf(Math.max(1, from)), COST_SCALE,
                RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money4(BigDecimal value) {
        return value.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static final class LineAcc {
        final long productId;
        int pieces;
        int withoutBatch;
        int withoutCost;
        final Map<String, SaleBatchAcc> batches = new LinkedHashMap<>();

        LineAcc(long productId) {
            this.productId = productId;
        }
    }

    private static final class SaleBatchAcc {
        final StockBatchMove take;
        final StockBatch batch;
        int pieces;

        SaleBatchAcc(StockBatchMove take, StockBatch batch) {
            this.take = take;
            this.batch = batch;
        }
    }
}
