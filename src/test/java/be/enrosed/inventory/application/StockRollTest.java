package be.enrosed.inventory.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.catalog.domain.StockMovement.Kind;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;
import be.enrosed.inventory.application.StockRoll.BookedLine;
import be.enrosed.inventory.application.StockRoll.Flip;
import be.enrosed.inventory.application.StockRoll.Location;
import be.enrosed.inventory.application.StockRoll.Named;
import be.enrosed.inventory.application.StockRoll.Position;
import be.enrosed.inventory.application.StockRoll.Receipt;
import be.enrosed.inventory.application.StockRoll.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rolling a booked count to the closing date: which lines of the stock book
 * are listed, what each one counts for by default, and what the closing
 * quantity becomes in both directions.
 */
class StockRollTest {

    private static final LocalDate CLOSING = LocalDate.of(2026, 12, 31);
    private static final Instant CUTOFF = InventoryClock.cutoffAt(CLOSING);
    private static final long ROSE = 11, TULIP = 12, WAREHOUSE = 1;
    private static final long BASE = 40, CORRECTION = 41;
    private static final String BASE_REF = "Jaartelling 2026 #40 ·";
    private static final String CORRECTION_REF = "Jaartelling 2026 #41 ·";

    private final List<StockMovement> book = new ArrayList<>();
    private final Map<Long, Flip> decisions = new HashMap<>();
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<String, LocalDate> invoices = new HashMap<>();
    private final Map<String, Integer> before = new HashMap<>();
    private List<StockClosingMovementEntity> previous = List.of();
    private Set<Long> stillInBook = new HashSet<>();

    /* ------------------------------------------------------- count after */

    @Test
    void aCountAfterTheClosingDateTakesTheMovementsSinceThatDateBackOut() {
        /* Section 3.8: counted 1.210 on 02/01 10:15; a sale of 40 was booked that morning, another on 05/01. */
        Instant counted = at(2027, 1, 2, 10, 15);
        before.put(ROSE + ":" + WAREHOUSE, 1250);
        move(1, ROSE, at(2027, 1, 2, 9, 2), -40, 1210, Kind.SALE, "F-2027-001");
        move(2, ROSE, counted.minusMillis(5), 0, 1210, Kind.STOCKTAKE, BASE_REF + " geen verschil");
        move(3, ROSE, at(2027, 1, 5, 14, 0), -40, 1170, Kind.SALE, "F-2027-009");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 1210, counted));

        Position position = result.position(ROSE, WAREHOUSE);
        assertEquals(StockRoll.ANCHOR_COUNT, position.anchor());
        assertTrue(position.countAfterClosingDate());
        assertEquals(1210, position.anchorQuantity());
        assertEquals(40, position.rollDelta());
        assertEquals(1250, position.closingQuantity(), "1.210 + the 40 that were sold after the closing date");
        assertEquals(BASE, position.countId());
        assertEquals(400L, position.countLineId());
        assertEquals(counted, position.anchoredAt());

        assertNull(result.row(2), "the row the count itself wrote is not listed");
        var morning = result.row(1);
        assertTrue(morning.between());
        assertTrue(morning.row().defaultApplied);
        assertTrue(morning.row().applied);
        assertEquals(-40, morning.row().effectiveDelta);
        assertFalse(morning.row().noAnchor);
        assertEquals("Verkocht", morning.row().kindLabel);
        assertEquals("F-2027-001", morning.row().refText);
        assertEquals("Roos in stolp rood", morning.row().productName);
        var later = result.row(3);
        assertFalse(later.between(), "booked after the count: the pieces were still on the shelf when it was counted");
        assertFalse(later.row().defaultApplied);
        assertFalse(later.row().applied);

        /* Ticked, the later sale is taken out as well: the formula, not the advice. */
        decisions.put(3L, new Flip(true, "Was op 02/01 al opgehaald"));
        Result ticked = roll(counted, new BookedLine(BASE, 400, ROSE, 1210, counted));
        assertEquals(1290, ticked.position(ROSE, WAREHOUSE).closingQuantity());
        assertTrue(ticked.row(3).row().applied);
        assertFalse(ticked.row(3).row().defaultApplied);
        assertEquals("Was op 02/01 al opgehaald", ticked.row(3).row().appliedReason);

        /* Unticked, the morning sale had left before the closing date. */
        decisions.clear();
        decisions.put(1L, new Flip(false, "Op 30/12 meegegeven"));
        assertEquals(1210, roll(counted, new BookedLine(BASE, 400, ROSE, 1210, counted)).position(ROSE, WAREHOUSE).closingQuantity());
    }

    @Test
    void theAnchorIsPerProductAndACorrectionReplacesTheCountOfItsProductOnly() {
        Instant roseCounted = at(2027, 1, 2, 10, 0);
        Instant tulipCounted = at(2027, 1, 2, 10, 1);
        Instant sessionBooked = at(2027, 1, 2, 10, 2);
        Instant corrected = at(2027, 1, 4, 16, 0);
        before.put(ROSE + ":" + WAREHOUSE, 100);
        before.put(TULIP + ":" + WAREHOUSE, 60);
        move(1, ROSE, roseCounted.minusMillis(3), -2, 98, Kind.STOCKTAKE, BASE_REF + " Niet gevonden");
        move(2, TULIP, tulipCounted.minusMillis(3), 0, 60, Kind.STOCKTAKE, BASE_REF + " geen verschil");
        /* Sold while the other shelves were still being booked: after the rose's own moment, before the session's. */
        move(3, ROSE, at(2027, 1, 2, 10, 1), -5, 93, Kind.SALE, "F-2027-002");
        move(4, ROSE, at(2027, 1, 3, 11, 0), -3, 90, Kind.SALE, "F-2027-003");
        move(5, ROSE, corrected.minusMillis(3), 4, 94, Kind.STOCKTAKE, CORRECTION_REF + " Teruggevonden");

        Location location = new Location(WAREHOUSE, "Magazijn", StockRoll.ANCHOR_COUNT, BASE,
                Map.of(BASE, BASE_REF, CORRECTION, CORRECTION_REF),
                List.of(new BookedLine(BASE, 400, ROSE, 98, roseCounted), new BookedLine(BASE, 401, TULIP, 60, tulipCounted),
                        new BookedLine(CORRECTION, 410, ROSE, 94, corrected)), null, sessionBooked);
        Result result = roll(location);

        Position rose = result.position(ROSE, WAREHOUSE);
        assertEquals(94, rose.anchorQuantity(), "the corrected figure");
        assertEquals(corrected, rose.anchoredAt());
        assertEquals(410L, rose.countLineId());
        assertEquals(BASE, rose.countId(), "the base session of the location, also for a corrected product");
        Position tulip = result.position(TULIP, WAREHOUSE);
        assertEquals(60, tulip.anchorQuantity());
        assertEquals(tulipCounted, tulip.anchoredAt(), "its own line, not the moment the session was booked");
        assertEquals(60, tulip.closingQuantity());

        assertNull(result.row(5), "the correction's own row");
        assertNull(result.row(2));
        var replaced = result.row(1);
        assertNotNull(replaced, "the count the correction replaced is listed");
        assertFalse(replaced.row().defaultApplied);
        assertEquals(StockRoll.NOTE_REPLACED_COUNT, replaced.row().defaultNote);
        assertFalse(replaced.row().review);
        assertTrue(result.row(3).row().applied);
        assertTrue(result.row(4).row().applied);
        assertEquals(94 + 5 + 3, rose.closingQuantity());

        /* Without the correction the rose is anchored on its own line of 10:00: the sale of 10:01 came after it. */
        book.removeIf(movement -> movement.id() == 5L);
        Result plain = roll(new Location(WAREHOUSE, "Magazijn", StockRoll.ANCHOR_COUNT, BASE, Map.of(BASE, BASE_REF),
                List.of(new BookedLine(BASE, 400, ROSE, 98, roseCounted), new BookedLine(BASE, 401, TULIP, 60, tulipCounted)),
                null, sessionBooked));
        assertFalse(plain.row(3).between());
        assertFalse(plain.row(3).row().applied);
        assertEquals(98, plain.position(ROSE, WAREHOUSE).closingQuantity(), "not taken out of the counted 98");
    }

    @Test
    void aCorrectionOfTheLevelAfterTheClosingDateIsNotAMovementAndAMemoRowCountsNothing() {
        Instant counted = at(2027, 1, 6, 9, 0);
        before.put(ROSE + ":" + WAREHOUSE, 200);
        move(1, ROSE, at(2027, 1, 2, 9, 0), 15, 215, Kind.MANUAL_CORRECTION, "nagekeken");
        /* Damage noted at arrival: a negative delta and an unchanged level. */
        move(2, ROSE, at(2027, 1, 3, 9, 0), -6, 215, Kind.DAMAGED, "PO-2026-030");
        move(3, ROSE, at(2027, 1, 4, 9, 0), 0, 215, Kind.STOCKTAKE, "Jaartelling 2026 #77 · geen verschil");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 215, counted));

        var correction = result.row(1);
        assertFalse(correction.row().defaultApplied);
        assertEquals(StockRoll.NOTE_CORRECTION, correction.row().defaultNote);
        assertTrue(correction.row().review);
        var memo = result.row(2);
        assertEquals(-6, memo.row().delta);
        assertEquals(0, memo.row().effectiveDelta, "geen voorraadwijziging");
        assertTrue(memo.row().applied);
        var otherCount = result.row(3);
        assertFalse(otherCount.row().defaultApplied, "a count of another session");
        assertTrue(otherCount.row().review);
        assertEquals(215, result.position(ROSE, WAREHOUSE).closingQuantity());
    }

    @Test
    void aReceiptBookedAfterTheClosingDateForAContainerThatLayThereIsNotTakenOut() {
        Instant counted = at(2027, 1, 6, 9, 0);
        before.put(ROSE + ":" + WAREHOUSE, 100);
        receipts.put("PO-2026-020", new Receipt(20, LocalDate.of(2026, 12, 28)));
        receipts.put("PO-2027-001", new Receipt(21, LocalDate.of(2027, 1, 4)));
        receipts.put("PO-2026-021 correctie", new Receipt(22, LocalDate.of(2026, 12, 20)));
        move(1, ROSE, at(2027, 1, 3, 9, 0), 500, 600, Kind.PURCHASE_RECEIPT, "PO-2026-020");
        move(2, ROSE, at(2027, 1, 4, 15, 0), 300, 900, Kind.PURCHASE_RECEIPT, "PO-2027-001");
        move(3, ROSE, at(2027, 1, 8, 15, 0), 12, 912, Kind.PURCHASE_RECEIPT, "PO-2026-021 correctie");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 900, counted));

        var old = result.row(1);
        assertEquals(LocalDate.of(2026, 12, 28), old.row().businessDate);
        assertEquals(StockRoll.SOURCE_RECEIPT, old.row().businessDateSource);
        assertFalse(old.row().defaultApplied);
        assertEquals("Ontvangen op 28/12/2026, bijgeboekt op 03/01/2027: lag er al op de afsluitdatum", old.row().defaultNote);
        assertEquals(20L, old.purchaseOrderId());
        assertFalse(old.receivedBeforeCount());
        var january = result.row(2);
        assertTrue(january.row().defaultApplied, "received after the closing date");
        assertNull(january.row().defaultNote);
        var late = result.row(3);
        assertFalse(late.row().defaultApplied);
        assertTrue(late.receivedBeforeCount(), "booked after the count for pieces that had arrived before it");
        assertEquals(900 - 300, result.position(ROSE, WAREHOUSE).closingQuantity());
    }

    /* ------------------------------------------------------ count before */

    @Test
    void aCountBeforeTheClosingDateAddsTheMovementsUpToThatDate() {
        Instant counted = at(2026, 12, 28, 17, 0);
        before.put(ROSE + ":" + WAREHOUSE, 500);
        receipts.put("PO-2026-040", new Receipt(40, LocalDate.of(2026, 12, 30)));
        move(1, ROSE, counted.minusMillis(4), -3, 497, Kind.STOCKTAKE, BASE_REF + " Beschadigd of stuk");
        move(2, ROSE, at(2026, 12, 29, 10, 0), -20, 477, Kind.SALE, "F-2026-140");
        move(3, ROSE, at(2026, 12, 30, 10, 0), 4, 481, Kind.MANUAL_CORRECTION, "doos teruggevonden");
        move(4, ROSE, at(2027, 1, 3, 10, 0), -40, 441, Kind.SALE, "F-2027-004");
        move(5, ROSE, at(2027, 1, 4, 10, 0), 250, 691, Kind.PURCHASE_RECEIPT, "PO-2026-040");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 497, counted));

        Position position = result.position(ROSE, WAREHOUSE);
        assertFalse(position.countAfterClosingDate());
        assertNull(result.row(1));
        assertTrue(result.row(2).between());
        assertTrue(result.row(2).row().applied);
        var correction = result.row(3);
        assertTrue(correction.row().defaultApplied, "before the closing date a correction of the level is in the closing quantity");
        assertFalse(correction.row().review);
        var january = result.row(4);
        assertFalse(january.between());
        assertFalse(january.row().defaultApplied, "an ordinary sale of January");
        var receipt = result.row(5);
        assertTrue(receipt.row().defaultApplied);
        assertEquals("Ontvangen op 30/12/2026, bijgeboekt na de afsluitdatum", receipt.row().defaultNote);
        assertFalse(receipt.receivedBeforeCount());
        assertEquals(497 - 20 + 4 + 250, position.closingQuantity());
        assertEquals(234, position.rollDelta());

        /* Ticked, the January sale lowers the closing quantity by its amount. */
        decisions.put(4L, new Flip(true, "Op 30/12 meegegeven, factuur later"));
        assertEquals(731 - 40, roll(counted, new BookedLine(BASE, 400, ROSE, 497, counted)).position(ROSE, WAREHOUSE).closingQuantity());
    }

    @Test
    void aSaleInvoicedOnTheOtherSideOfTheClosingDateIsFlaggedAndNothingIsAppliedForIt() {
        Instant counted = at(2027, 1, 6, 9, 0);
        before.put(ROSE + ":" + WAREHOUSE, 100);
        invoices.put("F-2026-118", LocalDate.of(2026, 12, 20));
        invoices.put("F-2027-001", LocalDate.of(2027, 1, 2));
        move(1, ROSE, at(2027, 1, 2, 9, 0), -30, 70, Kind.SALE, "F-2026-118");
        move(2, ROSE, at(2027, 1, 3, 9, 0), -10, 60, Kind.SALE, "F-2027-001");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 60, counted));

        assertEquals(LocalDate.of(2026, 12, 20), result.row(1).row().businessDate);
        assertEquals(StockRoll.SOURCE_INVOICE, result.row(1).row().businessDateSource);
        assertTrue(result.row(1).row().review, "invoiced in December, booked in January");
        assertTrue(result.row(1).row().applied, "the default stays the booking time");
        assertFalse(result.row(2).row().review);
        assertEquals(100, result.position(ROSE, WAREHOUSE).closingQuantity());
    }

    /* ------------------------------------------------------------ other */

    @Test
    void aRowWithoutAPredecessorIsFlaggedAndCountsItsOwnDelta() {
        Instant counted = at(2027, 1, 6, 9, 0);
        move(1, ROSE, at(2027, 1, 2, 9, 0), -7, 43, Kind.SALE, "F-2027-001");
        move(2, ROSE, at(2027, 1, 3, 9, 0), -3, 40, Kind.SALE, "F-2027-002");

        Result result = roll(counted, new BookedLine(BASE, 400, ROSE, 40, counted));

        assertTrue(result.row(1).row().noAnchor);
        assertEquals(-7, result.row(1).row().effectiveDelta);
        assertFalse(result.row(2).row().noAnchor);
        assertEquals(50, result.position(ROSE, WAREHOUSE).closingQuantity());
    }

    @Test
    void aLocationWithoutACountIsRolledFromTheLevelOfTheBook() {
        Instant read = at(2027, 1, 6, 9, 0);
        before.put(ROSE + ":" + WAREHOUSE, 80);
        move(1, ROSE, at(2027, 1, 2, 9, 0), -5, 75, Kind.SALE, "F-2027-001");

        Result result = roll(new Location(WAREHOUSE, "Magazijn", StockRoll.ANCHOR_BOOK, null, null, null,
                Map.of(ROSE, 75, TULIP, 0), read));

        Position position = result.position(ROSE, WAREHOUSE);
        assertEquals(StockRoll.ANCHOR_BOOK, position.anchor());
        assertNull(position.countId());
        assertNull(position.countLineId());
        assertEquals(80, position.closingQuantity());
        assertNull(result.position(TULIP, WAREHOUSE), "nothing there and nothing moved");
    }

    @Test
    void aVanishedRowStaysInTheListAndCountsForNothing() {
        Instant counted = at(2027, 1, 6, 9, 0);
        before.put(ROSE + ":" + WAREHOUSE, 100);
        move(1, ROSE, at(2027, 1, 2, 9, 0), -30, 70, Kind.SALE, "F-2027-001");
        move(2, ROSE, at(2027, 1, 3, 9, 0), -10, 60, Kind.SALE, "F-2027-002");
        BookedLine line = new BookedLine(BASE, 400, ROSE, 60, counted);
        Result first = roll(counted, line);
        assertEquals(100, first.position(ROSE, WAREHOUSE).closingQuantity());

        /* The first sale is struck from the stock book; the second now starts from the level before both. */
        book.removeIf(movement -> movement.id() == 1L);
        previous = first.rows().stream().map(StockRoll.Row::row).toList();
        stillInBook = Set.of(2L);
        Result second = roll(counted, line);

        var gone = second.row(1);
        assertTrue(gone.row().removed);
        assertFalse(gone.row().applied);
        assertEquals(-30, gone.row().effectiveDelta, "written again as it was");
        assertEquals("F-2027-001", gone.row().refText);
        assertEquals(-40, second.row(2).row().effectiveDelta);
        assertEquals(100, second.position(ROSE, WAREHOUSE).closingQuantity());

        for (int compute = 0; compute < 2; compute++) {
            previous = second.rows().stream().map(StockRoll.Row::row).toList();
            second = roll(counted, line);
            assertTrue(second.row(1).row().removed, "still listed after compute " + (compute + 2));
            assertFalse(second.row(1).row().applied);
            assertEquals(2, second.rows().size());
            assertEquals(100, second.position(ROSE, WAREHOUSE).closingQuantity());
        }

        /* A row that only fell out of the window is not a vanished row. */
        previous = second.rows().stream().map(StockRoll.Row::row).filter(row -> !row.removed).toList();
        book.clear();
        stillInBook = Set.of(2L);
        assertEquals(0, roll(counted, line).rows().size());
    }

    /* ---------------------------------------------------------- helpers */

    private Result roll(Instant counted, BookedLine... lines) {
        return roll(new Location(WAREHOUSE, "Magazijn", StockRoll.ANCHOR_COUNT, BASE, Map.of(BASE, BASE_REF),
                List.of(lines), null, counted));
    }

    private Result roll(Location location) {
        return StockRoll.roll(new StockRoll.Input(CLOSING, CUTOFF, List.of(location),
                Map.of(ROSE, new Named("ROOS-R", "Roos in stolp rood"), TULIP, new Named("TULP-W", "Tulp wit")),
                List.copyOf(book), (productId, locationId, moment) -> before.get(productId + ":" + locationId),
                receipts, invoices, decisions, previous, stillInBook));
    }

    private void move(long id, long productId, Instant at, int delta, int after, Kind kind, String reference) {
        book.add(new StockMovement(id, productId, WAREHOUSE, at, delta, after, kind, reference, "emre"));
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(InventoryClock.BRUSSELS).toInstant();
    }
}
