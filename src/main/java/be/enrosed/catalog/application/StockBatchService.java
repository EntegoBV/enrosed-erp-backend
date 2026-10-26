package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchMoveEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchRunEntity;
import be.enrosed.catalog.adapter.out.persistence.StockDaos;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.BatchSource;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.catalog.domain.StockBatchRun;
import be.enrosed.catalog.domain.StockBatchSaleCost;
import be.enrosed.catalog.domain.StockBatchTotals;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stock batches: which container's pieces are still on the shelf and at what cost.
 *
 * <p>Quantities are physical pieces; a batch's cost is per selling unit of the batch ({@code unit_pieces}, frozen when
 * it was born). Every change of a batch is a signed move written in the same transaction as the stock movement that
 * caused it, so the invariant {@code Σ pieces_left − open unassigned pieces = Σ pieces on every location} holds per
 * product. Batches are company-wide FIFO: locations and transfers do not matter here.
 *
 * <p>Nothing happens before the start ("Batches opbouwen", a {@code stock_batch_run} row): every hook returns at once,
 * and again while the latest run is paused. All writers run inside the caller's transaction under the product's
 * {@code PESSIMISTIC_WRITE} lock, which every {@link StockService} writer already holds; the methods called from
 * elsewhere take it themselves. This service never depends on {@link StockService} (that one depends on this one).
 */
@ApplicationScoped
public class StockBatchService {

    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    static final String OPENING = "OPENING";
    static final String COUNT_IN = "COUNT_IN";
    static final String BACKFILL = "BACKFILL";
    static final String RECEIPT_CORRECTION = "RECEIPT_CORRECTION";
    static final String REPAIR = "REPAIR";
    private static final String SALE = StockMovement.Kind.SALE.name();
    private static final String SALE_RETURN = StockMovement.Kind.SALE_RETURN.name();
    private static final String PURCHASE_RECEIPT = StockMovement.Kind.PURCHASE_RECEIPT.name();

    /** Takes that count as "Verlies" on the batch they left. */
    private static final Set<String> LOST_KINDS = Set.of("DAMAGED", "SHORTAGE", "DEMO", "STOCKTAKE",
            "MANUAL_CORRECTION", PURCHASE_RECEIPT);

    static final String NOT_STARTED = "Batches zijn nog niet gestart.";
    static final String PAUSED = "Batches staan op pauze.";

    private static final int SCALE = 4;
    /** saleCost values stay unrounded for the caller; this only keeps the divisions exact enough. */
    private static final int VALUE_SCALE = 10;

    /* The column lengths of stock-batches-postgresql.sql: a longer text must never roll back a stock booking. */
    private static final int REFERENCE_LENGTH = 160;
    private static final int ACTOR_LENGTH = 80;
    private static final int NOTE_LENGTH = 300;
    private static final int ORDER_NUMBER_LENGTH = 120;
    private static final int RUN_NOTE_LENGTH = 500;

    /** Own before partner; within own, Beginvoorraad first; then oldest received (unknown first), then id. */
    private static final Comparator<StockBatchEntity> FIFO = Comparator
            .comparingInt((StockBatchEntity b) -> isPartner(b) ? 1 : 0)
            .thenComparingInt(b -> !isPartner(b) && OPENING.equals(b.kind) ? 0 : 1)
            .thenComparing(b -> b.receivedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(b -> b.id, Comparator.nullsLast(Comparator.naturalOrder()));

    /** "Most recently consumed": batch received_on desc (unknown last), batch id desc. */
    private static final Comparator<StockBatchEntity> MOST_RECENT = Comparator
            .comparing((StockBatchEntity b) -> b.receivedOn, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(b -> b.id, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
            .reversed();

    private final StockDaos.Batches batches;
    private final StockDaos.BatchMoves moves;
    private final StockDaos.BatchRuns runs;
    private final StockDaos.Levels levels;
    private final CatalogDaos.Products products;
    private final CurrentActor actor;

    public StockBatchService(StockDaos.Batches batches, StockDaos.BatchMoves moves, StockDaos.BatchRuns runs,
                             StockDaos.Levels levels, CatalogDaos.Products products, CurrentActor actor) {
        this.batches = batches;
        this.moves = moves;
        this.runs = runs;
        this.levels = levels;
        this.products = products;
        this.actor = actor;
    }

    /* ------------------------------------------------------------ the start */

    /** A start is recorded (the backfill run), paused or not. Screens and the packaging guard use this. */
    public boolean started() {
        return runs.count() > 0;
    }

    /** Started and the latest run is not paused: only then do hooks write. One indexed query per call, no cache. */
    public boolean active() {
        StockBatchRunEntity latest = latestRun();
        return latest != null && latest.pausedAt == null;
    }

    /** The latest run row. */
    public Optional<StockBatchRun> startRun() {
        return Optional.ofNullable(latestRun()).map(StockBatchService::toDomain);
    }

    /** Stops every hook at once, without stopping any stock work; idempotent. */
    @Transactional
    public StockBatchRun pause() {
        StockBatchRunEntity latest = latestRun();
        if (latest == null) throw new BusinessRuleException(NOT_STARTED);
        if (latest.pausedAt == null) {
            latest.pausedAt = Instant.now();
            latest.pausedBy = cut(actor.name(), ACTOR_LENGTH);
        }
        return toDomain(latest);
    }

    /** Hooks write again; the drift that built up shows in {@link #check()} until it is repaired. Idempotent. */
    @Transactional
    public StockBatchRun resume() {
        StockBatchRunEntity latest = latestRun();
        if (latest == null) throw new BusinessRuleException(NOT_STARTED);
        latest.pausedAt = null;
        latest.pausedBy = null;
        return toDomain(latest);
    }

    /** Deletes every stock_batch_move, stock_batch and stock_batch_run row (bulk, in that order). */
    @Transactional
    public void reset() {
        moves.flush();
        moves.deleteAll();
        batches.deleteAll();
        runs.deleteAll();
    }

    /* ---------------------------------------------------------------- hooks */

    /** A booked add (receipt, credit return, legacy correction); {@code physicalDelta} may be negative. */
    void onAdd(long productId, Long movementId, StockMovement.Kind kind, int physicalDelta, StockBatchContext ctx,
               String reference, Instant at) {
        if (physicalDelta == 0 || kind == null || !active()) return;
        StockBatchContext context = ctx == null ? StockBatchContext.NONE : ctx;
        if (kind == StockMovement.Kind.PURCHASE_RECEIPT && context.receipt() != null) {
            receipt(productId, movementId, physicalDelta, context.receipt(), reference, at);
            return;
        }
        Ids ids = new Ids(movementId, context.salesOrderId(), null, null, reference, at, actorName());
        if (kind == StockMovement.Kind.SALE_RETURN && physicalDelta > 0) {
            restore(productId, physicalDelta, SALE_RETURN, context.returnOfSalesOrderId(), null, ids);
        } else if (physicalDelta > 0) {
            found(productId, physicalDelta, "Ontvangst zonder container", kind.name(), ids);
        } else {
            take(productId, -physicalDelta, kind.name(), null, ids);
        }
    }

    /** Pieces leaving the shelf (sale, damage, shortage, demo): pinned to {@code ctx.pinPurchaseOrderId} first. */
    void onTake(long productId, Long movementId, StockMovement.Kind kind, int pieces, StockBatchContext ctx,
                String reference, Instant at) {
        if (pieces <= 0 || kind == null || !active()) return;
        StockBatchContext context = ctx == null ? StockBatchContext.NONE : ctx;
        Long pin = context.pinPurchaseOrderId();
        take(productId, pieces, kind.name(), pin,
                new Ids(movementId, context.salesOrderId(), null, pin, reference, at, actorName()));
    }

    /** A count set at a location: fewer pieces leave FIFO, more pieces are found. */
    void onCount(long productId, Long movementId, StockMovement.Kind kind, int physicalDelta, String reference,
                 Instant at) {
        if (physicalDelta == 0 || kind == null || !active()) return;
        Ids ids = new Ids(movementId, null, null, null, reference, at, actorName());
        if (physicalDelta < 0) take(productId, -physicalDelta, kind.name(), null, ids);
        else found(productId, physicalDelta, "Telling", kind.name(), ids);
    }

    /** A TICA booking: a sale takes FIFO, a correction or return restores (that sale's takes first). */
    void onTica(long productId, Long movementId, int physicalDelta, long ticaSaleId, String reference, Instant at) {
        if (physicalDelta == 0 || !active()) return;
        Ids ids = new Ids(movementId, null, ticaSaleId, null, reference, at, actorName());
        if (physicalDelta < 0) take(productId, -physicalDelta, SALE, null, ids);
        else restore(productId, physicalDelta, SALE_RETURN, null, ticaSaleId, ids);
    }

    /**
     * Pieces that turned up without a known origin (a level created at start-up): they resolve open unassigned takes
     * first, the rest goes into today's Telling batch at the card cost. No-op when not active. Locks the product.
     */
    void found(long productId, int pieces, String note, Instant at) {
        if (pieces <= 0 || !active()) return;
        lock(productId);
        found(productId, pieces, note, COUNT_IN, new Ids(null, null, null, null, null, at, actorName()));
    }

    /* ----------------------------------------------------------------- cost */

    /** What a cost write touched: the batches and the pieces still on them. */
    public record AppliedBatchCost(int batches, int piecesLeft) {
        public static final AppliedBatchCost NONE = new AppliedBatchCost(0, 0);
    }

    /**
     * Writes the cost on every batch of (container, product), converted to each batch's own unit; costUnitPieces is
     * the product's factor the cost is per. Sets the state and who applied it. A cost without a unit cost changes
     * nothing (a CALCULATED cost is never wiped by a line without a price). Moves are never rewritten.
     */
    @Transactional
    public AppliedBatchCost applyCost(long purchaseOrderId, long productId, BatchCost cost, int costUnitPieces) {
        return writeCost(purchaseOrderId, productId, cost, costUnitPieces, false);
    }

    /** Live CALCULATED batches of the product (pieces left, a container set), FIFO order. */
    public List<StockBatch> calculatedLive(long productId) {
        List<StockBatchEntity> rows = new ArrayList<>(batches.list(
                "productId = ?1 and piecesLeft > 0 and purchaseOrderId is not null and costState = ?2",
                productId, StockBatch.CostState.CALCULATED.name()));
        rows.sort(FIFO);
        return toDomain(rows);
    }

    /**
     * Like {@link #applyCost}, but only for CALCULATED batches of (container, product); the state stays CALCULATED
     * and the applied fields stay empty. A cost with state NONE or without a unit cost changes nothing.
     */
    @Transactional
    public AppliedBatchCost refreshCalculated(long purchaseOrderId, long productId, BatchCost cost,
                                              int costUnitPieces) {
        if (cost == null || cost.state() == StockBatch.CostState.NONE) return AppliedBatchCost.NONE;
        return writeCost(purchaseOrderId, productId, cost, costUnitPieces, true);
    }

    private AppliedBatchCost writeCost(long purchaseOrderId, long productId, BatchCost cost, int costUnitPieces,
                                       boolean calculatedOnly) {
        if (cost == null || cost.unitCostEur() == null || !started()) return AppliedBatchCost.NONE;
        lock(productId);
        int count = 0;
        int left = 0;
        int per = Math.max(1, costUnitPieces);
        for (StockBatchEntity batch : batches.list("purchaseOrderId = ?1 and productId = ?2 order by id",
                purchaseOrderId, productId)) {
            if (calculatedOnly && !StockBatch.CostState.CALCULATED.name().equals(batch.costState)) continue;
            int own = unitPieces(batch);
            ProductCostBreakdown parts = cost.parts();
            batch.unitCostEur = convert(cost.unitCostEur(), own, per);
            batch.unitCostExclEnrosedEur = convert(cost.unitCostExclEnrosedEur(), own, per);
            batch.goodsEur = parts == null ? null : convert(parts.goodsEur(), own, per);
            batch.originEur = parts == null ? null : convert(parts.originEur(), own, per);
            batch.freightEur = parts == null ? null : convert(parts.freightEur(), own, per);
            batch.dutyEur = parts == null ? null : convert(parts.dutyEur(), own, per);
            batch.destinationEur = parts == null ? null : convert(parts.destinationEur(), own, per);
            batch.enrosedEur = parts == null ? null : convert(parts.enrosedEur(), own, per);
            batch.separateEur = parts == null ? null : convert(parts.separateEur(), own, per);
            if (!calculatedOnly) {
                batch.costState = (cost.state() == null ? StockBatch.CostState.NONE : cost.state()).name();
                batch.costAppliedAt = cost.appliedAt();
                batch.costAppliedBy = cut(cost.appliedBy(), ACTOR_LENGTH);
            }
            count++;
            left += pieces(batch.piecesLeft);
        }
        batches.flush();
        return new AppliedBatchCost(count, left);
    }

    /* --------------------------------------------------------------- repair */

    /**
     * Books the product's difference ("Herstellen"): too few batch pieces → found; too many → FIFO take of kind
     * REPAIR. Refused while paused or before the start. Returns the new totals.
     */
    @Transactional
    public StockBatchTotals repair(long productId) {
        requireActive();
        lock(productId);
        int difference = totals(productId).differencePieces();
        Ids ids = new Ids(null, null, null, null, "Herstel verschil", Instant.now(), actorName());
        if (difference < 0) found(productId, -difference, "Herstel verschil", REPAIR, ids);
        else if (difference > 0) take(productId, difference, REPAIR, null, ids);
        return totals(productId);
    }

    /* ---------------------------------------------------------------- reads */

    /** The product's batches: live ones in the order they are taken, then the empty ones newest first. */
    public List<StockBatch> forProduct(long productId) {
        List<StockBatchEntity> rows = batches.list("productId", productId);
        List<StockBatchEntity> live = new ArrayList<>();
        List<StockBatchEntity> empty = new ArrayList<>();
        for (StockBatchEntity row : rows) (pieces(row.piecesLeft) > 0 ? live : empty).add(row);
        live.sort(FIFO);
        empty.sort(MOST_RECENT);
        List<StockBatchEntity> ordered = new ArrayList<>(live);
        ordered.addAll(empty);
        return toDomain(ordered);
    }

    /** A container's batches, by product id, then batch id. */
    public List<StockBatch> forPurchaseOrder(long purchaseOrderId) {
        return toDomain(batches.list("purchaseOrderId = ?1 order by productId, id", purchaseOrderId));
    }

    public Optional<StockBatch> batch(long batchId) {
        StockBatchEntity row = batches.findById(batchId);
        return row == null ? Optional.empty() : Optional.of(toDomain(List.of(row)).getFirst());
    }

    /** A batch's moves, newest first. */
    public List<StockBatchMove> moves(long batchId, int limit) {
        return moves.find("batchId = ?1 order by id desc", batchId).page(0, Math.max(1, limit)).list().stream()
                .map(StockBatchService::toDomain).toList();
    }

    public int moveCount(long batchId) {
        return Math.toIntExact(moves.count("batchId", batchId));
    }

    /** The takes a shipment made (from a batch or unassigned; later settles are not among them), oldest first. */
    public List<StockBatchMove> takesForSalesOrder(long salesOrderId) {
        return moves.list("salesOrderId = ?1 and pieces < 0 and linkMoveId is null order by id", salesOrderId)
                .stream().map(StockBatchService::toDomain).toList();
    }

    /** What one shipment took, per product (from {@link #takesForSalesOrder}); values unrounded. */
    public Map<Long, StockBatchSaleCost> saleCost(long salesOrderId) {
        Map<Long, int[]> pieces = new LinkedHashMap<>();
        Map<Long, BigDecimal> value = new HashMap<>();
        Map<Long, BigDecimal> valueExcl = new HashMap<>();
        for (StockBatchMove take : takesForSalesOrder(salesOrderId)) {
            int taken = Math.abs(take.pieces());
            int[] counts = pieces.computeIfAbsent(take.productId(), id -> new int[2]);
            counts[0] += taken;
            if (take.batchId() == null || take.unitCostEur() == null) {
                counts[1] += taken;
                continue;
            }
            BigDecimal per = BigDecimal.valueOf(Math.max(1, take.unitPieces()));
            BigDecimal amount = take.unitCostEur().multiply(BigDecimal.valueOf(taken))
                    .divide(per, VALUE_SCALE, RoundingMode.HALF_UP);
            BigDecimal excl = (take.unitCostExclEnrosedEur() != null ? take.unitCostExclEnrosedEur()
                    : take.unitCostEur()).multiply(BigDecimal.valueOf(taken)).divide(per, VALUE_SCALE, RoundingMode.HALF_UP);
            value.merge(take.productId(), amount, BigDecimal::add);
            valueExcl.merge(take.productId(), excl, BigDecimal::add);
        }
        Map<Long, StockBatchSaleCost> result = new LinkedHashMap<>();
        pieces.forEach((productId, counts) -> result.put(productId, new StockBatchSaleCost(productId, counts[0],
                value.get(productId), valueExcl.get(productId), counts[1])));
        return result;
    }

    /** The invariant of one product; computed while paused too (that is how drift shows). */
    public StockBatchTotals totals(long productId) {
        int factor = unitPieces(productId);
        int stock = 0;
        for (StockLevelEntity level : levels.list("productId", productId)) {
            stock = Math.addExact(stock, Math.addExact(Math.multiplyExact(level.quantity, factor), loose(level)));
        }
        int batchPieces = 0;
        for (StockBatchEntity batch : batches.list("productId", productId)) {
            batchPieces = Math.addExact(batchPieces, pieces(batch.piecesLeft));
        }
        int unassigned = 0;
        for (Open open : openUnassigned(productId)) unassigned = Math.addExact(unassigned, open.pieces());
        return new StockBatchTotals(productId, stock, batchPieces, unassigned);
    }

    /** Only products whose batches differ from their stock; empty before the start. */
    public List<StockBatchTotals> check() {
        if (!started()) return List.of();
        Map<Long, long[]> stock = new HashMap<>();
        for (Object[] row : levels.getEntityManager().createQuery(
                "select l.productId, sum(l.quantity), sum(coalesce(l.loosePieces, 0)) from StockLevelEntity l "
                        + "group by l.productId", Object[].class).getResultList()) {
            stock.put(((Number) row[0]).longValue(),
                    new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        Map<Long, Long> batchPieces = new HashMap<>();
        for (Object[] row : batches.getEntityManager().createQuery(
                "select b.productId, sum(coalesce(b.piecesLeft, 0)) from StockBatchEntity b group by b.productId",
                Object[].class).getResultList()) {
            batchPieces.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        Map<Long, Integer> unassigned = new HashMap<>();
        List<StockBatchMoveEntity> takes = moves.list(
                "batchId is null and linkMoveId is null and pieces < 0 order by id");
        Map<Long, Integer> consumed = consumedBy(takes);
        for (StockBatchMoveEntity take : takes) {
            int open = Math.max(0, -take.pieces - consumed.getOrDefault(take.id, 0));
            if (open > 0) unassigned.merge(take.productId, open, Integer::sum);
        }
        Set<Long> ids = new TreeSet<>(stock.keySet());
        ids.addAll(batchPieces.keySet());
        ids.addAll(unassigned.keySet());
        Map<Long, Integer> factors = factors(ids);
        List<StockBatchTotals> result = new ArrayList<>();
        for (Long id : ids) {
            long[] held = stock.getOrDefault(id, new long[]{0, 0});
            int stockPieces = Math.toIntExact(held[0] * factors.getOrDefault(id, 1) + held[1]);
            StockBatchTotals totals = new StockBatchTotals(id, stockPieces,
                    Math.toIntExact(batchPieces.getOrDefault(id, 0L)), unassigned.getOrDefault(id, 0));
            if (totals.differencePieces() != 0) result.add(totals);
        }
        return result;
    }

    /* ------------------------------------------------------ backfill support */

    /** One batch the backfill writes; productSupplierId comes from the purchase order line. */
    public record BackfillBatch(StockBatch.Kind kind, StockBatch.Owner owner, Long purchaseOrderId,
            Long purchaseOrderLineId, String orderNumber, Long supplierId, Long productSupplierId,
            LocalDate receivedOn, Instant bookedAt, int unitPieces, int piecesIn, int piecesLeft, BatchCost cost,
            String note) {}

    /**
     * Writes the product's start: per batch (given oldest first: received_on asc unknown first, then purchase order id
     * asc) its in-move of {@code piecesIn} (at booked_at; Beginvoorraad at run time) and, when pieces already left, one
     * BACKFILL move at run time; so a newer batch's BACKFILL move always has the higher id. Then one unassigned
     * BACKFILL move for negative stock. Locks the product.
     */
    @Transactional
    public void writeBackfill(long productId, List<BackfillBatch> list, int unassignedPieces, String unassignedNote,
                              Instant at) {
        lock(productId);
        String who = actorName();
        for (BackfillBatch input : list == null ? List.<BackfillBatch>of() : list) {
            int in = Math.max(0, input.piecesIn());
            int left = Math.max(0, Math.min(input.piecesLeft(), in));
            StockBatch.Kind kind = input.kind() == null ? StockBatch.Kind.OPENING : input.kind();
            StockBatchEntity batch = new StockBatchEntity();
            batch.productId = productId;
            batch.kind = kind.name();
            batch.owner = (input.owner() == null ? StockBatch.Owner.OWN : input.owner()).name();
            batch.purchaseOrderId = input.purchaseOrderId();
            batch.purchaseOrderLineId = input.purchaseOrderLineId();
            batch.orderNumber = cut(input.orderNumber(), ORDER_NUMBER_LENGTH);
            batch.supplierId = input.supplierId();
            batch.productSupplierId = input.productSupplierId();
            batch.receivedOn = input.receivedOn();
            batch.bookedAt = input.bookedAt();
            batch.unitPieces = Math.max(1, input.unitPieces());
            batch.piecesIn = in;
            batch.piecesLeft = left;
            writeCostFields(batch, input.cost());
            batch.note = cut(input.note(), NOTE_LENGTH);
            batch.createdAt = at;
            batch.createdBy = cut(who, ACTOR_LENGTH);
            batches.persist(batch);
            if (in > 0) {
                Instant inAt = kind == StockBatch.Kind.OPENING || input.bookedAt() == null ? at : input.bookedAt();
                String inKind = switch (kind) {
                    case RECEIPT -> PURCHASE_RECEIPT;
                    case OPENING -> OPENING;
                    case COUNT -> COUNT_IN;
                };
                String reference = kind == StockBatch.Kind.RECEIPT ? input.orderNumber() : input.note();
                writeMove(batch.id, productId, null, inKind, in, batch, false,
                        new Ids(null, null, null, input.purchaseOrderId(), reference, inAt, who));
            }
            if (in > left) {
                writeMove(batch.id, productId, null, BACKFILL, -(in - left), batch, false,
                        new Ids(null, null, null, null, null, at, who));
            }
        }
        if (unassignedPieces > 0) {
            unassigned(productId, unassignedPieces, BACKFILL, new Ids(null, null, null, null, unassignedNote, at, who));
        }
        batches.flush();
    }

    /** Records the start; from now on every hook writes. */
    @Transactional
    public StockBatchRun recordStart(int products, int batches, int openingPieces, int unassignedPieces, String note) {
        StockBatchRunEntity run = new StockBatchRunEntity();
        run.ranAt = Instant.now();
        run.ranBy = cut(actorName(), ACTOR_LENGTH);
        run.products = products;
        run.batches = batches;
        run.openingPieces = openingPieces;
        run.unassignedPieces = unassignedPieces;
        run.note = cut(note, RUN_NOTE_LENGTH);
        runs.persist(run);
        runs.flush();
        return toDomain(run);
    }

    /* ============================================================ internals */

    /** What every move of one stock change carries besides its batch, pieces and cost. */
    private record Ids(Long stockMovementId, Long salesOrderId, Long ticaSaleId, Long purchaseOrderId,
                       String reference, Instant at, String actor) {}

    /** A take (batch or unassigned) with the pieces not yet restored, settled or resolved. */
    private record Open(StockBatchMoveEntity take, int pieces) {}

    private void receipt(long productId, Long movementId, int delta, BatchSource source, String reference,
                         Instant at) {
        Ids ids = new Ids(movementId, null, null, source.purchaseOrderId(), reference, at, actorName());
        StockBatchEntity batch = batches.find("purchaseOrderLineId", source.purchaseOrderLineId()).firstResult();
        if (batch != null && !Objects.equals(batch.productId, productId)) {
            /* The line's batch belongs to another product (the line was changed after booking): no guess. */
            if (delta > 0) found(productId, delta, "Ontvangst zonder container", PURCHASE_RECEIPT, ids);
            else take(productId, -delta, RECEIPT_CORRECTION, null, ids);
            return;
        }
        if (batch == null) {
            if (delta < 0) {
                take(productId, -delta, RECEIPT_CORRECTION, null, ids);
                return;
            }
            batch = new StockBatchEntity();
            batch.productId = productId;
            batch.kind = StockBatch.Kind.RECEIPT.name();
            batch.owner = (source.partner() ? StockBatch.Owner.PARTNER : StockBatch.Owner.OWN).name();
            batch.purchaseOrderId = source.purchaseOrderId();
            batch.purchaseOrderLineId = source.purchaseOrderLineId();
            batch.orderNumber = cut(source.orderNumber(), ORDER_NUMBER_LENGTH);
            batch.supplierId = source.supplierId();
            batch.productSupplierId = source.productSupplierId();
            batch.receivedOn = source.receivedOn();
            batch.bookedAt = at;
            batch.unitPieces = unitPieces(productId);
            batch.piecesIn = delta;
            batch.piecesLeft = delta;
            writeCostFields(batch, source.cost());
            batch.createdAt = at;
            batch.createdBy = cut(ids.actor(), ACTOR_LENGTH);
            batches.persist(batch);
            writeMove(batch.id, productId, null, PURCHASE_RECEIPT, delta, batch, false, ids);
            if (!isPartner(batch)) settle(batch, ids);
            return;
        }
        if (delta > 0) {
            batch.piecesIn = pieces(batch.piecesIn) + delta;
            batch.piecesLeft = pieces(batch.piecesLeft) + delta;
            writeMove(batch.id, productId, null, RECEIPT_CORRECTION, delta, batch, false, ids);
            if (!isPartner(batch)) settle(batch, ids);
            return;
        }
        int wanted = -delta;
        int given = Math.min(wanted, pieces(batch.piecesLeft));
        if (given > 0) {
            batch.piecesIn = pieces(batch.piecesIn) - given;
            batch.piecesLeft = pieces(batch.piecesLeft) - given;
            writeMove(batch.id, productId, null, RECEIPT_CORRECTION, -given, batch, false, ids);
        }
        if (wanted > given) take(productId, wanted - given, RECEIPT_CORRECTION, null, ids);
    }

    /** Pinned batches first (that container's, oldest first), then own FIFO, then partner FIFO, then unassigned. */
    private void take(long productId, int pieces, String kind, Long pin, Ids ids) {
        if (pieces <= 0) return;
        int remaining = pieces;
        List<StockBatchEntity> live = new ArrayList<>(batches.list("productId = ?1 and piecesLeft > 0", productId));
        live.sort(FIFO);
        if (pin != null) {
            for (StockBatchEntity batch : live) {
                if (!pin.equals(batch.purchaseOrderId)) continue;
                remaining -= takeFrom(batch, remaining, kind, true, ids);
                if (remaining == 0) return;
            }
        }
        for (StockBatchEntity batch : live) {
            remaining -= takeFrom(batch, remaining, kind, false, ids);
            if (remaining == 0) return;
        }
        unassigned(productId, remaining, kind, ids);
    }

    private int takeFrom(StockBatchEntity batch, int wanted, String kind, boolean pinned, Ids ids) {
        int given = Math.min(wanted, pieces(batch.piecesLeft));
        if (given <= 0) return 0;
        batch.piecesLeft = pieces(batch.piecesLeft) - given;
        writeMove(batch.id, batch.productId, null, kind, -given, batch, pinned, ids);
        return given;
    }

    private void unassigned(long productId, int pieces, String kind, Ids ids) {
        if (pieces <= 0) return;
        StockBatchMoveEntity move = baseMove(null, productId, null, kind, -pieces, ids);
        move.unitPieces = unitPieces(productId);
        move.pinned = false;
        moves.persist(move);
    }

    /**
     * Goods coming back: every restore links one take and gives back at most its open pieces, so a batch never holds
     * more than came in. (1) the preferred document's open takes, most recent first, then its open unassigned takes
     * (resolved); (2) open SALE/BACKFILL takes on this product's batches, most recent first; (3) open unassigned takes,
     * oldest first (resolved); (4) the rest is found ("Retour zonder herkomst").
     */
    private void restore(long productId, int pieces, String kind, Long preferSalesOrderId, Long preferTicaSaleId,
                         Ids ids) {
        int remaining = pieces;
        if (preferSalesOrderId != null || preferTicaSaleId != null) {
            List<StockBatchMoveEntity> preferred = preferSalesOrderId != null
                    ? moves.list("productId = ?1 and salesOrderId = ?2 and pieces < 0", productId, preferSalesOrderId)
                    : moves.list("productId = ?1 and ticaSaleId = ?2 and pieces < 0", productId, preferTicaSaleId);
            List<StockBatchMoveEntity> batchTakes = new ArrayList<>();
            List<StockBatchMoveEntity> unassignedTakes = new ArrayList<>();
            for (StockBatchMoveEntity take : preferred) {
                if (take.batchId != null) batchTakes.add(take);
                else if (take.linkMoveId == null) unassignedTakes.add(take);
            }
            remaining = restoreInto(batchTakes, remaining, kind, ids);
            unassignedTakes.sort(Comparator.comparing((StockBatchMoveEntity m) -> m.id).reversed());
            remaining = resolve(open(unassignedTakes), remaining, kind, ids);
        }
        if (remaining > 0) {
            remaining = restoreInto(moves.list("productId = ?1 and batchId is not null and pieces < 0 and kind in ?2",
                    productId, List.of(SALE, BACKFILL)), remaining, kind, ids);
        }
        if (remaining > 0) remaining = resolve(openUnassigned(productId), remaining, kind, ids);
        if (remaining > 0) found(productId, remaining, "Retour zonder herkomst", kind, ids);
    }

    /** Restores into the batches of these takes, most recently consumed first; returns what is left to place. */
    private int restoreInto(List<StockBatchMoveEntity> takes, int pieces, String kind, Ids ids) {
        if (pieces <= 0 || takes.isEmpty()) return pieces;
        Map<Long, StockBatchEntity> byId = new HashMap<>();
        for (StockBatchMoveEntity take : takes) {
            byId.computeIfAbsent(take.batchId, batches::findById);
        }
        List<Open> open = new ArrayList<>(open(takes));
        open.removeIf(candidate -> byId.get(candidate.take().batchId) == null);
        open.sort(Comparator.comparing((Open candidate) -> byId.get(candidate.take().batchId), MOST_RECENT)
                .thenComparing(candidate -> candidate.take().id, Comparator.reverseOrder()));
        int remaining = pieces;
        for (Open candidate : open) {
            if (remaining == 0) break;
            StockBatchEntity batch = byId.get(candidate.take().batchId);
            int given = Math.min(remaining, candidate.pieces());
            if (given <= 0) continue;
            batch.piecesLeft = pieces(batch.piecesLeft) + given;
            writeMove(batch.id, batch.productId, candidate.take().id, kind, given, batch, false,
                    new Ids(ids.stockMovementId(), ids.salesOrderId(), ids.ticaSaleId(), null, ids.reference(),
                            ids.at(), ids.actor()));
            remaining -= given;
        }
        return remaining;
    }

    /** Resolves open unassigned takes in the given order, without a batch; returns what is left. */
    private int resolve(List<Open> open, int pieces, String kind, Ids ids) {
        int remaining = pieces;
        for (Open candidate : open) {
            if (remaining == 0) break;
            int given = Math.min(remaining, candidate.pieces());
            if (given <= 0) continue;
            StockBatchMoveEntity move = baseMove(null, candidate.take().productId, candidate.take().id, kind, given,
                    new Ids(ids.stockMovementId(), ids.salesOrderId(), ids.ticaSaleId(), null, ids.reference(),
                            ids.at(), ids.actor()));
            move.unitPieces = candidate.take().unitPieces;
            move.pinned = false;
            moves.persist(move);
            remaining -= given;
        }
        return remaining;
    }

    /** Found pieces: resolve open unassigned takes oldest first, the rest into today's Telling batch. */
    private void found(long productId, int pieces, String note, String resolveKind, Ids ids) {
        int remaining = resolve(openUnassigned(productId), pieces, resolveKind, ids);
        if (remaining <= 0) return;
        ProductEntity product = products.findById(productId);
        if (product == null) throw new NotFoundException("Product", productId);
        int factor = unitPieces(product);
        BigDecimal card = product.landedCostEur == null || product.landedCostEur.signum() <= 0
                ? null : product.landedCostEur.setScale(SCALE, RoundingMode.HALF_UP);
        LocalDate today = LocalDate.now(BRUSSELS);
        StockBatchEntity batch = batches.list("productId = ?1 and kind = ?2 and receivedOn = ?3 order by id",
                        productId, StockBatch.Kind.COUNT.name(), today).stream()
                .filter(candidate -> unitPieces(candidate) == factor && sameAmount(candidate.unitCostEur, card))
                .findFirst().orElse(null);
        String reference = ids.reference() != null ? ids.reference() : note;
        Ids countIds = new Ids(ids.stockMovementId(), null, null, null, reference, ids.at(), ids.actor());
        if (batch != null) {
            batch.piecesIn = pieces(batch.piecesIn) + remaining;
            batch.piecesLeft = pieces(batch.piecesLeft) + remaining;
            writeMove(batch.id, productId, null, COUNT_IN, remaining, batch, false, countIds);
            return;
        }
        batch = new StockBatchEntity();
        batch.productId = productId;
        batch.kind = StockBatch.Kind.COUNT.name();
        batch.owner = StockBatch.Owner.OWN.name();
        batch.receivedOn = today;
        batch.bookedAt = ids.at();
        batch.unitPieces = factor;
        batch.piecesIn = remaining;
        batch.piecesLeft = remaining;
        batch.costState = (card == null ? StockBatch.CostState.NONE : StockBatch.CostState.CARD).name();
        batch.unitCostEur = card;
        batch.note = cut(note, NOTE_LENGTH);
        batch.createdAt = ids.at();
        batch.createdBy = cut(ids.actor(), ACTOR_LENGTH);
        batches.persist(batch);
        writeMove(batch.id, productId, null, COUNT_IN, remaining, batch, false, countIds);
    }

    /** A new own receipt (or its correction upwards) settles open unassigned takes, oldest first. */
    private void settle(StockBatchEntity batch, Ids ids) {
        for (Open candidate : openUnassigned(batch.productId)) {
            int left = pieces(batch.piecesLeft);
            if (left == 0) return;
            int given = Math.min(left, candidate.pieces());
            if (given <= 0) continue;
            StockBatchMoveEntity take = candidate.take();
            batch.piecesLeft = left - given;
            StockBatchMoveEntity move = baseMove(batch.id, batch.productId, take.id, take.kind, -given,
                    new Ids(ids.stockMovementId(), take.salesOrderId, take.ticaSaleId, take.purchaseOrderId,
                            take.reference, ids.at(), ids.actor()));
            /* The sale already used the unassigned move's cost; the sales line is not rewritten. */
            move.unitPieces = take.unitPieces;
            move.unitCostEur = take.unitCostEur;
            move.unitCostExclEnrosedEur = take.unitCostExclEnrosedEur;
            move.pinned = false;
            moves.persist(move);
        }
    }

    /** Open unassigned takes of the product, oldest first. */
    private List<Open> openUnassigned(long productId) {
        return open(moves.list("productId = ?1 and batchId is null and linkMoveId is null and pieces < 0 order by id",
                productId));
    }

    /** Each take with its open pieces (−pieces − Σ|moves linking it|), in the given order; fully used ones dropped. */
    private List<Open> open(List<StockBatchMoveEntity> takes) {
        Map<Long, Integer> consumed = consumedBy(takes);
        List<Open> result = new ArrayList<>();
        for (StockBatchMoveEntity take : takes) {
            int open = -pieces(take.pieces) - consumed.getOrDefault(take.id, 0);
            if (open > 0) result.add(new Open(take, open));
        }
        return result;
    }

    /** Per take id: Σ |pieces| of the moves linking it (restores of a take; settles and resolves of an unassigned). */
    private Map<Long, Integer> consumedBy(List<StockBatchMoveEntity> takes) {
        Map<Long, Integer> consumed = new HashMap<>();
        if (takes.isEmpty()) return consumed;
        List<Long> ids = takes.stream().map(take -> take.id).toList();
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            for (StockBatchMoveEntity link : moves.list("linkMoveId in ?1", chunk)) {
                consumed.merge(link.linkMoveId, Math.abs(pieces(link.pieces)), Integer::sum);
            }
        }
        return consumed;
    }

    private void writeMove(Long batchId, long productId, Long linkMoveId, String kind, int pieces,
                           StockBatchEntity costOf, boolean pinned, Ids ids) {
        StockBatchMoveEntity move = baseMove(batchId, productId, linkMoveId, kind, pieces, ids);
        move.unitPieces = unitPieces(costOf);
        move.unitCostEur = costOf.unitCostEur;
        move.unitCostExclEnrosedEur = costOf.unitCostExclEnrosedEur;
        move.pinned = pinned;
        moves.persist(move);
    }

    private static StockBatchMoveEntity baseMove(Long batchId, long productId, Long linkMoveId, String kind,
                                                 int pieces, Ids ids) {
        StockBatchMoveEntity move = new StockBatchMoveEntity();
        move.batchId = batchId;
        move.productId = productId;
        move.stockMovementId = ids.stockMovementId();
        move.linkMoveId = linkMoveId;
        move.kind = kind;
        move.pieces = pieces;
        move.salesOrderId = ids.salesOrderId();
        move.ticaSaleId = ids.ticaSaleId();
        move.purchaseOrderId = ids.purchaseOrderId();
        move.reference = cut(ids.reference(), REFERENCE_LENGTH);
        move.movedAt = ids.at() == null ? Instant.now() : ids.at();
        move.actor = cut(ids.actor(), ACTOR_LENGTH);
        return move;
    }

    private static void writeCostFields(StockBatchEntity batch, BatchCost given) {
        BatchCost cost = given == null || given.unitCostEur() == null ? BatchCost.NONE : given;
        batch.costState = (cost.state() == null || cost.unitCostEur() == null
                ? StockBatch.CostState.NONE : cost.state()).name();
        batch.unitCostEur = cost.unitCostEur();
        batch.unitCostExclEnrosedEur = cost.unitCostExclEnrosedEur();
        ProductCostBreakdown parts = cost.parts();
        batch.goodsEur = parts == null ? null : parts.goodsEur();
        batch.originEur = parts == null ? null : parts.originEur();
        batch.freightEur = parts == null ? null : parts.freightEur();
        batch.dutyEur = parts == null ? null : parts.dutyEur();
        batch.destinationEur = parts == null ? null : parts.destinationEur();
        batch.enrosedEur = parts == null ? null : parts.enrosedEur();
        batch.separateEur = parts == null ? null : parts.separateEur();
        batch.costAppliedAt = cost.appliedAt();
        batch.costAppliedBy = cut(cost.appliedBy(), ACTOR_LENGTH);
    }

    /** The domain view with the screens' buckets; two aggregate queries for all given batches. */
    private List<StockBatch> toDomain(List<StockBatchEntity> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, StockBatchEntity> byId = new LinkedHashMap<>();
        for (StockBatchEntity row : rows) byId.put(row.id, row);
        /* bucket per batch: 0 sold, 1 lost, 2 earlier */
        Map<Long, long[]> taken = new HashMap<>();
        Map<Long, long[]> restored = new HashMap<>();
        List<Long> ids = new ArrayList<>(byId.keySet());
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            for (Object[] row : moves.getEntityManager().createQuery(
                    "select m.batchId, m.kind, m.purchaseOrderId, sum(m.pieces) from StockBatchMoveEntity m "
                            + "where m.batchId in :ids and m.pieces < 0 group by m.batchId, m.kind, m.purchaseOrderId",
                    Object[].class).setParameter("ids", chunk).getResultList()) {
                addBucket(taken, byId, row, -1);
            }
            for (Object[] row : moves.getEntityManager().createQuery(
                    "select r.batchId, t.kind, t.purchaseOrderId, sum(r.pieces) from StockBatchMoveEntity r, "
                            + "StockBatchMoveEntity t where t.id = r.linkMoveId and r.batchId in :ids and r.pieces > 0 "
                            + "group by r.batchId, t.kind, t.purchaseOrderId",
                    Object[].class).setParameter("ids", chunk).getResultList()) {
                addBucket(restored, byId, row, 1);
            }
        }
        List<StockBatch> result = new ArrayList<>(rows.size());
        for (StockBatchEntity row : rows) {
            long[] out = taken.getOrDefault(row.id, new long[3]);
            long[] back = restored.getOrDefault(row.id, new long[3]);
            int sold = (int) Math.max(0, out[0] - back[0]);
            int lost = (int) Math.max(0, out[1] - back[1]);
            int earlier = (int) Math.max(0, out[2] - back[2]);
            int other = pieces(row.piecesIn) - pieces(row.piecesLeft) - sold - lost - earlier;
            result.add(toDomain(row, sold, lost, earlier, other));
        }
        return result;
    }

    private static void addBucket(Map<Long, long[]> buckets, Map<Long, StockBatchEntity> byId, Object[] row,
                                  int sign) {
        Long batchId = ((Number) row[0]).longValue();
        String kind = (String) row[1];
        Long purchaseOrderId = row[2] == null ? null : ((Number) row[2]).longValue();
        long pieces = row[3] == null ? 0 : ((Number) row[3]).longValue() * sign;
        StockBatchEntity batch = byId.get(batchId);
        int bucket;
        if (SALE.equals(kind)) bucket = 0;
        else if (LOST_KINDS.contains(kind)) bucket = 1;
        else if (RECEIPT_CORRECTION.equals(kind) && batch != null
                && !Objects.equals(purchaseOrderId, batch.purchaseOrderId)) bucket = 1;
        else if (BACKFILL.equals(kind)) bucket = 2;
        else return;
        buckets.computeIfAbsent(batchId, id -> new long[3])[bucket] += pieces;
    }

    private static StockBatch toDomain(StockBatchEntity row, int sold, int lost, int earlier, int other) {
        ProductCostBreakdown parts = new ProductCostBreakdown(row.goodsEur, row.originEur, row.freightEur,
                row.dutyEur, row.destinationEur, row.enrosedEur, row.separateEur);
        return new StockBatch(row.id, row.productId == null ? 0 : row.productId,
                parse(StockBatch.Kind.class, row.kind, StockBatch.Kind.RECEIPT),
                parse(StockBatch.Owner.class, row.owner, StockBatch.Owner.OWN),
                row.purchaseOrderId, row.purchaseOrderLineId, row.orderNumber, row.supplierId, row.productSupplierId,
                row.receivedOn, row.bookedAt, unitPieces(row), pieces(row.piecesIn), pieces(row.piecesLeft),
                parse(StockBatch.CostState.class, row.costState, StockBatch.CostState.NONE), row.unitCostEur,
                row.unitCostExclEnrosedEur, parts.isEmpty() ? null : parts, row.costAppliedAt, row.costAppliedBy,
                row.note, sold, lost, earlier, other);
    }

    private static StockBatchMove toDomain(StockBatchMoveEntity row) {
        return new StockBatchMove(row.id, row.batchId, row.productId == null ? 0 : row.productId,
                row.stockMovementId, row.linkMoveId, row.kind, pieces(row.pieces),
                row.unitPieces == null || row.unitPieces < 1 ? 1 : row.unitPieces, row.unitCostEur,
                row.unitCostExclEnrosedEur, row.salesOrderId, row.ticaSaleId, row.purchaseOrderId,
                Boolean.TRUE.equals(row.pinned), row.reference, row.movedAt, row.actor);
    }

    private static StockBatchRun toDomain(StockBatchRunEntity row) {
        return new StockBatchRun(row.id, row.ranAt, row.ranBy, pieces(row.products), pieces(row.batches),
                pieces(row.openingPieces), pieces(row.unassignedPieces), row.note, row.pausedAt, row.pausedBy);
    }

    private StockBatchRunEntity latestRun() {
        List<StockBatchRunEntity> latest = runs.find("order by id desc").page(0, 1).list();
        return latest.isEmpty() ? null : latest.getFirst();
    }

    private void requireActive() {
        StockBatchRunEntity latest = latestRun();
        if (latest == null) throw new BusinessRuleException(NOT_STARTED);
        if (latest.pausedAt != null) throw new BusinessRuleException(PAUSED);
    }

    private void lock(long productId) {
        if (products.findById(productId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new NotFoundException("Product", productId);
        }
    }

    /** StockService's rule: pieces per selling unit when sold per display, else 1. */
    private int unitPieces(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) throw new NotFoundException("Product", productId);
        return unitPieces(product);
    }

    private static int unitPieces(ProductEntity product) {
        return product.packaging().soldAsDisplay() ? product.packaging().unitPieces() : 1;
    }

    /** The same rule over the packaging columns only, for many products at once (no product rows loaded). */
    private Map<Long, Integer> factors(Collection<Long> productIds) {
        Map<Long, Integer> result = new HashMap<>();
        List<Long> ids = new ArrayList<>(productIds);
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            for (Object[] row : products.getEntityManager().createQuery(
                    "select p.id, p.packagingKind, p.packagingSalesUnit, p.packagingPiecesPerUnit from ProductEntity p "
                            + "where p.id in :ids", Object[].class).setParameter("ids", chunk).getResultList()) {
                Packaging packaging = new Packaging((PackagingKind) row[1], Dimensions.empty(), null,
                        (Integer) row[3], (SalesUnit) row[2]);
                result.put(((Number) row[0]).longValue(), packaging.soldAsDisplay() ? packaging.unitPieces() : 1);
            }
        }
        return result;
    }

    private static int unitPieces(StockBatchEntity batch) {
        return batch.unitPieces == null || batch.unitPieces < 1 ? 1 : batch.unitPieces;
    }

    private static boolean isPartner(StockBatchEntity batch) {
        return StockBatch.Owner.PARTNER.name().equals(batch.owner);
    }

    private static int loose(StockLevelEntity level) {
        return level.loosePieces == null ? 0 : level.loosePieces;
    }

    private static int pieces(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal convert(BigDecimal value, int batchUnitPieces, int costUnitPieces) {
        if (value == null) return null;
        if (batchUnitPieces == costUnitPieces) return value;
        return value.multiply(BigDecimal.valueOf(batchUnitPieces))
                .divide(BigDecimal.valueOf(costUnitPieces), SCALE, RoundingMode.HALF_UP);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    private String actorName() {
        return actor.name();
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
