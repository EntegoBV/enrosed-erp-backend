package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchMoveEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchRunEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.BatchSource;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.catalog.domain.StockBatchSaleCost;
import be.enrosed.catalog.domain.StockBatchTotals;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch core (batches contract §3.1-§3.3, §3.6; tests B1 (1)-(12), (14), (15) and the TICA replay). Every case
 * but the replay runs in one rolled-back transaction, so its start row never leaks into other tests. The test
 * database is shared, so "check() is empty" is asserted for this test's own products.
 */
@QuarkusTest
class StockBatchServiceTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 10);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 10);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 10);

    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject ProductService products;
    @Inject StockLocationBackfill locationBackfill;
    @Inject EntityManager entities;

    /** Purchase order and line ids far above anything the shared test database holds. */
    private final long base = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000) * 100;

    /* ------------------------------------------------------------------ (1) */

    @Test
    @TestTransaction
    void nothingIsWrittenBeforeTheStartAndAPauseOnlyLetsTheDriftShow() {
        long early = product(1, "24");
        long main = main();
        stock.add(early, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-EARLY",
                receipt(early, 1, "PO-EARLY", false, JAN, cost("2")));
        stock.sell(early, main, 3, "F-EARLY", StockBatchContext.sale(base + 90, null));
        assertFalse(batches.started());
        assertFalse(batches.active());
        assertTrue(batches.forProduct(early).isEmpty(), "no batch before the start");
        assertEquals(0, movesOf(early).size(), "no batch move before the start");
        assertTrue(batches.check().isEmpty(), "nothing to check before the start");

        start();
        assertTrue(batches.started());
        assertTrue(batches.active());
        long product = product(1, "24");
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-1",
                receipt(product, 2, "PO-1", false, JAN, cost("2")));
        assertEquals(1, batches.forProduct(product).size());

        batches.pause();
        assertTrue(batches.started());
        assertFalse(batches.active());
        int movesBefore = movesOf(product).size();
        stock.sell(product, main, 4, "F-PAUSED");
        stock.add(product, main, 2, StockMovement.Kind.PURCHASE_RECEIPT, "PO-1 correctie",
                receipt(product, 2, "PO-1", false, JAN, cost("2")));
        assertEquals(movesBefore, movesOf(product).size(), "paused: hooks write nothing");
        assertEquals(10, batches.forProduct(product).getFirst().piecesLeft());

        StockBatchTotals drift = checkRow(product);
        assertNotNull(drift, "the paused drift shows in check()");
        assertEquals(8, drift.stockPieces());
        assertEquals(10, drift.batchPieces());
        assertEquals(2, drift.differencePieces());
    }

    /* ------------------------------------------------------------------ (2) */

    @Test
    @TestTransaction
    void aReceiptBecomesABatchInPiecesAndTakesGoOpeningThenOwnByDateThenPartner() {
        long main = main();
        long display = product(12, "99.8160");
        long product = product(1, "1");
        stock.setLevel(product, main, 10, StockMovement.Kind.STOCKTAKE, "Opening count");
        start();
        BatchCost calculated = new BatchCost(StockBatch.CostState.CALCULATED, new BigDecimal("8.3180"),
                new BigDecimal("7.9012"), new ProductCostBreakdown(new BigDecimal("5.1000"), null, null, null, null,
                new BigDecimal("0.4168"), null), null, null);
        stock.add(display, main, 50, StockMovement.Kind.PURCHASE_RECEIPT, "PO-2026-005",
                new StockBatchContext(null, null, null, new BatchSource(base + 39, base + 391, "PO-2026-005", 3L,
                        base + 7, false, LocalDate.of(2026, 8, 20), calculated)));
        StockBatch batch = batches.forProduct(display).getFirst();
        assertEquals(StockBatch.Kind.RECEIPT, batch.kind());
        assertEquals(StockBatch.Owner.OWN, batch.owner());
        assertEquals(12, batch.unitPieces());
        assertEquals(600, batch.piecesIn(), "50 displays of 12");
        assertEquals(600, batch.piecesLeft());
        assertEquals(StockBatch.CostState.CALCULATED, batch.costState());
        assertEquals(new BigDecimal("8.3180"), batch.unitCostEur());
        assertEquals(new BigDecimal("7.9012"), batch.unitCostExclEnrosedEur());
        assertEquals(new BigDecimal("5.1000"), batch.parts().goodsEur());
        assertEquals(3L, batch.supplierId());
        assertEquals(base + 7, batch.productSupplierId());
        assertEquals(base + 39, batch.purchaseOrderId());
        assertEquals(base + 391, batch.purchaseOrderLineId());
        assertEquals("PO-2026-005", batch.orderNumber());
        assertEquals(LocalDate.of(2026, 8, 20), batch.receivedOn());
        assertNotNull(batch.bookedAt());
        assertEquals(new BigDecimal("415.90"), batch.valueLeftEur(), "600 × 8,3180 / 12");
        List<StockBatchMove> in = batches.moves(batch.id(), 10);
        assertEquals(1, in.size());
        assertEquals("PURCHASE_RECEIPT", in.getFirst().kind());
        assertEquals(600, in.getFirst().pieces());
        assertEquals(base + 39, in.getFirst().purchaseOrderId());
        assertEquals(new BigDecimal("8.3180"), in.getFirst().unitCostEur());
        assertNotNull(in.getFirst().stockMovementId(), "the move points at its stock movement");

        /* FIFO: Beginvoorraad, then own by received_on (not by booking order), then partner. */
        batches.writeBackfill(product, List.of(new StockBatchService.BackfillBatch(StockBatch.Kind.OPENING,
                StockBatch.Owner.OWN, null, null, null, null, null, null, null, 1, 10, 10,
                new BatchCost(StockBatch.CostState.CARD, BigDecimal.ONE, null, null, null, null),
                "Beginvoorraad, kost van de productkaart")), 0, null, Instant.now());
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 11, "PO-A", false, MAR, cost("3")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-P",
                receipt(product, 12, "PO-P", true, JAN, cost("4")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-B",
                receipt(product, 13, "PO-B", false, FEB, cost("5")));
        assertEquals(List.of("Beginvoorraad", "PO-B", "PO-A", "PO-P"), titles(batches.forProduct(product)));
        assertEquals(StockBatch.Owner.PARTNER, batches.forProduct(product).get(3).owner());

        stock.sell(product, main, 25, "F-FIFO");
        assertEquals(List.of(0, 0, 5, 10), lefts(product, "Beginvoorraad", "PO-B", "PO-A", "PO-P"));
        stock.sell(product, main, 8, "F-FIFO-2");
        assertEquals(List.of(0, 0, 0, 7), lefts(product, "Beginvoorraad", "PO-B", "PO-A", "PO-P"),
                "partner pieces only once every own batch is empty");
        assertEquals(0, batches.totals(product).differencePieces());
        assertEquals(0, batches.totals(display).differencePieces());
    }

    /* ------------------------------------------------------------------ (3) */

    @Test
    @TestTransaction
    void damageReportedOnAContainerTakesFromItsBatchFirstAndOverflowsFifo() {
        long main = main();
        long product = product(1, "2");
        start();
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 1, "PO-A", false, JAN, cost("2")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-B",
                receipt(product, 2, "PO-B", false, FEB, cost("3")));
        stock.takeOut(product, main, 15, StockMovement.Kind.DAMAGED, "Kapot bij uitpakken", base + 2);

        StockBatch a = byTitle(product, "PO-A");
        StockBatch b = byTitle(product, "PO-B");
        assertEquals(0, b.piecesLeft(), "the reported container's batch goes first");
        assertEquals(5, a.piecesLeft(), "the overflow follows FIFO");
        assertEquals(10, b.lostPieces());
        assertEquals(5, a.lostPieces());
        StockBatchMove pinned = batches.moves(b.id(), 5).getFirst();
        StockBatchMove overflow = batches.moves(a.id(), 5).getFirst();
        assertEquals("DAMAGED", pinned.kind());
        assertTrue(pinned.pinned());
        assertFalse(overflow.pinned());
        assertEquals(base + 2, pinned.purchaseOrderId());
        assertEquals(base + 2, overflow.purchaseOrderId(), "every move of the take names the reported container");
        assertEquals(-10, pinned.pieces());
        assertEquals(-5, overflow.pieces());
        assertEquals(0, batches.totals(product).differencePieces());
    }

    /* ------------------------------------------------------------------ (4) */

    @Test
    @TestTransaction
    void aSaleBelowZeroIsUnassignedUntilTheNextOwnReceiptSettlesIt() {
        long main = main();
        long product = product(1, "2");
        start();
        stock.sell(product, main, 5, "F-NEG", StockBatchContext.sale(base + 77, null));
        StockBatchTotals afterSale = batches.totals(product);
        assertEquals(-5, afterSale.stockPieces());
        assertEquals(0, afterSale.batchPieces());
        assertEquals(5, afterSale.unassignedPieces());
        assertEquals(0, afterSale.differencePieces());
        StockBatchMove unassigned = batches.takesForSalesOrder(base + 77).getFirst();
        assertNull(unassigned.batchId());
        assertEquals(-5, unassigned.pieces());

        stock.add(product, main, 3, StockMovement.Kind.PURCHASE_RECEIPT, "PO-P",
                receipt(product, 1, "PO-P", true, JAN, cost("4")));
        assertEquals(5, batches.totals(product).unassignedPieces(), "a partner receipt settles nothing");
        assertEquals(3, byTitle(product, "PO-P").piecesLeft());

        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 2, "PO-A", false, FEB, cost("2")));
        StockBatch own = byTitle(product, "PO-A");
        assertEquals(10, own.piecesIn());
        assertEquals(5, own.piecesLeft(), "the own receipt settles the open sale");
        assertEquals(5, own.soldPieces(), "a settled sale counts as sold");
        StockBatchMove settle = batches.moves(own.id(), 5).getFirst();
        assertEquals(unassigned.id(), settle.linkMoveId());
        assertEquals("SALE", settle.kind());
        assertEquals(base + 77, settle.salesOrderId());
        assertNull(settle.unitCostEur(), "the settle keeps the unassigned move's cost (none)");
        assertEquals(1, batches.takesForSalesOrder(base + 77).size(), "the settle is not a take of the shipment");
        StockBatchTotals totals = batches.totals(product);
        assertEquals(0, totals.unassignedPieces());
        assertEquals(0, totals.differencePieces());
    }

    /* ------------------------------------------------------------------ (5) */

    @Test
    @TestTransaction
    void aCreditReturnRestoresTheMostRecentBatchByReceiptDateCappedThenTheLastConsumedThenTelling() {
        long main = main();
        long product = product(1, "24");
        start();
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 1, "PO-A", false, JAN, cost("2")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-B",
                receipt(product, 2, "PO-B", false, FEB, cost("3")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-C",
                receipt(product, 3, "PO-C", false, MAR, cost("4")));
        long otherSale = base + 50;
        long invoice = base + 51;
        stock.sell(product, main, 4, "F-Y", StockBatchContext.sale(otherSale, null));
        /* A container sale pinned to C: C's take is written first, A's overflow gets the higher move id. */
        stock.sell(product, main, 15, "F-X", StockBatchContext.sale(invoice, base + 3));
        List<StockBatchMove> taken = batches.takesForSalesOrder(invoice);
        assertEquals(2, taken.size());
        assertEquals(byTitle(product, "PO-C").id(), taken.get(0).batchId());
        assertEquals(byTitle(product, "PO-A").id(), taken.get(1).batchId());
        assertTrue(taken.get(1).id() > taken.get(0).id(), "the older batch's move has the higher id");
        assertEquals(1, byTitle(product, "PO-A").piecesLeft());

        stock.add(product, main, 12, StockMovement.Kind.SALE_RETURN, "C-1",
                StockBatchContext.saleReturn(base + 60, invoice));
        assertEquals(10, byTitle(product, "PO-C").piecesLeft(), "C (received last) is restored first, capped at its take");
        assertEquals(3, byTitle(product, "PO-A").piecesLeft(), "then A gets the remaining 2");
        StockBatchMove restore = batches.moves(byTitle(product, "PO-A").id(), 1).getFirst();
        assertEquals("SALE_RETURN", restore.kind());
        assertEquals(base + 60, restore.salesOrderId(), "the restore carries the credit note");
        assertEquals(taken.get(1).id(), restore.linkMoveId());

        stock.add(product, main, 10, StockMovement.Kind.SALE_RETURN, "C-2",
                StockBatchContext.saleReturn(base + 61, invoice));
        StockBatch a = byTitle(product, "PO-A");
        assertEquals(10, a.piecesLeft(), "the invoice's last 3, then the other sale's 4 on A (the last consumed batch)");
        assertEquals(10, a.piecesIn());
        assertEquals(0, a.soldPieces());
        StockBatch telling = byTitle(product, "Telling");
        assertEquals(3, telling.piecesLeft(), "what no take can take back goes into a Telling batch");
        assertEquals(StockBatch.CostState.CARD, telling.costState());
        assertEquals(new BigDecimal("24.0000"), telling.unitCostEur());
        assertEquals("Retour zonder herkomst", telling.note());
        for (StockBatch batch : batches.forProduct(product)) {
            assertTrue(batch.piecesLeft() <= batch.piecesIn(), "a batch never holds more than came in");
        }
        assertEquals(0, batches.totals(product).differencePieces());
    }

    /* ------------------------------------------------------------------ (6) */

    @Test
    @TestTransaction
    void ticaStemsOfADisplayTakeSinglePiecesAndReturnsNeverMakeATellingBatch() {
        long main = main();
        long display = product(12, "24");
        start();
        stock.add(display, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-D",
                receipt(display, 1, "PO-D", false, JAN, cost("24")));
        stock.sell(display, main, 1, "F-D", StockBatchContext.sale(base + 40, null));
        long sale = base + 41;
        tica(display, main, -5, sale, base + 1001, "TICA 1/1 · verkoop/retour");
        StockBatch batch = byTitle(display, "PO-D");
        assertEquals(103, batch.piecesLeft(), "120 − 12 − 5 stems");
        StockBatchMove stems = batches.moves(batch.id(), 1).getFirst();
        assertEquals(-5, stems.pieces());
        assertEquals("SALE", stems.kind());
        assertEquals(sale, stems.ticaSaleId());

        tica(display, main, 5, sale, base + 1002, "TICA 1/1 · correctie");
        assertEquals(108, byTitle(display, "PO-D").piecesLeft());
        assertEquals(stems.id(), batches.moves(batch.id(), 1).getFirst().linkMoveId(), "the reversal restores that sale");

        tica(display, main, 3, base + 42, base + 1003, "TICA 2/1 · verkoop/retour");
        assertEquals(111, byTitle(display, "PO-D").piecesLeft(), "a return under a new sale id goes back to the batch");
        assertTrue(batches.forProduct(display).stream().noneMatch(b -> b.kind() == StockBatch.Kind.COUNT),
                "no Telling batch");
        assertEquals(9, stock.quantityAt(display, main));
        assertEquals(3, stock.loosePiecesAt(display, main));
        assertEquals(0, batches.totals(display).differencePieces());
    }

    /* ------------------------------------------------------------------ (7) */

    @Test
    @TestTransaction
    void aRecountDownTakesFifoAndUpResolvesUnassignedThenOneTellingBatchPerDay() {
        long main = main();
        long product = product(1, "24");
        start();
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 1, "PO-A", false, JAN, cost("2")));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-B",
                receipt(product, 2, "PO-B", false, FEB, cost("3")));
        stock.setLevel(product, main, 15, StockMovement.Kind.MANUAL_CORRECTION, "Telling");
        assertEquals(5, byTitle(product, "PO-A").piecesLeft(), "a count down takes the oldest batch");
        assertEquals(5, byTitle(product, "PO-A").lostPieces());

        stock.sell(product, main, 20, "F-OVER");
        assertEquals(5, batches.totals(product).unassignedPieces());
        stock.setLevel(product, main, 0, StockMovement.Kind.STOCKTAKE, "Telling 0");
        assertEquals(0, batches.totals(product).unassignedPieces(), "found pieces resolve the unassigned first");
        assertTrue(batches.forProduct(product).stream().noneMatch(b -> b.kind() == StockBatch.Kind.COUNT));

        stock.setLevel(product, main, 3, StockMovement.Kind.STOCKTAKE, "Telling 3");
        stock.setLevel(product, main, 5, StockMovement.Kind.STOCKTAKE, "Telling 5");
        List<StockBatch> counts = batches.forProduct(product).stream()
                .filter(b -> b.kind() == StockBatch.Kind.COUNT).toList();
        assertEquals(1, counts.size(), "finds of one day at one cost merge");
        StockBatch telling = counts.getFirst();
        assertEquals(5, telling.piecesIn());
        assertEquals(5, telling.piecesLeft());
        assertEquals(StockBatch.CostState.CARD, telling.costState());
        assertEquals(new BigDecimal("24.0000"), telling.unitCostEur());
        assertNull(telling.unitCostExclEnrosedEur());
        assertEquals("Telling", telling.note());
        assertEquals(LocalDate.now(StockBatchService.BRUSSELS), telling.receivedOn());
        assertEquals(List.of("COUNT_IN", "COUNT_IN"),
                batches.moves(telling.id(), 10).stream().map(StockBatchMove::kind).toList());
        assertEquals(0, batches.totals(product).differencePieces());
    }

    /* ------------------------------------------------------------------ (8) */

    @Test
    @TestTransaction
    void transfersDamageOnArrivalAndDeletedMovementsLeaveBatchesAlone() {
        long main = main();
        long stand = location();
        long product = product(1, "2");
        start();
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(product, 1, "PO-A", false, JAN, cost("2")));
        int before = movesOf(product).size();
        stock.transfer(product, main, stand, 4, "Naar de stand");
        stock.noteDamagedOnArrival(product, main, 2, "PO-A");
        Long receiptMovement = batches.moves(byTitle(product, "PO-A").id(), 1).getFirst().stockMovementId();
        products.deleteStockMovement(product, receiptMovement);
        assertEquals(before, movesOf(product).size(), "no batch move for any of the three");
        StockBatch batch = byTitle(product, "PO-A");
        assertEquals(10, batch.piecesLeft());
        assertEquals(10, batch.piecesIn());
        assertEquals(0, batches.totals(product).differencePieces(), "transfers change nothing company-wide");
    }

    /* ------------------------------------------------------------------ (9) */

    @Test
    @TestTransaction
    void applyCostConvertsToTheBatchUnitAndRefreshOnlyTouchesCalculatedBatches() {
        long main = main();
        long display = product(12, "96");
        start();
        stock.add(display, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-1",
                receipt(display, 1, "PO-1", false, JAN, cost("8")));
        stock.sell(display, main, 1, "F-1");
        StockBatch batch = byTitle(display, "PO-1");
        StockBatchMove sold = batches.moves(batch.id(), 1).getFirst();
        assertEquals(new BigDecimal("8.0000"), sold.unitCostEur());

        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        BatchCost perSix = new BatchCost(StockBatch.CostState.APPLIED, new BigDecimal("3.0000"),
                new BigDecimal("2.5000"), new ProductCostBreakdown(new BigDecimal("1.5000"), null, null, null, null,
                new BigDecimal("0.5000"), null), now, "emre");
        StockBatchService.AppliedBatchCost applied = batches.applyCost(base + 1, display, perSix, 6);
        assertEquals(1, applied.batches());
        assertEquals(108, applied.piecesLeft());
        StockBatch converted = byTitle(display, "PO-1");
        assertEquals(StockBatch.CostState.APPLIED, converted.costState());
        assertEquals(new BigDecimal("6.0000"), converted.unitCostEur(), "3,00 per 6 pieces = 6,00 per 12");
        assertEquals(new BigDecimal("5.0000"), converted.unitCostExclEnrosedEur());
        assertEquals(new BigDecimal("3.0000"), converted.parts().goodsEur());
        assertEquals(new BigDecimal("1.0000"), converted.parts().enrosedEur());
        assertNull(converted.parts().freightEur());
        assertEquals(now, converted.costAppliedAt());
        assertEquals("emre", converted.costAppliedBy());
        assertEquals(new BigDecimal("8.0000"), batches.moves(batch.id(), 1).getFirst().unitCostEur(),
                "pieces that left keep the cost on their move");

        stock.add(display, main, 5, StockMovement.Kind.PURCHASE_RECEIPT, "PO-2",
                receipt(display, 2, "PO-2", false, FEB, cost("8")));
        stock.add(display, main, 5, StockMovement.Kind.PURCHASE_RECEIPT, "PO-3",
                receipt(display, 3, "PO-3", false, MAR,
                        new BatchCost(StockBatch.CostState.APPLIED, new BigDecimal("7"), null, null, now, "emre")));
        assertEquals(List.of("PO-2"), batches.calculatedLive(display).stream()
                .map(StockBatch::orderNumber).toList(), "only PO-2 is calculated and live");
        BatchCost nine = new BatchCost(StockBatch.CostState.CALCULATED, new BigDecimal("9.0000"), null, null,
                null, null);
        assertEquals(1, batches.refreshCalculated(base + 2, display, nine, 12).batches());
        assertEquals(0, batches.refreshCalculated(base + 3, display, nine, 12).batches());
        assertEquals(0, batches.refreshCalculated(base + 2, display, BatchCost.NONE, 12).batches());
        StockBatch refreshed = byTitle(display, "PO-2");
        assertEquals(StockBatch.CostState.CALCULATED, refreshed.costState());
        assertEquals(new BigDecimal("9.0000"), refreshed.unitCostEur());
        assertNull(refreshed.costAppliedAt());
        StockBatch untouched = byTitle(display, "PO-3");
        assertEquals(StockBatch.CostState.APPLIED, untouched.costState());
        assertEquals(0, new BigDecimal("7").compareTo(untouched.unitCostEur()));
        assertEquals(0, batches.applyCost(base + 2, display, BatchCost.NONE, 12).batches(),
                "a line without a price never wipes a cost");
        assertEquals(new BigDecimal("9.0000"), byTitle(display, "PO-2").unitCostEur());
    }

    /* ----------------------------------------------------------------- (10) */

    @Test
    @TestTransaction
    void aReceiptCorrectionDownLowersItsBatchAndTheOverflowIsALossElsewhere() {
        long main = main();
        long product = product(1, "2");
        start();
        BatchSource a = source(product, 1, "PO-A", false, JAN, cost("2"));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A", StockBatchContext.receipt(a));
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-B",
                receipt(product, 2, "PO-B", false, FEB, cost("3")));
        stock.sell(product, main, 8, "F-1");
        stock.add(product, main, -5, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A correctie",
                StockBatchContext.receipt(a));

        StockBatch first = byTitle(product, "PO-A");
        assertEquals(8, first.piecesIn(), "its own correction lowers pieces_in");
        assertEquals(0, first.piecesLeft());
        assertEquals(8, first.soldPieces());
        assertEquals(0, first.lostPieces());
        assertEquals(0, first.otherPieces());
        StockBatch second = byTitle(product, "PO-B");
        assertEquals(10, second.piecesIn(), "the overflow leaves pieces_in of the other batch alone");
        assertEquals(7, second.piecesLeft());
        assertEquals(3, second.lostPieces());
        StockBatchMove overflow = batches.moves(second.id(), 1).getFirst();
        assertEquals("RECEIPT_CORRECTION", overflow.kind());
        assertEquals(base + 1, overflow.purchaseOrderId(), "it names the corrected container");

        stock.add(product, main, 4, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A correctie 2",
                StockBatchContext.receipt(a));
        assertEquals(12, byTitle(product, "PO-A").piecesIn(), "a correction up adds to the batch");
        assertEquals(4, byTitle(product, "PO-A").piecesLeft());
        assertEquals(0, batches.totals(product).differencePieces());
    }

    /* ----------------------------------------------------------------- (11) */

    @Test
    @TestTransaction
    void saleCostUsesTheIncludedFigureWhereABatchHasNoFigureWithoutEnrosedKost() {
        long main = main();
        long display = product(12, "24");
        stock.setLevel(display, main, 10, StockMovement.Kind.STOCKTAKE, "Opening count");
        start();
        batches.writeBackfill(display, List.of(new StockBatchService.BackfillBatch(StockBatch.Kind.OPENING,
                StockBatch.Owner.OWN, null, null, null, null, null, null, null, 12, 120, 120,
                new BatchCost(StockBatch.CostState.CARD, new BigDecimal("24.0000"), null, null, null, null),
                "Beginvoorraad, kost van de productkaart")), 0, null, Instant.now());
        stock.add(display, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-A",
                receipt(display, 1, "PO-A", false, JAN, new BatchCost(StockBatch.CostState.CALCULATED,
                        new BigDecimal("36.0000"), new BigDecimal("30.0000"), null, null, null)));
        stock.sell(display, main, 15, "F-1", StockBatchContext.sale(base + 555, null));

        StockBatchSaleCost cost = batches.saleCost(base + 555).get(display);
        assertEquals(180, cost.pieces());
        assertEquals(0, cost.piecesWithoutCost());
        /* 120 × 24/12 + 60 × 36/12 = 240 + 180; without Enrosed kost 240 + 60 × 30/12 = 390 */
        assertEquals(0, new BigDecimal("420").compareTo(cost.valueEur()));
        assertEquals(0, new BigDecimal("390").compareTo(cost.valueExclEnrosedEur()));

        stock.sell(display, main, 10, "F-2", StockBatchContext.sale(base + 556, null));
        StockBatchSaleCost over = batches.saleCost(base + 556).get(display);
        assertEquals(120, over.pieces());
        assertEquals(60, over.piecesWithoutCost(), "the pieces no batch could give");
        assertEquals(0, new BigDecimal("180").compareTo(over.valueEur()));
        assertEquals(0, new BigDecimal("150").compareTo(over.valueExclEnrosedEur()));
        assertTrue(batches.saleCost(base + 557).isEmpty());
    }

    /* ----------------------------------------------------------------- (12) */

    @Test
    @TestTransaction
    void checkIsEmptyAfterAMixedFlowAndRepairBooksTheDriftUnlessPaused() {
        long main = main();
        long one = product(1, "2");
        long two = product(12, "24");
        start();
        stock.add(one, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-1",
                receipt(one, 1, "PO-1", false, JAN, cost("2")));
        stock.add(two, main, 4, StockMovement.Kind.PURCHASE_RECEIPT, "PO-2",
                receipt(two, 2, "PO-2", false, JAN, cost("24")));
        stock.sell(one, main, 3, "F-1");
        stock.setLevel(two, main, 3, 5, StockMovement.Kind.STOCKTAKE, "Telling");
        stock.takeOut(one, main, 1, StockMovement.Kind.DEMO, "Demo");
        stock.add(one, main, 2, StockMovement.Kind.SALE_RETURN, "C-1");
        assertNull(checkRow(one), "no difference after a mixed flow");
        assertNull(checkRow(two));

        batches.pause();
        stock.sell(one, main, 2, "F-PAUSED");
        stock.add(two, main, 1, StockMovement.Kind.PURCHASE_RECEIPT, "PO-LATE");
        assertEquals(2, checkRow(one).differencePieces());
        assertEquals(-12, checkRow(two).differencePieces());
        BusinessRuleException paused = assertThrows(BusinessRuleException.class, () -> batches.repair(one));
        assertEquals("Batches staan op pauze.", paused.getMessage());

        batches.resume();
        StockBatchTotals repairedOne = batches.repair(one);
        StockBatchTotals repairedTwo = batches.repair(two);
        assertEquals(0, repairedOne.differencePieces());
        assertEquals(0, repairedTwo.differencePieces());
        assertNull(checkRow(one));
        assertNull(checkRow(two));
        assertEquals("REPAIR", batches.moves(byTitle(one, "PO-1").id(), 1).getFirst().kind());
        StockBatch found = byTitle(two, "Telling");
        assertEquals(12, found.piecesLeft());
        assertEquals("Herstel verschil", found.note());
    }

    /* ----------------------------------------------------------------- (14) */

    @Test
    @TestTransaction
    void aProductCreatedWithAStockFigureStartsWithATellingBatchAndNoDifference() {
        StockLocation main = stock.mainLocation();
        ProductEntity product = entity(12, "24");
        product.inventoryKnown = true;
        product.stockQuantity = 7;
        entities.persist(product);
        entities.flush();
        start();
        locationBackfill.seedMissingLevels(main);

        assertEquals(7, stock.quantityAt(product.id, main.id()));
        List<StockBatch> list = batches.forProduct(product.id);
        assertEquals(1, list.size());
        StockBatch created = list.getFirst();
        assertEquals(StockBatch.Kind.COUNT, created.kind());
        assertEquals("Voorraad bij aanmaak", created.note());
        assertEquals(84, created.piecesLeft(), "7 displays of 12");
        assertEquals(new BigDecimal("24.0000"), created.unitCostEur());
        assertEquals(0, batches.totals(product.id).differencePieces());
        assertNull(checkRow(product.id));
    }

    /* ----------------------------------------------------------------- (15) */

    @Test
    @TestTransaction
    void pauseStopsTheHooksResumeRestartsThemAndResetEmptiesTheThreeTables() {
        long main = main();
        long product = product(1, "2");
        assertEquals("Batches zijn nog niet gestart.",
                assertThrows(BusinessRuleException.class, () -> batches.pause()).getMessage());
        assertEquals("Batches zijn nog niet gestart.",
                assertThrows(BusinessRuleException.class, () -> batches.repair(product)).getMessage());
        start();
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-1",
                receipt(product, 1, "PO-1", false, JAN, cost("2")));

        assertTrue(batches.pause().paused());
        assertTrue(batches.pause().paused(), "pausing twice is fine");
        assertTrue(batches.startRun().orElseThrow().paused());
        stock.sell(product, main, 2, "F-PAUSED");
        assertEquals(10, byTitle(product, "PO-1").piecesLeft());

        assertFalse(batches.resume().paused());
        stock.sell(product, main, 1, "F-ACTIVE");
        assertEquals(9, byTitle(product, "PO-1").piecesLeft());

        batches.reset();
        assertFalse(batches.started());
        assertEquals(0L, count(StockBatchMoveEntity.class));
        assertEquals(0L, count(StockBatchEntity.class));
        assertEquals(0L, count(StockBatchRunEntity.class));
        stock.sell(product, main, 1, "F-AFTER-RESET");
        assertTrue(batches.forProduct(product).isEmpty(), "after the reset the hooks are off again");
    }

    /* ---------------------------------------------------- TICA replay (extra) */

    /**
     * The same TICA sale and revision booked twice through applyTica: the stock book refuses the second line (its
     * unique key on revision, product and location) and the batch take goes back with it, so there is no second take.
     * Runs in committed transactions (the refused booking rolls back on its own) and removes everything afterwards.
     */
    @Test
    void aReplayedTicaBookingMakesNoSecondBatchTake() {
        long main = QuarkusTransaction.requiringNew().call(this::main);
        long product = QuarkusTransaction.requiringNew().call(() -> product(1, "2"));
        Long run = null;
        try {
            run = QuarkusTransaction.requiringNew().call(() -> {
                long id = batches.recordStart(0, 0, 0, 0, "replay test").id();
                stock.add(product, main, 20, StockMovement.Kind.PURCHASE_RECEIPT, "PO-T",
                        receipt(product, 1, "PO-T", false, JAN, cost("2")));
                return id;
            });
            long sale = base + 70;
            long revision = base + 71;
            tica(product, main, -5, sale, revision, "TICA 9/1 · verkoop/retour");
            assertThrows(RuntimeException.class,
                    () -> tica(product, main, -5, sale, revision, "TICA 9/1 · verkoop/retour"),
                    "the stock book holds one line per revision, product and location");

            QuarkusTransaction.requiringNew().run(() -> {
                assertEquals(15, stock.quantityAt(product, main), "booked once");
                List<StockBatchMoveEntity> takes = entities.createQuery(
                        "select m from StockBatchMoveEntity m where m.productId = :p and m.ticaSaleId = :s",
                        StockBatchMoveEntity.class).setParameter("p", product).setParameter("s", sale).getResultList();
                assertEquals(1, takes.size(), "one batch take");
                assertEquals(-5, takes.getFirst().pieces);
                assertEquals(15, batches.forProduct(product).getFirst().piecesLeft());
                assertEquals(0, batches.totals(product).differencePieces());
            });
        } finally {
            Long runId = run;
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createQuery("delete from StockBatchMoveEntity m where m.productId = :p")
                        .setParameter("p", product).executeUpdate();
                entities.createQuery("delete from StockBatchEntity b where b.productId = :p")
                        .setParameter("p", product).executeUpdate();
                if (runId != null) {
                    entities.createQuery("delete from StockBatchRunEntity r where r.id = :id")
                            .setParameter("id", runId).executeUpdate();
                }
                entities.createQuery("delete from StockMovementEntity m where m.productId = :p")
                        .setParameter("p", product).executeUpdate();
                entities.createQuery("delete from StockLevelEntity l where l.productId = :p")
                        .setParameter("p", product).executeUpdate();
                ProductEntity row = entities.find(ProductEntity.class, product);
                if (row != null) entities.remove(row);
            });
        }
        assertFalse(QuarkusTransaction.requiringNew().call(() -> batches.started()), "no start row is left behind");
    }

    /* -------------------------------------------------------------- helpers */

    private void start() {
        batches.recordStart(0, 0, 0, 0, "test");
    }

    private long main() {
        return stock.mainLocation().id();
    }

    private long location() {
        StockLocationEntity location = new StockLocationEntity();
        location.code = "BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        location.name = "Stand " + location.code;
        location.kind = StockLocation.Kind.SALES_POINT;
        location.active = true;
        location.publicPickupPoint = false;
        location.publicPickupPosition = 0;
        location.position = 50;
        entities.persist(location);
        entities.flush();
        return location.id;
    }

    private ProductEntity entity(int unitPieces, String cardCost) {
        ProductEntity product = new ProductEntity();
        product.sku = "BATCH-" + UUID.randomUUID();
        product.name = "Batch test product";
        product.active = true;
        product.inventoryKnown = false;
        product.stockQuantity = 0;
        product.landedCostEur = new BigDecimal(cardCost);
        if (unitPieces > 1) {
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
            product.packagingPiecesPerUnit = unitPieces;
        }
        return product;
    }

    private long product(int unitPieces, String cardCost) {
        ProductEntity product = entity(unitPieces, cardCost);
        entities.persist(product);
        entities.flush();
        return product.id;
    }

    private static BatchCost cost(String unit) {
        return new BatchCost(StockBatch.CostState.CALCULATED, new BigDecimal(unit).setScale(4), null, null, null, null);
    }

    /** Container {@code base + n}, line {@code base + n·10 + productId % 10}; the number is the batch title. */
    private BatchSource source(long productId, int n, String number, boolean partner, LocalDate receivedOn,
                               BatchCost cost) {
        return new BatchSource(base + n, base + n * 10L + productId % 10, number, 5L, null, partner, receivedOn, cost);
    }

    private StockBatchContext receipt(long productId, int n, String number, boolean partner, LocalDate receivedOn,
                                      BatchCost cost) {
        return StockBatchContext.receipt(source(productId, n, number, partner, receivedOn, cost));
    }

    private void tica(long productId, long locationId, int delta, long sale, long revision, String reference) {
        stock.applyTica(productId, locationId, delta, sale, revision, LocalDateTime.of(2026, 10, 25, 11, 0),
                reference, Instant.now(), BigDecimal.valueOf(Math.abs(delta)), "PIECE");
    }

    private static String title(StockBatch batch) {
        return switch (batch.kind()) {
            case OPENING -> "Beginvoorraad";
            case COUNT -> "Telling";
            case RECEIPT -> batch.orderNumber();
        };
    }

    private static List<String> titles(List<StockBatch> list) {
        return list.stream().map(StockBatchServiceTest::title).toList();
    }

    private StockBatch byTitle(long productId, String title) {
        return batches.forProduct(productId).stream().filter(batch -> title.equals(title(batch))).findFirst()
                .orElseThrow(() -> new AssertionError("no batch " + title));
    }

    private List<Integer> lefts(long productId, String... titles) {
        return java.util.Arrays.stream(titles).map(title -> byTitle(productId, title).piecesLeft()).toList();
    }

    private List<StockBatchMoveEntity> movesOf(long productId) {
        return entities.createQuery("select m from StockBatchMoveEntity m where m.productId = :p",
                StockBatchMoveEntity.class).setParameter("p", productId).getResultList();
    }

    private StockBatchTotals checkRow(long productId) {
        return batches.check().stream().filter(row -> row.productId() == productId).findFirst().orElse(null);
    }

    private long count(Class<?> entity) {
        return entities.createQuery("select count(e) from " + entity.getSimpleName() + " e", Long.class)
                .getSingleResult();
    }
}
