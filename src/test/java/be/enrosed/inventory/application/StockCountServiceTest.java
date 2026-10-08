package be.enrosed.inventory.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.adapter.out.persistence.StockMovementEntity;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.StockCountEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;
import be.enrosed.inventory.application.StockCountService.Check;
import be.enrosed.inventory.application.StockCountService.Line;
import be.enrosed.inventory.application.StockCountService.LineWrite;
import be.enrosed.inventory.application.StockCountService.OpenDocument;
import be.enrosed.inventory.application.StockCountService.OrphanLevel;
import be.enrosed.inventory.application.StockCountService.Session;
import be.enrosed.inventory.application.StockCountService.Summary;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The yearly count of a location: what a line remembers of the moment its
 * shelf was counted, what booking does to a level that moved in the meantime,
 * and how an invoice or a container that explains a difference keeps the same
 * pieces from being booked twice.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class StockCountServiceTest {
    private static final int YEAR = InventoryClock.today().getYear();

    @Inject StockCountService counts;
    @Inject StockService stock;
    @Inject ProductService products;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject EntityManager em;

    /* ------------------------------------------------------------------ start */

    @Test @TestTransaction
    void aFullCountListsWhatLiesThereHiddenProductsIncludedAndEveryActiveProduct() {
        StockLocation shelf = location("Start");
        long active = product("Actief met voorraad", true);
        long empty = product("Actief zonder voorraad", true);
        long hidden = product("Inactief met voorraad", false);
        long gone = product("Inactief zonder voorraad", false);
        long below = product("Inactief onder nul", false);
        level(active, shelf, 12);
        level(hidden, shelf, 3);
        level(below, shelf, 4);
        stock.sell(below, shelf.id(), 9, "F-TEST");

        Session session = counts.start(YEAR, shelf.id(), "  eerste ronde  ", null);

        StockCountEntity count = session.summary().count();
        assertEquals("OPEN", count.status);
        assertEquals(YEAR, count.countYear);
        assertEquals(shelf.name(), count.locationName);
        assertNull(count.correctsCountId);
        assertEquals("eerste ronde", count.note);
        assertEquals("Jaartelling " + YEAR + " #" + count.id + " ·", count.ledgerRef);
        assertEquals("emre", count.startedBy);
        assertNotNull(count.startedAt);
        assertNotNull(line(session, active));
        assertNotNull(line(session, empty));
        assertNotNull(line(session, hidden), "the stock page hides an inactive product, the count does not");
        assertNotNull(line(session, below), "a level below zero is on the list");
        assertNull(line(session, gone));
        assertEquals(-5, line(session, below).liveQuantity());

        Line listed = line(session, active);
        assertEquals("Actief met voorraad - rood", listed.row().productName);
        assertTrue(listed.row().sku.startsWith("COUNT-"));
        assertEquals("stuk", listed.row().unitKey);
        assertEquals("PIECE", listed.row().salesUnit);
        assertNull(listed.row().piecesPerUnit);
        assertFalse(listed.row().addedByHand);
        assertEquals(0, listed.row().revision);
        assertFalse(listed.row().documentsConfirmed);
        assertEquals(12, listed.liveQuantity());
        assertNull(listed.row().expectedQuantity, "expected is not stored at the start");
        assertFalse(listed.moved());
    }

    @Test @TestTransaction
    void aStartNeedsAYearAndALocationAndOnlyOneSessionRunsPerLocation() {
        StockLocation shelf = location("Dubbel");
        assertEquals("Kies een boekjaar", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.start(null, shelf.id(), null, null)).getMessage());
        assertEquals("Kies een locatie voor de telling", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.start(YEAR, null, null, null)).getMessage());
        assertEquals("Locatie 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> counts.start(YEAR, 987_654_321L, null, null)).getMessage());
        assertEquals("Telling 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> counts.view(987_654_321L)).getMessage());

        long running = counts.start(YEAR, shelf.id(), null, null).summary().count().id;

        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.start(YEAR - 1, shelf.id(), null, null));
        assertEquals("TELLING_LOOPT", refused.code());
        assertEquals("Voor " + shelf.name() + " loopt al een telling", refused.getMessage());
        assertEquals(Map.of("countId", running), refused.details());
        assertEquals(List.of(running), counts.openSessions(YEAR).stream().map(count -> count.id)
                .filter(id -> id == running).toList());

        counts.cancel(running);
        assertEquals("OPEN", counts.start(YEAR, shelf.id(), null, null).summary().count().status,
                "a cancelled session no longer holds the location");
    }

    /* --------------------------------------------------------------- counting */

    @Test @TestTransaction
    void expectedIsTheLiveLevelAtTheSaveOfACountAndAReasonOnlySaveLeavesTheCountAlone() {
        StockLocation shelf = location("Verkoop");
        long rose = product("Roos", true);
        level(rose, shelf, 100);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        stock.sell(rose, shelf.id(), 2, "F-VOOR");

        /* A count saves without a reason, so the number is never lost. */
        Line counted = save(count, rose, 95, null, null);
        assertEquals(98, counted.row().expectedQuantity, "the level at the save, not at the start");
        assertEquals(95, counted.row().countedQuantity);
        assertEquals(-3, counted.row().difference);
        assertNull(counted.row().reasonCode);
        assertEquals("emre", counted.row().countedBy);
        assertEquals(1, counted.row().revision);
        assertFalse(counted.moved());
        var countedAt = counted.row().countedAt;
        var expectedAt = counted.row().expectedAt;
        assertNotNull(countedAt);

        /* The other counter did this shelf; a sale is booked before the reason is chosen. */
        counted.row().countedBy = "berat";
        counted.row().countedByName = "Berat";
        em.flush();
        stock.sell(rose, shelf.id(), 10, "F-TUSSEN");

        Line reasoned = save(count, rose, 95, "NIET_GEVONDEN", "  achter het rek gezocht ");
        assertEquals(98, reasoned.row().expectedQuantity);
        assertEquals(expectedAt, reasoned.row().expectedAt);
        assertEquals(-3, reasoned.row().difference, "choosing the reason later never changes the difference");
        assertEquals("berat", reasoned.row().countedBy);
        assertEquals("Berat", reasoned.row().countedByName);
        assertEquals(countedAt, reasoned.row().countedAt);
        assertEquals("NIET_GEVONDEN", reasoned.row().reasonCode);
        assertEquals("achter het rek gezocht", reasoned.row().reasonNote);
        assertEquals(2, reasoned.row().revision);
        assertEquals(88, reasoned.liveQuantity());
        assertTrue(reasoned.moved());

        Check check = counts.bookingCheck(count);
        assertEquals(1, check.moved().size());
        Check.Moved moved = check.moved().getFirst();
        assertEquals(reasoned.row().id, moved.lineId());
        assertEquals(98, moved.expectedQuantity());
        assertEquals(95, moved.countedQuantity());
        assertEquals(-3, moved.difference());
        assertEquals(88, moved.liveQuantity());
        assertEquals(85, moved.resultQuantity());
        assertEquals(List.of("F-TUSSEN"), moved.movements().stream().map(StockMovement::reference).toList(),
                "only what was booked after the shelf was counted");
        assertEquals(-10, moved.movements().getFirst().delta());

        counts.book(count, check.checkToken());

        assertEquals(85, stock.quantityAt(rose, shelf.id()), "the sale survives: live plus the original difference");
        StockMovementEntity booked = stocktakes(count).getFirst();
        assertEquals(-3, booked.delta);
        assertEquals(85, booked.quantityAfter);
        assertEquals("Jaartelling " + YEAR + " #" + count + " · Niet gevonden", booked.reference);
        StockCountLineEntity row = line(counts.view(count), rose).row();
        assertEquals(88, row.liveAtBooking);
        assertEquals(-3, row.bookedDelta);
        assertEquals(85, row.bookedQuantity);
        assertNotNull(row.bookedAt);
    }

    @Test @TestTransaction
    void aRebaseTakesTheLiveLevelAsExpectedAndKeepsTheCountedFigure() {
        StockLocation shelf = location("Herreken");
        long rose = product("Roos", true);
        level(rose, shelf, 100);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        Line counted = save(count, rose, 70, "NIET_GEVONDEN", null);
        assertEquals(-30, counted.row().difference);
        long lineId = counted.row().id;
        counts.saveLine(count, lineId, new LineWrite(70, "NIET_GEVONDEN", null, 1, null, true));
        stock.sell(rose, shelf.id(), 30, "F-AFGEPUNT");

        Line rebased = counts.saveLine(count, lineId, new LineWrite(70, "NIET_GEVONDEN", null, 2, true, null));

        assertEquals(70, rebased.row().expectedQuantity);
        assertEquals(70, rebased.row().countedQuantity);
        assertEquals(0, rebased.row().difference);
        assertNull(rebased.row().reasonCode, "no difference, no reason");
        assertFalse(rebased.row().documentsConfirmed, "a new count asks the question again");
        assertFalse(rebased.moved());
        assertEquals(3, rebased.row().revision);
    }

    @Test @TestTransaction
    void aStaleRevisionIsRefusedWithTheLineAsItIsNow() {
        StockLocation shelf = location("Twee telefoons");
        long rose = product("Roos", true);
        level(rose, shelf, 20);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        long lineId = save(count, rose, 18, null, null).row().id;
        StockCountLineEntity stored = em.find(StockCountLineEntity.class, lineId);
        stored.countedByName = "Berat";
        stored.countedAt = LocalDate.of(YEAR, 3, 4).atTime(14, 5).atZone(InventoryClock.BRUSSELS).toInstant();
        em.flush();

        InventoryRefusal refused = assertThrows(InventoryRefusal.class,
                () -> counts.saveLine(count, lineId, new LineWrite(17, null, null, 0, null, null)));

        assertEquals("REGEL_GEWIJZIGD", refused.code());
        assertEquals("Berat telde hier al 18 (14:05)", refused.getMessage());
        Line current = (Line) refused.details().get("line");
        assertEquals(lineId, current.row().id);
        assertEquals(18, current.row().countedQuantity);
        assertEquals(1, current.row().revision);
        assertEquals(18, em.find(StockCountLineEntity.class, lineId).countedQuantity, "nothing was written");

        assertThrows(InventoryRefusal.class, () -> counts.saveLine(count, lineId, new LineWrite(17, null, null, null, null, null)));
        assertEquals(17, counts.saveLine(count, lineId, new LineWrite(17, null, null, 1, null, null)).row().countedQuantity);
    }

    @Test @TestTransaction
    void aLineWriteIsValidatedAndANullCountClearsTheLine() {
        StockLocation shelf = location("Invoer");
        long rose = product("Roos", true);
        level(rose, shelf, 20);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        long lineId = line(counts.view(count), rose).row().id;

        assertEquals("Geteld aantal kan niet negatief zijn", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.saveLine(count, lineId, new LineWrite(-1, null, null, 0, null, null))).getMessage());
        assertEquals("Onbekende reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.saveLine(count, lineId, new LineWrite(18, "VERKOCHT", null, 0, null, null))).getMessage());
        assertEquals("Vul bij 'Andere reden' een notitie in", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.saveLine(count, lineId, new LineWrite(18, "ANDERS", "  ", 0, null, null))).getMessage());
        assertEquals("Regel 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> counts.saveLine(count, 987_654_321L, new LineWrite(18, null, null, 0, null, null))).getMessage());
        assertEquals(0, em.find(StockCountLineEntity.class, lineId).revision);

        Line other = counts.saveLine(count, lineId, new LineWrite(18, "ANDERS", "doos open", 0, null, null));
        assertEquals("ANDERS", other.row().reasonCode);
        assertEquals("doos open", other.row().reasonNote);
        assertEquals("Vul bij 'Andere reden' een notitie in", assertThrows(UnprocessableBusinessRuleException.class,
                () -> counts.saveLine(count, lineId, new LineWrite(18, "ANDERS", null, 1, null, null))).getMessage());

        /* A count that equals the book has no difference to explain. */
        Line equal = counts.saveLine(count, lineId, new LineWrite(20, "ANDERS", null, 1, null, null));
        assertEquals(0, equal.row().difference);
        assertNull(equal.row().reasonCode);
        assertNull(equal.row().reasonNote);

        Line cleared = counts.saveLine(count, lineId, new LineWrite(null, "TELFOUT", "x", 2, null, true));
        assertNull(cleared.row().countedQuantity);
        assertNull(cleared.row().expectedQuantity);
        assertNull(cleared.row().expectedAt);
        assertNull(cleared.row().difference);
        assertNull(cleared.row().countedBy);
        assertNull(cleared.row().countedByName);
        assertNull(cleared.row().countedAt);
        assertNull(cleared.row().reasonCode);
        assertNull(cleared.row().reasonNote);
        assertFalse(cleared.row().documentsConfirmed);
        assertEquals(3, cleared.row().revision);
        assertEquals(20, stock.quantityAt(rose, shelf.id()), "counting never touches stock");
    }

    @Test @TestTransaction
    void lineCountAndCountedCountFollowTheLinesThatMatter() {
        StockLocation shelf = location("Voortgang");
        long stocked = product("Met voorraad", true);
        long empty = product("Zonder voorraad", true);
        long hidden = product("Inactief met voorraad", false);
        long extra = product("Inactief zonder voorraad", false);
        level(stocked, shelf, 10);
        level(hidden, shelf, 3);

        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        assertSummary(counts.view(count), 2, 0, 0, 0);

        /* Counted at zero where the book says zero: a counted line counts. */
        save(count, empty, 0, null, null);
        assertSummary(counts.view(count), 3, 1, 0, 0);

        assertTrue(counts.addLine(count, extra).created());
        assertFalse(counts.addLine(count, extra).created(), "the listed line is returned");
        assertTrue(line(counts.view(count), extra).row().addedByHand);
        assertSummary(counts.view(count), 4, 1, 0, 0);
        assertEquals("Product 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> counts.addLine(count, 987_654_321L)).getMessage());

        save(count, stocked, 8, null, null);
        assertSummary(counts.view(count), 4, 2, 1, 1);
        save(count, stocked, 8, "BESCHADIGD", null);
        assertSummary(counts.view(count), 4, 2, 1, 0);

        /* The level drops to zero after the count: the expected figure keeps the line in. */
        stock.sell(stocked, shelf.id(), 10, "F-LEEG");
        stock.sell(hidden, shelf.id(), 3, "F-LEEG");
        assertSummary(counts.view(count), 3, 2, 1, 0);
    }

    /* ---------------------------------------------------------------- booking */

    @Test @TestTransaction
    void bookingRefusesUntilEveryDifferenceHasAReasonAndEveryProductWithStockIsCounted() {
        StockLocation shelf = location("Controle");
        long rose = product("Roos", true);
        long tulip = product("Tulp", true);
        long late = product("Later binnen", false);
        level(rose, shelf, 10);
        level(tulip, shelf, 5);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        level(late, shelf, 2);
        long roseLine = save(count, rose, 9, null, null).row().id;

        Check check = counts.bookingCheck(count);
        assertEquals(List.of(tulip, late), check.uncounted().stream().map(Check.Uncounted::productId).toList());
        assertEquals(line(counts.view(count), tulip).row().id, check.uncounted().getFirst().lineId());
        assertEquals(5, check.uncounted().getFirst().liveQuantity());
        assertNull(check.uncounted().get(1).lineId(), "a product without a line is uncounted too");
        assertEquals("Later binnen - rood", check.uncounted().get(1).productName());
        assertEquals(List.of(roseLine), check.missingReasons());
        assertEquals(sha256(rose + ":10:1\n" + tulip + ":5\n" + late + ":2"), check.checkToken());

        InventoryRefusal uncounted = assertThrows(InventoryRefusal.class, () -> counts.book(count, check.checkToken()));
        assertEquals("NIET_GETELD", uncounted.code());
        assertEquals("Nog 2 producten met voorraad zijn niet geteld", uncounted.getMessage());

        save(count, tulip, 5, null, null);
        InventoryRefusal oneLeft = assertThrows(InventoryRefusal.class,
                () -> counts.book(count, counts.bookingCheck(count).checkToken()));
        assertEquals("Nog 1 product met voorraad is niet geteld", oneLeft.getMessage());
        counts.addLine(count, late);
        save(count, late, 2, null, null);
        InventoryRefusal reason = assertThrows(InventoryRefusal.class,
                () -> counts.book(count, counts.bookingCheck(count).checkToken()));
        assertEquals("REDEN_ONTBREEKT", reason.code());
        assertEquals("Bij 1 verschil ontbreekt een reden", reason.getMessage());
        assertEquals("OPEN", counts.view(count).summary().count().status);

        save(count, rose, 9, "BESCHADIGD", null);
        Check ready = counts.bookingCheck(count);
        assertEquals(List.of(), ready.uncounted());
        assertEquals(List.of(), ready.missingReasons());
        assertEquals(new Check.CheckSummary(3, 2, 1, 1, 0, 0), ready.summary());

        Session booked = counts.book(count, ready.checkToken());

        assertEquals("GEBOEKT", booked.summary().count().status);
        assertEquals("emre", booked.summary().count().bookedBy);
        assertNotNull(booked.summary().count().bookedAt);
        assertEquals(9, stock.quantityAt(rose, shelf.id()));
        /* One row per counted line, also where nothing differs; the uncounted lines of other products write none. */
        List<StockMovementEntity> rows = stocktakes(count);
        assertEquals(3, rows.size());
        assertEquals(List.of(-1, 0, 0), rows.stream().map(row -> row.delta).sorted().toList());
        assertTrue(rows.stream().allMatch(row -> row.reference.startsWith("Jaartelling " + YEAR + " #" + count + " ·")));
        assertEquals(2, rows.stream().filter(row -> row.reference.endsWith("· geen verschil")).count());
        assertEquals(1, rows.stream().filter(row -> row.reference.endsWith("· Beschadigd of stuk")).count());
        assertFalse(line(booked, rose).moved(), "a booked session asks no question any more");

        ActivityLogEntity entry = activity(count).getFirst();
        assertEquals("STOCK_BOOKED", entry.action);
        assertEquals("Jaartelling " + YEAR + " " + shelf.name(), entry.entityLabel);
        assertEquals("Jaartelling " + YEAR + " " + shelf.name() + " geboekt: 3 regels, 1 verschil (-1 / +0)", entry.summary);
        assertEquals("emre", entry.actorUsername);
    }

    @Test
    void theLedgerReferenceIsCutToWhatTheStockBookHolds() {
        assertEquals("Jaartelling 2026 #7 · geen verschil", StockCountService.reference("Jaartelling 2026 #7 ·", "geen verschil"));
        String cut = StockCountService.reference("Jaartelling 2026 #7 ·", "x".repeat(400));
        assertEquals(255, cut.length());
        assertTrue(cut.startsWith("Jaartelling 2026 #7 · xxx"));
    }

    @Test @TestTransaction
    void aMovementCommittedWhileTheBookingRunsIsNotOverwritten() {
        StockLocation shelf = location("Venster");
        long rose = product("Roos", true);
        level(rose, shelf, 100);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        save(count, rose, 98, "BESCHADIGD", null);
        String seen = counts.bookingCheck(count).checkToken();
        assertEquals(sha256(rose + ":100:1"), seen);

        /* Another user's sale of 10 reaches the database behind the back of this session, which still holds 100. */
        assertEquals(1, em.createQuery("update StockLevelEntity set quantity = 90 where productId = ?1 and locationId = ?2")
                .setParameter(1, rose).setParameter(2, shelf.id()).executeUpdate());
        assertEquals(100, stock.quantityAt(rose, shelf.id()), "the stale figure the booking used to write from");

        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.book(count, seen));
        assertEquals("TELLING_GEWIJZIGD", refused.code());
        assertEquals(List.of(), stocktakes(count));
        assertEquals(90, stock.quantityAt(rose, shelf.id()), "the sale stands; 98 was never written over it");

        em.clear();
        counts.book(count, counts.bookingCheck(count).checkToken());
        assertEquals(88, stock.quantityAt(rose, shelf.id()), "the sale of 10 and the difference of -2 both survive");
    }

    @Test @TestTransaction
    void aStaleCheckTokenSendsTheUserBackToTheCheck() {
        StockLocation shelf = location("Token");
        long rose = product("Roos", true);
        level(rose, shelf, 10);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        save(count, rose, 10, null, null);
        String seen = counts.bookingCheck(count).checkToken();
        assertEquals(sha256(rose + ":10:1"), seen);
        stock.sell(rose, shelf.id(), 1, "F-NA-CONTROLE");

        for (String token : new String[] {seen, null, ""}) {
            InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.book(count, token));
            assertEquals("TELLING_GEWIJZIGD", refused.code());
            assertEquals("De voorraad of de telling is intussen gewijzigd; bekijk de controle opnieuw", refused.getMessage());
        }
        assertEquals(List.of(), stocktakes(count));

        counts.book(count, counts.bookingCheck(count).checkToken());
        assertEquals(9, stock.quantityAt(rose, shelf.id()));
    }

    @Test @TestTransaction
    void aResultBelowZeroBlocksTheBooking() {
        StockLocation shelf = location("Onder nul");
        long rose = product("Roos", true);
        level(rose, shelf, 10);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        long lineId = save(count, rose, 0, "NIET_GEVONDEN", null).row().id;
        stock.sell(rose, shelf.id(), 6, "F-TUSSEN");

        Check check = counts.bookingCheck(count);
        assertEquals(List.of(new Check.Negative(lineId, "Roos - rood", -6)), check.negative());
        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.book(count, check.checkToken()));
        assertEquals("ONDER_NUL", refused.code());
        assertEquals("Roos - rood: de telling zou onder nul uitkomen. Tel dit product opnieuw", refused.getMessage());
        assertEquals(4, stock.quantityAt(rose, shelf.id()));
    }

    @Test @TestTransaction
    void anEmptyLocationBooksAFullCountWithNoCountedLine() {
        StockLocation shelf = location("Leeg");
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;

        Check check = counts.bookingCheck(count);
        assertEquals(0, check.summary().lines());
        assertEquals(sha256(""), check.checkToken());
        Session booked = counts.book(count, check.checkToken());

        assertEquals("GEBOEKT", booked.summary().count().status);
        assertEquals(List.of(), stocktakes(count), "nothing was counted, nothing is written");
        assertTrue(activity(count).getFirst().summary.endsWith("geboekt: 0 regels, 0 verschillen (-0 / +0)"));
        assertEquals(count, anchor(shelf).base().id);
        assertEquals(List.of(), anchor(shelf).lines());
    }

    @Test @TestTransaction
    void cancellingBooksNothingAndAClosedSessionRefusesEveryWrite() {
        StockLocation shelf = location("Annuleren");
        long rose = product("Roos", true);
        level(rose, shelf, 10);
        long cancelled = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        long cancelledLine = save(cancelled, rose, 4, "NIET_GEVONDEN", null).row().id;

        Session stopped = counts.cancel(cancelled);

        assertEquals("GEANNULEERD", stopped.summary().count().status);
        assertEquals("emre", stopped.summary().count().cancelledBy);
        assertNotNull(stopped.summary().count().cancelledAt);
        assertEquals(10, stock.quantityAt(rose, shelf.id()));
        assertEquals(List.of(), stocktakes(cancelled));
        assertEquals("STATUS_CHANGED", activity(cancelled).getFirst().action);
        assertEquals("Jaartelling " + YEAR + " " + shelf.name() + " geannuleerd", activity(cancelled).getFirst().summary);
        assertClosed("Deze telling is geannuleerd", cancelled, cancelledLine, rose);
        assertEquals("Deze telling is geannuleerd", assertThrows(InventoryRefusal.class, () -> counts.cancel(cancelled)).getMessage());

        long booked = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        long bookedLine = save(booked, rose, 10, null, null).row().id;
        counts.book(booked, counts.bookingCheck(booked).checkToken());
        assertClosed("Deze telling is al geboekt", booked, bookedLine, rose);
        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.cancel(booked));
        assertEquals("TELLING_GESLOTEN", refused.code());
        assertEquals("Een geboekte telling kan niet geannuleerd worden", refused.getMessage());
        assertEquals("GEBOEKT", counts.view(booked).summary().count().status);
    }

    /* ----------------------------------------------- invoices and containers */

    @Test @TestTransaction
    void aShortageThatAnUnshippedInvoiceExplainsIsSolvedInTheInvoiceAndBookedOnce() {
        StockLocation main = emptiedMainLocation();
        long rose = product("Roos", true);
        level(rose, main, 100);
        SalesOrder invoice = invoice(rose, 30, LocalDate.of(YEAR, 1, 1));
        long count = counts.start(YEAR, main.id(), null, null).summary().count().id;

        Line counted = save(count, rose, 70, "NIET_GEVONDEN", null);
        assertEquals(List.of(new OpenDocument("FACTUUR", invoice.id(), invoice.number(), 30)), counted.openDocuments());
        Session session = counts.view(count);
        assertTrue(session.warnings().unshippedInvoices().stream()
                .anyMatch(open -> open.salesOrderId() == invoice.id() && open.number().equals(invoice.number())
                        && open.orderDate().equals(LocalDate.of(YEAR, 1, 1))));
        assertEquals(counted.openDocuments(), line(session, rose).openDocuments());
        Check check = counts.bookingCheck(count);
        assertEquals(List.of(counted.row().id), check.openDocuments());

        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.book(count, check.checkToken()));
        assertEquals("EERST_AFPUNTEN", refused.code());
        assertEquals("Roos - rood: factuur " + invoice.number() + " is nog niet afgepunt. Punt ze eerst af, of bevestig"
                + " bij het product dat het verschil niet door die factuur komt", refused.getMessage());
        assertEquals(Map.of("lineIds", List.of(counted.row().id)), refused.details());
        assertEquals(100, stock.quantityAt(rose, main.id()));

        /* The pieces really left: the invoice is afgepunt, the line moved, the difference is recomputed. */
        sales.shipGoods(invoice.id());
        Line moved = line(counts.view(count), rose);
        assertTrue(moved.moved());
        assertEquals(70, moved.liveQuantity());
        assertEquals(List.of(), moved.openDocuments(), "the invoice no longer explains anything");
        Line rebased = counts.saveLine(count, moved.row().id, new LineWrite(70, null, null, moved.row().revision, true, null));
        assertEquals(0, rebased.row().difference);
        counts.book(count, counts.bookingCheck(count).checkToken());

        assertEquals(70, stock.quantityAt(rose, main.id()), "the level ends at the physical count");
        List<StockMovementEntity> rows = movements(rose);
        assertEquals(List.of(-30), rows.stream().filter(row -> row.kind().equals(StockMovement.Kind.SALE)).map(row -> row.delta).toList());
        assertEquals(List.of(0), rows.stream().filter(row -> row.kind().equals(StockMovement.Kind.STOCKTAKE)).map(row -> row.delta).toList());
    }

    @Test @TestTransaction
    void aConfirmedShortageIsBookedByTheCountAndTheLaterAfpuntenBooksTheSaleOnce() {
        StockLocation main = emptiedMainLocation();
        long rose = product("Roos", true);
        level(rose, main, 100);
        SalesOrder invoice = invoice(rose, 30, LocalDate.of(YEAR, 1, 1));
        long count = counts.start(YEAR, main.id(), null, null).summary().count().id;
        Line counted = save(count, rose, 70, null, null);
        assertEquals(List.of(counted.row().id), counts.bookingCheck(count).openDocuments(),
                "the open invoice is asked about before the missing reason is");

        /* "Dit verschil komt niet door deze factuur": the pieces of that invoice still lie there. */
        Line confirmed = counts.saveLine(count, counted.row().id, new LineWrite(70, "NIET_GEVONDEN", null, 1, null, true));
        assertTrue(confirmed.row().documentsConfirmed);
        assertEquals(-30, confirmed.row().difference);
        assertEquals(1, confirmed.openDocuments().size(), "the invoice stays on the line");
        Check check = counts.bookingCheck(count);
        assertEquals(List.of(), check.openDocuments());
        counts.book(count, check.checkToken());
        assertEquals(70, stock.quantityAt(rose, main.id()));

        sales.shipGoods(invoice.id());

        assertEquals(40, stock.quantityAt(rose, main.id()));
        List<StockMovementEntity> rows = movements(rose);
        assertEquals(List.of(-30), rows.stream().filter(row -> row.kind().equals(StockMovement.Kind.SALE)).map(row -> row.delta).toList());
        assertEquals(List.of(-30), rows.stream().filter(row -> row.kind().equals(StockMovement.Kind.STOCKTAKE)).map(row -> row.delta).toList());
    }

    @Test @TestTransaction
    void aSurplusThatAnUnbookedContainerExplainsIsSolvedInTheContainer() {
        StockLocation main = emptiedMainLocation();
        var supplier = suppliers.save(new Supplier(null, "Count Co", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB", "Ningbo", 30, null));
        long rose = product("Roos", true);
        em.find(ProductEntity.class, rose).supplierId = supplier.id();
        em.flush();
        level(rose, main, 100);
        PurchaseOrder created = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.92"), BigDecimal.ZERO);
        purchases.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null, List.of(
                new PurchaseOrderLine(null, rose, 24, new BigDecimal("2.00"), Currency.USD, null, null))));
        purchases.receive(created.id(), new PurchaseOrderService.Receipt(List.of(
                new PurchaseOrderService.ReceivedLine(rose, 24, 4)), false, null, LocalDate.now(), null));
        PurchaseOrder container = purchases.get(created.id());
        assertFalse(container.isStockBooked());
        long count = counts.start(YEAR, main.id(), null, null).summary().count().id;

        Line counted = save(count, rose, 120, "TERUGGEVONDEN", null);
        assertEquals(List.of(new OpenDocument("CONTAINER", container.id(), container.number(), 20)), counted.openDocuments(),
                "the usable pieces, as bijboeken would book them");
        var listed = counts.view(count).warnings().unbookedContainers().stream()
                .filter(open -> open.purchaseOrderId() == container.id()).findFirst().orElseThrow();
        assertEquals(container.number(), listed.number());
        assertEquals(container.displayName(), listed.displayName());
        assertEquals(LocalDate.now(), listed.receivedOn());
        assertEquals(Map.of(rose, 20), listed.quantities());
        Check check = counts.bookingCheck(count);
        assertEquals(List.of(counted.row().id), check.openDocuments());

        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.book(count, check.checkToken()));
        assertEquals("EERST_BIJBOEKEN", refused.code());
        assertEquals("Roos - rood: container " + container.displayName() + " is nog niet bijgeboekt. Boek hem eerst bij,"
                + " of bevestig bij het product dat het verschil niet door die container komt", refused.getMessage());
        assertEquals(Map.of("lineIds", List.of(counted.row().id)), refused.details());

        purchases.bookStock(container.id());
        assertEquals(120, stock.quantityAt(rose, main.id()));
        Line moved = line(counts.view(count), rose);
        assertTrue(moved.moved());
        assertEquals(List.of(), moved.openDocuments());
        counts.saveLine(count, moved.row().id, new LineWrite(120, null, null, moved.row().revision, true, null));
        counts.book(count, counts.bookingCheck(count).checkToken());

        assertEquals(120, stock.quantityAt(rose, main.id()));
        assertEquals(List.of(20), movements(rose).stream()
                .filter(row -> row.kind().equals(StockMovement.Kind.PURCHASE_RECEIPT)).map(row -> row.delta).toList());
        /* A container unloaded elsewhere says nothing about this location. */
        StockLocation elsewhere = location("Elders");
        long other = counts.start(YEAR, elsewhere.id(), null, null).summary().count().id;
        assertEquals(List.of(), counts.view(other).warnings().unbookedContainers());
    }

    @Test @TestTransaction
    void anInvoiceFromBeforeTheCountYearIsOnlyCounted() {
        StockLocation main = emptiedMainLocation();
        long rose = product("Roos", true);
        level(rose, main, 100);
        long count = counts.start(YEAR, main.id(), null, null).summary().count().id;
        int olderBefore = counts.view(count).warnings().olderUnshippedInvoiceCount();

        SalesOrder old = invoice(rose, 30, LocalDate.of(YEAR - 1, 12, 31));
        SalesOrder concept = invoice(rose, 30, LocalDate.of(YEAR, 2, 1));
        em.find(SalesOrderEntity.class, concept.id()).status = QuoteStatus.CONCEPT;
        em.flush(); em.clear();

        Line counted = save(count, rose, 70, "NIET_GEVONDEN", null);
        Session session = counts.view(count);
        assertEquals(olderBefore + 1, session.warnings().olderUnshippedInvoiceCount());
        assertTrue(session.warnings().unshippedInvoices().stream()
                .noneMatch(open -> open.salesOrderId() == old.id() || open.salesOrderId() == concept.id()));
        assertEquals(List.of(), counted.openDocuments(), "an older invoice never touches a line");
        Check check = counts.bookingCheck(count);
        assertEquals(List.of(), check.openDocuments());
        counts.book(count, check.checkToken());
        assertEquals(70, stock.quantityAt(rose, main.id()));
    }

    /* ------------------------------------------------------ deleted products */

    @Test @TestTransaction
    void aLevelOfADeletedProductIsListedAndNeverCountedOrBooked() {
        StockLocation shelf = location("Wees");
        long rose = product("Roos", true);
        level(rose, shelf, 10);
        StockLevelEntity orphan = new StockLevelEntity();
        orphan.productId = 987_654_321L;
        orphan.locationId = shelf.id();
        orphan.quantity = 7;
        em.persist(orphan);
        em.flush();

        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;

        Session session = counts.view(count);
        assertEquals(List.of(new OrphanLevel(987_654_321L, 7)), session.warnings().orphanLevels());
        assertTrue(session.lines().stream().noneMatch(line -> line.row().productId == 987_654_321L));
        save(count, rose, 10, null, null);
        Check check = counts.bookingCheck(count);
        assertEquals(List.of(), check.uncounted(), "a level without a product is never uncounted");
        assertEquals(sha256(rose + ":10:1"), check.checkToken());
        assertEquals("GEBOEKT", counts.book(count, check.checkToken()).summary().count().status);
        assertEquals(7, stock.quantityAt(987_654_321L, shelf.id()));
        assertEquals(1, stocktakes(count).size());
    }

    @Test @TestTransaction
    void aProductDeletedWhileTheSessionIsOpenIsSkippedByTheBooking() {
        StockLocation shelf = location("Verwijderd");
        long rose = product("Roos", true);
        long gone = product("Verdwenen", true);
        level(rose, shelf, 5);
        level(gone, shelf, 10);
        long count = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        save(count, rose, 5, null, null);
        long goneLine = save(count, gone, 8, null, null).row().id;
        assertEquals(sha256(rose + ":5:1\n" + gone + ":10:1"), counts.bookingCheck(count).checkToken());
        Summary before = counts.view(count).summary();
        assertEquals(List.of(2, 2, 1, 1), List.of(before.lineCount(), before.countedCount(), before.differenceCount(),
                before.missingReasonCount()));

        products.delete(gone);
        em.flush(); em.clear();

        Session session = counts.view(count);
        Summary after = session.summary();
        assertEquals(List.of(1, 1, 0, 0), List.of(after.lineCount(), after.countedCount(), after.differenceCount(),
                after.missingReasonCount()), "the figures leave the line out, as the booking check does");
        Summary onHub = counts.overview(YEAR).locations().stream()
                .filter(location -> location.location().id().equals(shelf.id())).findFirst().orElseThrow().open();
        assertEquals(List.of(1, 1, 0, 0), List.of(onHub.lineCount(), onHub.countedCount(), onHub.differenceCount(),
                onHub.missingReasonCount()), "and the overview shows the same four");
        assertEquals(List.of(new OrphanLevel(gone, 10)), session.warnings().orphanLevels());
        assertNotNull(line(session, gone), "the line stays in the list");
        assertFalse(line(session, gone).moved());
        Check check = counts.bookingCheck(count);
        assertEquals(1, check.summary().lines());
        assertEquals(List.of(), check.missingReasons(), "its missing reason no longer blocks");
        assertEquals(List.of(), check.uncounted());
        assertEquals(sha256(rose + ":5:1"), check.checkToken());

        Session booked = counts.book(count, check.checkToken());

        assertEquals("GEBOEKT", booked.summary().count().status);
        assertEquals(check.summary().lines(), booked.summary().lineCount(), "the booked session counts what was booked");
        assertEquals(1, booked.summary().countedCount());
        assertEquals(0, booked.summary().differenceCount());
        assertEquals(1, stocktakes(count).size(), "one row for the other line, none for the deleted product");
        assertEquals(rose, stocktakes(count).getFirst().productId);
        StockCountLineEntity skipped = em.find(StockCountLineEntity.class, goneLine);
        assertNull(skipped.bookedDelta);
        assertNull(skipped.liveAtBooking);
        assertNull(skipped.bookedQuantity);
        assertNull(skipped.bookedAt);
        assertEquals(10, stock.quantityAt(gone, shelf.id()));
        assertEquals(List.of(rose), anchor(shelf).lines().stream().map(row -> row.productId).toList());
    }

    /* ------------------------------------------------------------ corrections */

    @Test @TestTransaction
    void aCorrectionStartsOnTheLatestBookedFullCountAndCountsOnlyWhatIsAdded() {
        StockLocation shelf = location("Correctie");
        long rose = product("Roos", true);
        long tulip = product("Tulp", true);
        level(rose, shelf, 10);
        level(tulip, shelf, 5);
        long first = fullCount(shelf, Map.of(rose, 10, tulip, 5));
        assertEquals("GEEN_TELLING_OM_TE_CORRIGEREN", assertThrows(InventoryRefusal.class,
                () -> counts.start(YEAR, shelf.id(), null, 987_654_321L)).code());
        assertEquals("GEEN_TELLING_OM_TE_CORRIGEREN", assertThrows(InventoryRefusal.class,
                () -> counts.start(YEAR - 1, shelf.id(), null, first)).code(), "the count is of another year");
        long second = fullCount(shelf, Map.of(rose, 10, tulip, 5));

        InventoryRefusal refused = assertThrows(InventoryRefusal.class, () -> counts.start(YEAR, shelf.id(), null, first));
        assertEquals("GEEN_TELLING_OM_TE_CORRIGEREN", refused.code());
        assertEquals("Corrigeren kan alleen op de laatste geboekte telling van " + shelf.name() + " voor " + YEAR,
                refused.getMessage());

        Session correction = counts.start(YEAR, shelf.id(), "doos gevonden", second);
        long id = correction.summary().count().id;
        assertEquals(second, correction.summary().count().correctsCountId);
        assertEquals(List.of(), correction.lines(), "a correction starts without lines");
        assertEquals(List.of(id), counts.openSessions(YEAR).stream().map(count -> count.id).filter(open -> open == id).toList());
        assertEquals("TELLING_LOOPT", assertThrows(InventoryRefusal.class,
                () -> counts.start(YEAR, shelf.id(), null, null)).code(), "a correction holds the location too");

        assertTrue(counts.addLine(id, rose).created());
        save(id, rose, 12, "TERUGGEVONDEN", null);
        Check check = counts.bookingCheck(id);
        assertEquals(List.of(), check.uncounted(), "the other products keep the figure of the full count");
        assertEquals(new Check.CheckSummary(1, 0, 0, 0, 1, 2), check.summary());

        Session booked = counts.book(id, check.checkToken());

        assertEquals(12, stock.quantityAt(rose, shelf.id()));
        assertEquals(5, stock.quantityAt(tulip, shelf.id()));
        StockCountLineEntity row = line(booked, rose).row();
        assertEquals(10, row.liveAtBooking);
        assertEquals(2, row.bookedDelta);
        assertEquals(12, row.bookedQuantity);
        assertNotNull(row.bookedAt);
        assertFalse(row.bookedAt.isAfter(booked.summary().count().bookedAt), "the session is booked after its last line");
        assertEquals("Correctie jaartelling " + YEAR + " " + shelf.name() + " geboekt: 1 regel, 1 verschil (-0 / +2)",
                activity(id).getFirst().summary);
        assertEquals(List.of(), counts.openSessions(YEAR).stream().filter(open -> open.id == id).toList());

        /* What a closing rolls to its date: the latest full count, its corrections, every booked line. */
        StockCountService.Anchor anchor = anchor(shelf);
        assertEquals(second, anchor.base().id);
        assertEquals(List.of(id), anchor.corrections().stream().map(count -> count.id).toList());
        assertEquals(3, anchor.lines().size());
        assertEquals(2, anchor.lines().stream().filter(line -> line.countId == second).count());
        assertEquals(List.of(12), anchor.lines().stream().filter(line -> line.countId == id).map(line -> line.bookedQuantity).toList());
        assertTrue(anchor.lines().stream().allMatch(line -> line.bookedAt != null));
        assertTrue(counts.anchors(YEAR - 1).stream().noneMatch(other -> other.locationId() == shelf.id()));

        var state = counts.overview(YEAR).locations().stream()
                .filter(location -> location.location().id().equals(shelf.id())).findFirst().orElseThrow();
        assertEquals(second, state.booked().count().id);
        assertEquals(1, state.correctionCount());
        assertNull(state.open());
        assertEquals(2, state.productsWithStock());
    }

    /* --------------------------------------------------------------- overview */

    @Test @TestTransaction
    void theOverviewShowsTheLocationsOfTheYearAndEveryYearThatHasASession() {
        StockLocation shelf = location("Overzicht");
        StockLocation closed = location("Gesloten met voorraad");
        StockLocation idle = location("Gesloten en leeg");
        long rose = product("Roos", true);
        level(rose, shelf, 10);
        level(rose, closed, 2);
        deactivate(closed);
        deactivate(idle);
        long old = counts.start(YEAR - 7, shelf.id(), null, null).summary().count().id;
        counts.cancel(old);
        long open = counts.start(YEAR, shelf.id(), null, null).summary().count().id;
        save(open, rose, 9, null, null);

        var overview = counts.overview(YEAR);

        assertEquals(YEAR, overview.year());
        assertEquals(YEAR, counts.overview(null).year(), "without a year: the current one in Brussels");
        assertTrue(overview.years().contains(YEAR) && overview.years().contains(YEAR - 7));
        assertEquals(overview.years().stream().sorted(java.util.Comparator.reverseOrder()).toList(), overview.years());
        assertEquals(overview.years().stream().distinct().toList(), overview.years());
        var state = overview.locations().stream().filter(location -> location.location().id().equals(shelf.id()))
                .findFirst().orElseThrow();
        assertEquals(1, state.productsWithStock());
        assertEquals(open, state.open().count().id);
        assertEquals(1, state.open().lineCount());
        assertEquals(1, state.open().countedCount());
        assertEquals(1, state.open().differenceCount());
        assertEquals(1, state.open().missingReasonCount());
        assertNull(state.booked());
        assertEquals(0, state.correctionCount());
        assertTrue(overview.locations().stream().anyMatch(location -> location.location().id().equals(closed.id())),
                "an inactive location that still holds stock is counted");
        assertTrue(overview.locations().stream().noneMatch(location -> location.location().id().equals(idle.id())));
        assertTrue(overview.counts().stream().anyMatch(count -> count.count().id == open));
        assertTrue(overview.counts().stream().noneMatch(count -> count.count().id == old));
        assertEquals(List.of(old), counts.overview(YEAR - 7).counts().stream().map(count -> count.count().id)
                .filter(id -> id == old).toList());
    }

    /* ---------------------------------------------------------------- helpers */

    private void assertClosed(String message, long count, long lineId, long productId) {
        List<InventoryRefusal> refusals = List.of(
                assertThrows(InventoryRefusal.class, () -> counts.saveLine(count, lineId, new LineWrite(1, null, null, 1, null, null))),
                assertThrows(InventoryRefusal.class, () -> counts.addLine(count, productId)),
                assertThrows(InventoryRefusal.class, () -> counts.bookingCheck(count)),
                assertThrows(InventoryRefusal.class, () -> counts.book(count, "x")));
        for (InventoryRefusal refusal : refusals) {
            assertEquals("TELLING_GESLOTEN", refusal.code());
            assertEquals(message, refusal.getMessage());
        }
    }

    private static void assertSummary(Session session, int lines, int counted, int differences, int missingReasons) {
        assertEquals(lines, session.summary().lineCount(), "lineCount");
        assertEquals(counted, session.summary().countedCount(), "countedCount");
        assertEquals(differences, session.summary().differenceCount(), "differenceCount");
        assertEquals(missingReasons, session.summary().missingReasonCount(), "missingReasonCount");
    }

    /** A booked full count of the location with these counted figures. */
    private long fullCount(StockLocation location, Map<Long, Integer> counted) {
        long count = counts.start(YEAR, location.id(), null, null).summary().count().id;
        counted.forEach((product, quantity) -> save(count, product, quantity, "TELFOUT", null));
        counts.book(count, counts.bookingCheck(count).checkToken());
        return count;
    }

    private Line save(long count, long productId, Integer quantity, String reason, String note) {
        StockCountLineEntity row = line(counts.view(count), productId).row();
        return counts.saveLine(count, row.id, new LineWrite(quantity, reason, note, row.revision, null, null));
    }

    private static Line line(Session session, long productId) {
        return session.lines().stream().filter(line -> line.row().productId == productId).findFirst().orElse(null);
    }

    private StockCountService.Anchor anchor(StockLocation location) {
        return counts.anchors(YEAR).stream().filter(anchor -> anchor.locationId() == location.id()).findFirst().orElseThrow();
    }

    private List<StockMovementEntity> stocktakes(long count) {
        return em.createQuery("from StockMovementEntity where kind = 'STOCKTAKE' and reference like ?1 order by id",
                StockMovementEntity.class).setParameter(1, "Jaartelling " + YEAR + " #" + count + " ·%").getResultList();
    }

    private List<StockMovementEntity> movements(long productId) {
        return em.createQuery("from StockMovementEntity where productId = ?1 order by id", StockMovementEntity.class)
                .setParameter(1, productId).getResultList();
    }

    private List<ActivityLogEntity> activity(long count) {
        return em.createQuery("from ActivityLogEntity where entityType = 'STOCK_COUNT' and entityId = ?1 order by id desc",
                ActivityLogEntity.class).setParameter(1, String.valueOf(count)).getResultList();
    }

    private StockLocation location(String name) {
        return stock.saveLocation(new StockLocation(null, null, name + " " + UUID.randomUUID().toString().substring(0, 8),
                StockLocation.Kind.SALES_POINT, null, true, false, false, 9));
    }

    private void deactivate(StockLocation location) {
        em.createQuery("update StockLocationEntity set active = false where id = ?1").setParameter(1, location.id()).executeUpdate();
        em.clear();
    }

    /**
     * The warehouse, where afpunten and bijboeken book, without what other tests left there:
     * a full count needs every product with stock counted. Rolled back with the test.
     */
    private StockLocation emptiedMainLocation() {
        StockLocation main = stock.mainLocation();
        em.createQuery("update StockLevelEntity set quantity = 0 where locationId = ?1").setParameter(1, main.id()).executeUpdate();
        em.createQuery("update StockCountEntity set status = 'GEANNULEERD' where locationId = ?1 and status = 'OPEN'")
                .setParameter(1, main.id()).executeUpdate();
        em.clear();
        return main;
    }

    private void level(long productId, StockLocation location, int quantity) {
        stock.setLevel(productId, location.id(), quantity, StockMovement.Kind.MANUAL_CORRECTION, "test");
    }

    private long product(String name, boolean active) {
        var product = new ProductEntity();
        product.sku = "COUNT-" + UUID.randomUUID();
        product.name = name;
        product.colour = "rood";
        product.active = active;
        product.piecesPerCarton = 12;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        em.persist(product);
        em.flush();
        return product.id;
    }

    /** An issued Belgian invoice for one product that is not afgepunt. */
    private SalesOrder invoice(long productId, int quantity, LocalDate orderDate) {
        var customer = customers.create(new Customer(null, "Count " + UUID.randomUUID(), "Buyer", "count@example.invalid", null,
                "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var created = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = new BigDecimal("120.00");
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        stored.orderDate = orderDate;
        var line = new SalesOrderLineEntity();
        line.order = stored; line.productId = productId; line.quantity = quantity;
        line.unitPriceEur = new BigDecimal("8.45"); line.unitCostEur = new BigDecimal("5.10");
        stored.lines.add(line);
        em.flush(); em.clear();
        return sales.get(created.id());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
