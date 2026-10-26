package be.enrosed.inventory.application;

import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchRun;
import be.enrosed.catalog.domain.StockBatchTotals;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.BatchCosts;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "Batches opbouwen": fills today's stock into batches, once, from what exists (batches contract §5.3).
 *
 * <p>Every booked container line becomes a batch with that container's cost: the newest cost history row of
 * (product, container) as applied, else the container's calculation of today. The shelf is filled from the newest
 * batch down (the year-end {@link FifoValuer} rule): partner pieces first (what the partner container still holds),
 * then own batches newest first; what no container explains becomes one "Beginvoorraad" batch at the card cost, and
 * negative stock one unassigned move. Older pieces that already left get one BACKFILL move per batch.
 *
 * <p>The dry run reads only and takes no locks. The real run locks every product before it reads its levels, writes
 * through {@link StockBatchService#writeBackfill}, checks that each written product holds its invariant (a
 * difference rolls the whole start back) and then records the start; from then on every stock hook writes.
 */
@ApplicationScoped
public class StockBatchBackfillService {

    private static final Logger LOG = Logger.getLogger(StockBatchBackfillService.class);
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int COST_SCALE = 4;
    /** The start locks every product it writes; it may take long on a big catalogue, never forever. */
    private static final int START_TIMEOUT_SECONDS = 600;

    static final String OPENING_NOTE = "Beginvoorraad, kost van de productkaart";
    static final String NEGATIVE_REFERENCE = "Negatieve voorraad bij de start";
    /** Only these rows carry the product's own cost from that container; COPY rows carry another product's. */
    private static final List<String> HISTORY_KINDS = List.of("APPLY", "REAPPLY", "SEED");

    private final StockBatchService batches;
    private final StockService stock;
    private final EntityManager entities;

    @Inject Instance<PurchaseOrderService> purchaseOrders;
    @Inject Instance<SalesOrderService> salesOrders;

    public StockBatchBackfillService(StockBatchService batches, StockService stock, EntityManager entities) {
        this.batches = batches;
        this.stock = stock;
        this.entities = entities;
    }

    /* ---------------------------------------------------------------- report */

    /** In this order on the report: rows with a flag first, by their first flag. */
    public enum Flag { OPENING, NEGATIVE, NO_COST, PARTNER }

    public record ReportBatch(Long purchaseOrderId, String orderNumber, StockBatch.Kind kind, StockBatch.Owner owner,
                              int piecesIn, int piecesLeft, BigDecimal unitCostEur, StockBatch.CostState costState) {}

    /** One product; {@code valueEur} counts own batches only. */
    public record ReportRow(long productId, String sku, String name, int unitPieces, int stockPieces,
                            int openingPieces, int unassignedPieces, BigDecimal valueEur, List<ReportBatch> batches,
                            List<Flag> flags, List<String> notes) {}

    public record NotBooked(long purchaseOrderId, String orderNumber) {}

    public record Report(boolean dryRun, Instant startedAt, int products, int batches, int openingPieces,
                         int unassignedPieces, BigDecimal valueEur, List<ReportRow> rows, List<NotBooked> notBooked) {}

    /* ------------------------------------------------------------ operations */

    /** What the start would write; reads only, no locks. */
    @Transactional
    public Report dryRun() {
        return build(false);
    }

    /**
     * The start. Refused when batches are already started; locks every product it writes. On its own it runs in one
     * transaction of up to 600 s; inside a caller's transaction it joins that one.
     */
    public Report start() {
        if (QuarkusTransaction.isActive()) return startHere();
        return QuarkusTransaction.requiringNew().timeout(START_TIMEOUT_SECONDS).call(this::startHere);
    }

    private Report startHere() {
        refuseWhenStarted();
        return build(true);
    }

    /**
     * Removes every batch, batch move and start, and the stock cost stamped on sales lines (on Emre's word only; no
     * screen calls it). Afterwards the dry run and the start can run again.
     */
    @Transactional
    public void reset() {
        batches.reset();
        entities.createNativeQuery("update sales_order_line set stock_cost_eur = null where stock_cost_eur is not null")
                .executeUpdate();
    }

    /* ============================================================ the build */

    /** A booked container line of one product, with the cost its batch gets. */
    private record Candidate(PurchaseOrder order, PurchaseOrderLine line, int occurrence) {}

    /** One batch as the build plans it. */
    private static final class Planned {
        final StockBatch.Kind kind;
        final StockBatch.Owner owner;
        final PurchaseOrder order;
        final PurchaseOrderLine line;
        final LocalDate receivedOn;
        final Instant bookedAt;
        final int piecesIn;
        int piecesLeft;
        final BatchCost cost;
        final String note;

        Planned(StockBatch.Kind kind, StockBatch.Owner owner, PurchaseOrder order, PurchaseOrderLine line,
                LocalDate receivedOn, Instant bookedAt, int piecesIn, BatchCost cost, String note) {
            this.kind = kind;
            this.owner = owner;
            this.order = order;
            this.line = line;
            this.receivedOn = receivedOn;
            this.bookedAt = bookedAt;
            this.piecesIn = piecesIn;
            this.cost = cost;
            this.note = note;
        }

        Long orderId() { return order == null ? null : order.id(); }

        Long lineId() { return line == null ? null : line.id(); }

        boolean partner() { return owner == StockBatch.Owner.PARTNER; }

        BigDecimal valueLeft(int unitPieces) {
            if (cost.unitCostEur() == null) return null;
            return cost.unitCostEur().multiply(BigDecimal.valueOf(piecesLeft))
                    .divide(BigDecimal.valueOf(Math.max(1, unitPieces)), 2, RoundingMode.HALF_UP);
        }
    }

    /** A product_cost_history row as far as the backfill needs it. */
    private record History(long id, BigDecimal landedUnitEur, String salesUnit, Integer piecesPerUnit,
                           BigDecimal goodsEur, BigDecimal originEur, BigDecimal freightEur, BigDecimal dutyEur,
                           BigDecimal destinationEur, BigDecimal enrosedEur, BigDecimal separateEur,
                           Instant appliedAt, String appliedBy) {
        boolean hasParts() {
            return Stream.of(goodsEur, originEur, freightEur, dutyEur, destinationEur, enrosedEur, separateEur)
                    .anyMatch(Objects::nonNull);
        }
    }

    private record Level(long locationId, int quantity, int loose) {}

    private Report build(boolean write) {
        Instant runAt = Instant.now();
        PurchaseOrderService orders = purchaseOrders.get();

        List<PurchaseOrder> all = orders.list().stream().sorted(Comparator.comparing(PurchaseOrder::id)).toList();
        List<PurchaseOrder> booked = all.stream()
                .filter(order -> order.status() == PurchaseOrderStatus.ONTVANGEN && order.isStockBooked()).toList();
        List<NotBooked> notBooked = all.stream()
                .filter(order -> order.status() == PurchaseOrderStatus.ONTVANGEN && !order.isStockBooked())
                .map(order -> new NotBooked(order.id(), order.number())).toList();

        Map<Long, Product> catalogue = orders.productsById();
        Map<Long, LandedCost> calculations = new HashMap<>();
        Map<Long, List<Candidate>> linesByProduct = new HashMap<>();
        boolean anyPartner = false;
        for (PurchaseOrder order : booked) {
            Map<Long, Integer> occurrences = new HashMap<>();
            boolean has = false;
            for (PurchaseOrderLine line : order.lines()) {
                if (line.productId() == null) continue;
                int occurrence = occurrences.merge(line.productId(), 1, Integer::sum) - 1;
                if (line.usable() <= 0 || !catalogue.containsKey(line.productId())) continue;
                linesByProduct.computeIfAbsent(line.productId(), id -> new ArrayList<>())
                        .add(new Candidate(order, line, occurrence));
                has = true;
            }
            if (!has) continue;
            anyPartner |= order.isPartnerContainer();
            try {
                calculations.put(order.id(), orders.calculate(order, catalogue));
            } catch (RuntimeException unavailable) {
                LOG.warnf(unavailable, "Batches opbouwen: geen berekening voor %s", order.number());
            }
        }
        Map<String, History> history = history();
        Map<String, Instant> firstReceipts = firstReceipts();
        Map<Long, String> locationNames = stock.locations().stream()
                .collect(Collectors.toMap(StockLocation::id, StockLocation::name, (a, b) -> a));
        List<SalesOrder> sales = anyPartner ? salesOrders.get().list() : List.of();
        Map<Long, Map<Long, Integer>> partnerShipped = new HashMap<>();
        Map<Long, List<Level>> allLevels = write ? Map.of() : levels(null);

        TreeSet<Long> productIds = new TreeSet<>(linesByProduct.keySet());
        if (write) productIds.addAll(levelProducts());
        else productIds.addAll(allLevels.keySet());
        productIds.removeIf(id -> !catalogue.containsKey(id));

        List<ReportRow> rows = new ArrayList<>();
        int batchCount = 0;
        int openingTotal = 0;
        int unassignedTotal = 0;
        BigDecimal valueTotal = BigDecimal.ZERO;
        boolean first = true;
        for (Long productId : productIds) {
            Product product = catalogue.get(productId);
            List<Level> held;
            if (write) {
                stock.lockProduct(productId);
                if (first) {
                    /* A start that ran meanwhile held these locks until it committed. */
                    refuseWhenStarted();
                    first = false;
                }
                held = levels(productId).getOrDefault(productId, List.of());
            } else {
                held = allLevels.getOrDefault(productId, List.of());
            }
            int factor = stock.unitPieces(productId);

            /* 1. the shelf: positive pieces fill batches, negative ones become one unassigned move */
            int pool = 0;
            int negative = 0;
            int net = 0;
            List<String> notes = new ArrayList<>();
            for (Level level : held) {
                int pieces = Math.addExact(Math.multiplyExact(level.quantity(), factor), level.loose());
                net = Math.addExact(net, pieces);
                if (pieces >= 0) {
                    pool = Math.addExact(pool, pieces);
                } else {
                    negative = Math.addExact(negative, -pieces);
                    notes.add("Negatief op " + locationNames.getOrDefault(level.locationId(),
                            "locatie #" + level.locationId()) + ": −" + (-pieces));
                }
            }

            /* 2-3. a batch per booked line, with its cost */
            List<Planned> planned = new ArrayList<>();
            for (Candidate candidate : linesByProduct.getOrDefault(productId, List.of())) {
                PurchaseOrder order = candidate.order();
                PurchaseOrderLine line = candidate.line();
                LandedCost costing = calculations.get(order.id());
                LandedCost.Line costingLine = costing == null ? null
                        : costingLine(costing, productId, candidate.occurrence());
                BatchCost cost = cost(history.get(productId + ":" + order.id()), costingLine, factor);
                LocalDate receivedOn = order.receivedOn();
                Instant bookedAt = firstReceipts.get(productId + "|" + order.number());
                if (bookedAt == null && receivedOn != null) bookedAt = receivedOn.atStartOfDay(BRUSSELS).toInstant();
                planned.add(new Planned(StockBatch.Kind.RECEIPT,
                        order.isPartnerContainer() ? StockBatch.Owner.PARTNER : StockBatch.Owner.OWN, order, line,
                        receivedOn, bookedAt, Math.multiplyExact(line.usable(), factor), cost, null));
            }
            Comparator<Planned> newestFirst = Comparator
                    .comparing((Planned batch) -> batch.receivedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Planned::orderId, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Planned::lineId, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed();

            /* 4. partner pieces first: what the partner container still holds by the run */
            Map<Long, Integer> shippedLeft = new HashMap<>();
            for (Planned batch : planned.stream().filter(Planned::partner).sorted(newestFirst).toList()) {
                long orderId = batch.order.id();
                Map<Long, Integer> shipped = partnerShipped.computeIfAbsent(orderId,
                        id -> ClosingSeparations.partnerShipped(id, sales, runAt));
                int out = shippedLeft.computeIfAbsent(orderId, id -> shipped.getOrDefault(productId, 0));
                int usable = batch.line.usable();
                int taken = Math.min(out, usable);
                shippedLeft.put(orderId, out - taken);
                int remaining = Math.multiplyExact(usable - taken, factor);
                batch.piecesLeft = Math.min(remaining, pool);
                pool -= batch.piecesLeft;
            }
            /* 5. own batches newest first */
            for (Planned batch : planned.stream().filter(batch -> !batch.partner()).sorted(newestFirst).toList()) {
                batch.piecesLeft = Math.min(batch.piecesIn, pool);
                pool -= batch.piecesLeft;
            }
            /* 6. what no container explains */
            int opening = 0;
            if (pool > 0) {
                opening = pool;
                Planned batch = new Planned(StockBatch.Kind.OPENING, StockBatch.Owner.OWN, null, null, null, null,
                        pool, cardCost(product), OPENING_NOTE);
                batch.piecesLeft = pool;
                planned.add(batch);
                pool = 0;
            }
            if (planned.isEmpty() && negative == 0) continue;

            /* oldest first: Beginvoorraad, then by receipt (unknown first), container, line */
            List<Planned> ordered = new ArrayList<>(planned);
            ordered.sort(Comparator.comparingInt((Planned batch) -> batch.kind == StockBatch.Kind.OPENING ? 0 : 1)
                    .thenComparing(batch -> batch.receivedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Planned::orderId, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Planned::lineId, Comparator.nullsFirst(Comparator.naturalOrder())));

            /* 8. the write (start only), checked at once */
            if (write) {
                batches.writeBackfill(productId, ordered.stream().map(batch -> new StockBatchService.BackfillBatch(
                        batch.kind, batch.owner, batch.orderId(), batch.lineId(),
                        batch.order == null ? null : batch.order.number(),
                        batch.order == null ? null : batch.order.supplierId(),
                        batch.line == null ? null : batch.line.productSupplierId(),
                        batch.receivedOn, batch.bookedAt, factor, batch.piecesIn, batch.piecesLeft, batch.cost,
                        batch.note)).toList(), negative, NEGATIVE_REFERENCE, runAt);
                StockBatchTotals totals = batches.totals(productId);
                if (totals.differencePieces() != 0) {
                    throw new BusinessRuleException("Batches opbouwen gestopt: " + label(product) + " wijkt "
                            + totals.differencePieces() + " stuks af van de voorraad. Er is niets bewaard.");
                }
            }

            /* the report row */
            EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
            if (opening > 0) flags.add(Flag.OPENING);
            if (negative > 0) flags.add(Flag.NEGATIVE);
            BigDecimal value = BigDecimal.ZERO;
            List<ReportBatch> reportBatches = new ArrayList<>();
            for (Planned batch : ordered) {
                if (batch.piecesLeft > 0 && batch.cost.unitCostEur() == null) flags.add(Flag.NO_COST);
                if (batch.piecesLeft > 0 && batch.partner()) flags.add(Flag.PARTNER);
                if (!batch.partner()) {
                    BigDecimal left = batch.valueLeft(factor);
                    if (left != null) value = value.add(left);
                }
                reportBatches.add(new ReportBatch(batch.orderId(), batch.order == null ? null : batch.order.number(),
                        batch.kind, batch.owner, batch.piecesIn, batch.piecesLeft, batch.cost.unitCostEur(),
                        stateOf(batch.cost)));
            }
            value = value.setScale(2, RoundingMode.HALF_UP);
            rows.add(new ReportRow(productId, nz(product.sku()), nz(product.name()), factor, net, opening, negative,
                    value, reportBatches, List.copyOf(flags), notes));
            batchCount += ordered.size();
            openingTotal += opening;
            unassignedTotal += negative;
            valueTotal = valueTotal.add(value);
        }

        rows.sort(Comparator.comparingInt((ReportRow row) -> row.flags().isEmpty() ? Flag.values().length
                        : row.flags().getFirst().ordinal())
                .thenComparing(ReportRow::sku, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparingLong(ReportRow::productId));

        Instant startedAt = null;
        if (write) {
            String note = "Batches opgebouwd: " + rows.size() + " producten, " + batchCount + " batches"
                    + (notBooked.isEmpty() ? "" : "; niet bijgeboekt: "
                    + notBooked.stream().map(NotBooked::orderNumber).filter(Objects::nonNull)
                    .collect(Collectors.joining(", ")));
            StockBatchRun run = batches.recordStart(rows.size(), batchCount, openingTotal, unassignedTotal, note);
            startedAt = run.ranAt();
            LOG.infof("Batches opgebouwd: %d producten, %d batches, %d stuks beginvoorraad, %d zonder batch",
                    rows.size(), batchCount, openingTotal, unassignedTotal);
        }
        return new Report(!write, startedAt, rows.size(), batchCount, openingTotal, unassignedTotal,
                valueTotal.setScale(2, RoundingMode.HALF_UP), rows, notBooked);
    }

    /* ------------------------------------------------------------------ cost */

    /**
     * The batch cost of one booked line. The newest history row of (product, container) is what was applied: its
     * figure on its own basis (per display only when it was applied per display) converted to the product's unit of
     * today. Without a row, the container's calculation of today. Without a price, no cost.
     */
    private static BatchCost cost(History row, LandedCost.Line costingLine, int factor) {
        BatchCost calculated = BatchCosts.of(costingLine, StockBatch.CostState.CALCULATED, null, null);
        if (row == null || row.landedUnitEur() == null || row.landedUnitEur().signum() <= 0) return calculated;
        int rowPieces = "DISPLAY".equals(row.salesUnit()) && row.piecesPerUnit() != null && row.piecesPerUnit() > 0
                ? row.piecesPerUnit() : 1;
        BigDecimal unit = convert(row.landedUnitEur(), factor, rowPieces);
        ProductCostBreakdown parts = null;
        BigDecimal excl = null;
        if (row.hasParts()) {
            parts = new ProductCostBreakdown(convert(row.goodsEur(), factor, rowPieces),
                    convert(row.originEur(), factor, rowPieces), convert(row.freightEur(), factor, rowPieces),
                    convert(row.dutyEur(), factor, rowPieces), convert(row.destinationEur(), factor, rowPieces),
                    convert(row.enrosedEur(), factor, rowPieces), convert(row.separateEur(), factor, rowPieces));
            excl = parts.enrosedEur() == null ? unit : unit.subtract(parts.enrosedEur());
        } else if (calculated.unitCostEur() != null
                && calculated.unitCostEur().setScale(COST_SCALE, RoundingMode.HALF_UP).compareTo(unit) == 0) {
            excl = calculated.unitCostExclEnrosedEur();
        }
        return new BatchCost(StockBatch.CostState.APPLIED, unit, excl, parts, row.appliedAt(), row.appliedBy());
    }

    private static BatchCost cardCost(Product product) {
        BigDecimal card = product.landedCostEur();
        if (card == null || card.signum() <= 0) return BatchCost.NONE;
        return new BatchCost(StockBatch.CostState.CARD, card.setScale(COST_SCALE, RoundingMode.HALF_UP), null, null,
                null, null);
    }

    private static StockBatch.CostState stateOf(BatchCost cost) {
        return cost.unitCostEur() == null || cost.state() == null ? StockBatch.CostState.NONE : cost.state();
    }

    /** value × factor / basis, 4 decimals HALF_UP. */
    private static BigDecimal convert(BigDecimal value, int factor, int basis) {
        if (value == null) return null;
        if (factor == basis) return value.setScale(COST_SCALE, RoundingMode.HALF_UP);
        return value.multiply(BigDecimal.valueOf(factor)).divide(BigDecimal.valueOf(basis), COST_SCALE,
                RoundingMode.HALF_UP);
    }

    /**
     * The costing line of an order line. The calculation sorts its lines by product but keeps the lines of one
     * product in order, so the n-th line of a product on the order is the n-th costing line of that product.
     */
    static LandedCost.Line costingLine(LandedCost costing, long productId, int occurrence) {
        List<LandedCost.Line> lines = costing.lines().stream()
                .filter(line -> line.productId() != null && line.productId() == productId).toList();
        if (lines.isEmpty()) return null;
        return lines.get(Math.max(0, Math.min(occurrence, lines.size() - 1)));
    }

    /** The same for a batch: its line found by id on the order, else the product's first costing line. */
    static LandedCost.Line costingLine(PurchaseOrder order, LandedCost costing, Long purchaseOrderLineId,
                                       long productId) {
        int occurrence = 0;
        if (order != null && purchaseOrderLineId != null) {
            int seen = 0;
            for (PurchaseOrderLine line : order.lines()) {
                if (line.productId() == null || line.productId() != productId) continue;
                if (purchaseOrderLineId.equals(line.id())) {
                    occurrence = seen;
                    break;
                }
                seen++;
            }
        }
        return costingLine(costing, productId, occurrence);
    }

    /* ----------------------------------------------------------------- reads */

    /** Newest history row per (product, container), kinds APPLY/REAPPLY/SEED only. */
    private Map<String, History> history() {
        Map<String, History> result = new HashMap<>();
        Map<String, Instant> appliedAt = new HashMap<>();
        for (Object[] row : entities.createQuery("select h.productId, h.purchaseOrderId, h.id, h.landedUnitEur, "
                        + "h.salesUnit, h.piecesPerUnit, h.goodsEur, h.originEur, h.freightEur, h.dutyEur, "
                        + "h.destinationEur, h.enrosedEur, h.separateEur, h.appliedAt, h.appliedBy "
                        + "from ProductCostHistoryEntity h where h.purchaseOrderId is not null and h.kind in :kinds",
                Object[].class).setParameter("kinds", HISTORY_KINDS).getResultList()) {
            String key = row[0] + ":" + row[1];
            History candidate = new History(((Number) row[2]).longValue(), (BigDecimal) row[3], (String) row[4],
                    (Integer) row[5], (BigDecimal) row[6], (BigDecimal) row[7], (BigDecimal) row[8],
                    (BigDecimal) row[9], (BigDecimal) row[10], (BigDecimal) row[11], (BigDecimal) row[12],
                    (Instant) row[13], (String) row[14]);
            History current = result.get(key);
            Instant at = candidate.appliedAt() == null ? Instant.MIN : candidate.appliedAt();
            if (current == null || at.isAfter(appliedAt.get(key))
                    || (at.equals(appliedAt.get(key)) && candidate.id() > current.id())) {
                result.put(key, candidate);
                appliedAt.put(key, at);
            }
        }
        return result;
    }

    /** The first receipt booking per (product, reference): when a container's pieces reached the shelf. */
    private Map<String, Instant> firstReceipts() {
        Map<String, Instant> result = new HashMap<>();
        for (Object[] row : entities.createQuery("select m.productId, m.reference, min(m.at) from StockMovementEntity m "
                        + "where m.kind = :kind and m.reference is not null group by m.productId, m.reference",
                Object[].class).setParameter("kind", StockMovement.Kind.PURCHASE_RECEIPT.name()).getResultList()) {
            result.put(row[0] + "|" + row[1], (Instant) row[2]);
        }
        return result;
    }

    /** Every stock level row (all locations, negatives included), or one product's. */
    private Map<Long, List<Level>> levels(Long productId) {
        Map<Long, List<Level>> result = new LinkedHashMap<>();
        var query = entities.createQuery("select l.productId, l.locationId, l.quantity, l.loosePieces "
                + "from StockLevelEntity l" + (productId == null ? "" : " where l.productId = :productId")
                + " order by l.productId, l.locationId", Object[].class);
        if (productId != null) query.setParameter("productId", productId);
        for (Object[] row : query.getResultList()) {
            result.computeIfAbsent(((Number) row[0]).longValue(), id -> new ArrayList<>())
                    .add(new Level(((Number) row[1]).longValue(), ((Number) row[2]).intValue(),
                            row[3] == null ? 0 : ((Number) row[3]).intValue()));
        }
        return result;
    }

    private Set<Long> levelProducts() {
        return new TreeSet<>(entities.createQuery("select distinct l.productId from StockLevelEntity l", Long.class)
                .getResultList());
    }

    private void refuseWhenStarted() {
        StockBatchRun run = batches.startRun().orElse(null);
        if (run != null) {
            String day = run.ranAt() == null ? "" : " op " + run.ranAt().atZone(BRUSSELS).toLocalDate().format(DAY);
            throw new BusinessRuleException("Batches zijn al gestart" + day + ".");
        }
    }

    private static String label(Product product) {
        return product.sku() != null && !product.sku().isBlank() ? product.sku() : "product #" + product.id();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
