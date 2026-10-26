package be.enrosed.inventory.application;

import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchMoveEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchRunEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.inventory.application.StockBatchBackfillService.Flag;
import be.enrosed.inventory.application.StockBatchBackfillService.Report;
import be.enrosed.inventory.application.StockBatchBackfillService.ReportBatch;
import be.enrosed.inventory.application.StockBatchBackfillService.ReportRow;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.BatchCosts;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Batches opbouwen" (batches contract §5.3, tests B3 (A)-(F) and the extras). Every case runs in one rolled-back
 * transaction on a quieted book: every other container back to concept and every other level at zero, so the report
 * holds this test's products only and every figure is worked out by hand in the comments.
 *
 * <p>The containers of (A)-(D): A 500 usable received 10/01, B 300 on 10/03, C 200 on 10/05, applied at € 1, € 2
 * and € 3; the card says € 0,50.
 */
@QuarkusTest
class StockBatchBackfillTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 10);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 10);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 10);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 10);

    @Inject StockBatchBackfillService backfill;
    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    private Supplier supplier;
    private long main;
    private final String tag = "BF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    /* ------------------------------------------------------------------ (A) */

    @Test
    @TestTransaction
    void theShelfIsFilledFromTheNewestBatchDownAndOlderPiecesLeftBeforeTheStart() {
        quiet();
        long product = product("A", 1, "0.50");
        long[] abc = abc(product);
        level(product, main, 350, 0);

        Report dry = backfill.dryRun();
        assertTrue(dry.dryRun());
        assertNull(dry.startedAt());
        ReportRow row = row(dry, product);
        /* 350 on the shelf: C 200, B 150, A nothing; worth 200 × 3 + 150 × 2 */
        assertEquals(List.of("A 500/0", "B 300/150", "C 200/200"), shape(row, abc));
        assertMoney("900.00", row.valueEur());
        assertEquals(350, row.stockPieces());
        assertEquals(List.of(), row.flags());
        assertEquals(1, dry.products());
        assertEquals(3, dry.batches());
        assertMoney("900.00", dry.valueEur());
        assertEquals(0, count(StockBatchEntity.class), "the dry run writes nothing");
        assertEquals(0, count(StockBatchMoveEntity.class));
        assertFalse(batches.started());

        Report started = backfill.start();
        assertFalse(started.dryRun());
        assertNotNull(started.startedAt());
        assertEquals(shape(row, abc), shape(row(started, product), abc), "the start writes what the dry run showed");
        assertTrue(batches.started());
        assertTrue(batches.active());
        StockBatch a = batch(product, abc[0]);
        StockBatch b = batch(product, abc[1]);
        StockBatch c = batch(product, abc[2]);
        assertEquals(List.of(0, 150, 200), List.of(a.piecesLeft(), b.piecesLeft(), c.piecesLeft()));
        assertEquals(List.of(500, 150, 0), List.of(a.earlierPieces(), b.earlierPieces(), c.earlierPieces()),
                "what left before the start shows as Vóór de start");
        assertEquals(StockBatch.CostState.APPLIED, c.costState());
        assertMoney("3.0000", c.unitCostEur());
        assertEquals("emre", c.costAppliedBy());
        assertEquals(StockBatch.Kind.RECEIPT, c.kind());
        assertEquals(supplier.id(), c.supplierId());
        assertEquals(MAY, c.receivedOn());

        List<StockBatchMoveEntity> backfills = em.createQuery("select m from StockBatchMoveEntity m where "
                + "m.productId = :p and m.kind = 'BACKFILL' order by m.id", StockBatchMoveEntity.class)
                .setParameter("p", product).getResultList();
        assertEquals(2, backfills.size(), "none on C");
        assertEquals(a.id(), backfills.get(0).batchId, "A's move first: a newer batch's BACKFILL move has the higher id");
        assertEquals(-500, backfills.get(0).pieces);
        assertEquals(b.id(), backfills.get(1).batchId);
        assertEquals(-150, backfills.get(1).pieces);
        assertTrue(batches.check().isEmpty(), "no difference after the start");
        assertEquals(1L, count(StockBatchRunEntity.class));

        String today = LocalDate.now(ZoneId.of("Europe/Brussels")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        assertEquals("Batches zijn al gestart op " + today + ".",
                assertThrows(BusinessRuleException.class, () -> backfill.start()).getMessage());
        assertEquals(3, row(backfill.dryRun(), product).batches().size(), "a dry run may still run after the start");
    }

    /* ------------------------------------------------------------------ (B) */

    @Test
    @TestTransaction
    void aPartnerContainerKeepsWhatItStillHoldsAndIsNotInTheValue() {
        quiet();
        long product = product("B", 1, "0.50");
        long[] abc = abc(product);
        long partner = container("P", JUN, product, 100, "1.00", true, partnerCustomer());
        history(product, partner, "4.00", "APPLY", "PIECE", null, null, null);
        settlement(partner, product, 40);
        level(product, main, 350, 0);

        ReportRow row = row(backfill.dryRun(), product);
        /* P holds 100 − 40 = 60 of the 350; the own 290: C 200, B 90; value 200 × 3 + 90 × 2, partner apart */
        assertEquals(List.of("A 500/0", "B 300/90", "C 200/200"), shape(row, abc));
        ReportBatch p = row.batches().stream().filter(batch -> batch.purchaseOrderId() == partner).findFirst()
                .orElseThrow();
        assertEquals(StockBatch.Owner.PARTNER, p.owner());
        assertEquals(100, p.piecesIn());
        assertEquals(60, p.piecesLeft());
        assertMoney("780.00", row.valueEur());
        assertEquals(List.of(Flag.PARTNER), row.flags());

        backfill.start();
        StockBatch stored = batch(product, partner);
        assertEquals(StockBatch.Owner.PARTNER, stored.owner());
        assertEquals(60, stored.piecesLeft());
        assertEquals(90, batch(product, abc[1]).piecesLeft());
        assertTrue(batches.check().isEmpty());
    }

    /* ------------------------------------------------------------------ (C) */

    @Test
    @TestTransaction
    void stockNoContainerExplainsBecomesOneOpeningBatchAtTheCardCost() {
        quiet();
        long product = product("C", 1, "0.50");
        long[] abc = abc(product);
        level(product, main, 1100, 0);

        Report dry = backfill.dryRun();
        ReportRow row = row(dry, product);
        /* 1.100: C 200, B 300, A 500 and 100 nobody explains; 600 + 600 + 500 + 100 × 0,50 */
        assertEquals(List.of("A 500/500", "B 300/300", "C 200/200"), shape(row, abc));
        ReportBatch opening = row.batches().getFirst();
        assertEquals(StockBatch.Kind.OPENING, opening.kind(), "Beginvoorraad comes first");
        assertEquals(100, opening.piecesIn());
        assertEquals(100, opening.piecesLeft());
        assertEquals(StockBatch.CostState.CARD, opening.costState());
        assertMoney("0.5000", opening.unitCostEur());
        assertEquals(100, row.openingPieces());
        assertEquals(100, dry.openingPieces());
        assertEquals(List.of(Flag.OPENING), row.flags());
        assertMoney("1750.00", row.valueEur());

        backfill.start();
        StockBatch stored = batches.forProduct(product).getFirst();
        assertEquals(StockBatch.Kind.OPENING, stored.kind(), "Beginvoorraad goes out first");
        assertEquals("Beginvoorraad, kost van de productkaart", stored.note());
        assertEquals(StockBatch.CostState.CARD, stored.costState());
        assertNull(stored.unitCostExclEnrosedEur());
        assertEquals(100, stored.piecesLeft());
        assertTrue(batches.check().isEmpty());
    }

    /* ------------------------------------------------------------------ (D) */

    @Test
    @TestTransaction
    void negativeStockBecomesOneUnassignedMoveWithANotePerLocation() {
        quiet();
        long product = product("D", 1, "0.50");
        long[] abc = abc(product);
        long tica = location("Tica Brussel");
        level(product, main, 350, 0);
        level(product, tica, -3, 0);

        Report dry = backfill.dryRun();
        ReportRow row = row(dry, product);
        assertEquals(List.of("A 500/0", "B 300/150", "C 200/200"), shape(row, abc), "the pool is 350 as in (A)");
        assertEquals(3, row.unassignedPieces());
        assertEquals(3, dry.unassignedPieces());
        assertEquals(347, row.stockPieces());
        assertEquals(List.of(Flag.NEGATIVE), row.flags());
        assertEquals(List.of("Negatief op Tica Brussel: −3"), row.notes());

        backfill.start();
        List<StockBatchMoveEntity> unassigned = em.createQuery("select m from StockBatchMoveEntity m where "
                + "m.productId = :p and m.batchId is null", StockBatchMoveEntity.class)
                .setParameter("p", product).getResultList();
        assertEquals(1, unassigned.size());
        assertEquals(-3, unassigned.getFirst().pieces);
        assertEquals("BACKFILL", unassigned.getFirst().kind);
        assertEquals("Negatieve voorraad bij de start", unassigned.getFirst().reference);
        assertEquals(3, batches.totals(product).unassignedPieces());
        assertTrue(batches.check().isEmpty(), "350 in batches − 3 without batch = 347 on the shelf");
    }

    /* -------------------------------------------------------------- (E) (F) */

    @Test
    @TestTransaction
    void aDisplayCountsInPiecesAndAHistoryRowIsReadOnTheBasisItWasAppliedOn() {
        quiet();
        /* (E) sold per display of 12: 50 displays received, 20 displays + 4 loose on the shelf */
        long display = product("E", 12, "8.0000");
        long e = container("E", MAR, display, 50, "5.00", true, null);
        history(display, e, "8.3180", "APPLY", "DISPLAY", 12, null, null);
        level(display, main, 20, 4);
        /* (F) a display of 12 sold per piece: the factor is 1, a row applied per piece is per piece */
        long perPiece = perPieceDisplay("F", 12, "0.6000");
        long f = container("F", MAR, perPiece, 120, "0.50", true, null);
        history(perPiece, f, "0.6930", "APPLY", "PIECE", 12, null, null);
        level(perPiece, main, 100, 0);

        Report dry = backfill.dryRun();
        ReportRow displayRow = row(dry, display);
        assertEquals(12, displayRow.unitPieces());
        assertEquals(244, displayRow.stockPieces());
        ReportBatch onDisplay = displayRow.batches().getFirst();
        assertEquals(600, onDisplay.piecesIn());
        assertEquals(244, onDisplay.piecesLeft());
        assertMoney("8.3180", onDisplay.unitCostEur(), "a row per display is copied as is");
        /* 244 pieces = 20,33 displays × 8,3180 */
        assertMoney("169.13", displayRow.valueEur());
        ReportRow pieceRow = row(dry, perPiece);
        assertEquals(1, pieceRow.unitPieces());
        assertMoney("0.6930", pieceRow.batches().getFirst().unitCostEur(), "never ÷ 12");

        backfill.start();
        StockBatch stored = batch(display, e);
        assertEquals(12, stored.unitPieces());
        assertEquals(600, stored.piecesIn());
        assertEquals(244, stored.piecesLeft());
        assertMoney("8.3180", stored.unitCostEur());
        assertMoney("169.13", stored.valueLeftEur());
        assertMoney("0.6930", batch(perPiece, f).unitCostEur());
        assertEquals(1, batch(perPiece, f).unitPieces());
        assertTrue(batches.check().isEmpty());
    }

    /* ------------------------------------------------------- costs and extras */

    @Test
    @TestTransaction
    void theCostComesFromTheAppliedRowElseTheCalculationAndAResetLetsItStartAgain() {
        quiet();
        /* X: only a COPY row of its container (another product's cost) → the calculation */
        long x = product("X", 1, "0.50");
        long kx = container("KX", MAR, x, 100, "1.00", true, null);
        history(x, kx, "9.9900", "COPY", "PIECE", null, null, null);
        level(x, main, 40, 0);
        /* Y: an applied row with its parts, bought on its supplier row */
        long y = product("Y", 1, "0.50");
        long ysRow = supplierRow(y);
        long ky = container("KY", MAR, y, 100, "1.00", true, null);
        history(y, ky, "1.2000", "APPLY", "PIECE", null, "0.9000", "0.1000");
        level(y, main, 30, 0);
        /* Z: no row → the calculation; another container of Z was received but never booked */
        long z = product("Z", 1, "0.50");
        long kz = container("KZ", MAR, z, 100, "2.00", true, null);
        long notBooked = container("KN", MAY, z, 10, "2.00", false, null);
        level(z, main, 20, 0);
        /* W: stock without a container, no card cost → Beginvoorraad without cost */
        long w = product("W", 1, null);
        level(w, main, 5, 0);
        SalesOrderLineEntity stamped = stampedSalesLine(y);

        Report dry = backfill.dryRun();
        assertEquals(4, dry.products());
        assertEquals(List.of(w, x, y, z), dry.rows().stream().map(ReportRow::productId).toList(),
                "flagged rows first, then by SKU");
        assertEquals(List.of(Flag.OPENING, Flag.NO_COST), row(dry, w).flags());
        assertEquals(StockBatch.CostState.NONE, row(dry, w).batches().getFirst().costState());
        assertTrue(dry.notBooked().stream().anyMatch(n -> n.purchaseOrderId() == notBooked),
                "received but not booked is listed");
        assertTrue(row(dry, z).batches().stream().noneMatch(batch -> Long.valueOf(notBooked)
                .equals(batch.purchaseOrderId())), "and gets no batch");

        BatchCost calcX = calculated(kx, x);
        ReportBatch onX = row(dry, x).batches().getFirst();
        assertEquals(StockBatch.CostState.CALCULATED, onX.costState(), "a COPY row is never the product's own cost");
        assertMoney(calcX.unitCostEur().toPlainString(), onX.unitCostEur());
        assertEquals(StockBatch.CostState.CALCULATED, row(dry, z).batches().getFirst().costState());
        assertMoney(calculated(kz, z).unitCostEur().toPlainString(), row(dry, z).batches().getFirst().unitCostEur());

        Report first = backfill.start();
        assertEquals(4, first.products());
        StockBatch onY = batch(y, ky);
        assertEquals(StockBatch.CostState.APPLIED, onY.costState());
        assertMoney("1.2000", onY.unitCostEur());
        assertMoney("1.1000", onY.unitCostExclEnrosedEur(), "without the Enrosed kost of the row");
        assertMoney("0.9000", onY.parts().goodsEur());
        assertMoney("0.1000", onY.parts().enrosedEur());
        Long lineRow = em.createQuery("select l.productSupplierId from " + PurchaseOrderLineEntity.class.getName()
                + " l where l.order.id = :o", Long.class).setParameter("o", ky).getSingleResult();
        assertEquals(Long.valueOf(ysRow), lineRow, "the line was bought on Y's supplier row");
        assertEquals(Long.valueOf(ysRow), onY.productSupplierId(), "the batch carries the line's supplier row");
        StockBatch onX2 = batch(x, kx);
        assertEquals(StockBatch.CostState.CALCULATED, onX2.costState());
        assertMoney(calcX.unitCostExclEnrosedEur().toPlainString(), onX2.unitCostExclEnrosedEur());
        assertTrue(batches.check().isEmpty());

        backfill.reset();
        em.clear();
        assertEquals(0, count(StockBatchEntity.class));
        assertEquals(0, count(StockBatchMoveEntity.class));
        assertEquals(0, count(StockBatchRunEntity.class));
        assertFalse(batches.started());
        assertNull(em.createQuery("select l.stockCostEur from " + SalesOrderLineEntity.class.getName() + " l where l.id = :id",
                BigDecimal.class).setParameter("id", stamped.id).getSingleResult(), "the reset clears the stamps");

        Report again = backfill.start();
        assertEquals(4, again.products(), "after a reset the start runs again");
        assertEquals(30, batch(y, ky).piecesLeft());
        assertTrue(batches.check().isEmpty());
    }

    /* -------------------------------------------------------------- helpers */

    /** Every other container back to concept, every other level at zero, no batch data: this test's book only. */
    private void quiet() {
        em.createQuery("delete from StockBatchMoveEntity").executeUpdate();
        em.createQuery("delete from StockBatchEntity").executeUpdate();
        em.createQuery("delete from StockBatchRunEntity").executeUpdate();
        em.createQuery("update " + PurchaseOrderEntity.class.getName() + " set status = ?1")
                .setParameter(1, PurchaseOrderStatus.CONCEPT).executeUpdate();
        em.createQuery("update StockLevelEntity set quantity = 0, loosePieces = 0").executeUpdate();
        em.clear();
        supplier = suppliers.save(new Supplier(null, "Backfill " + tag, "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB Ningbo", "Ningbo", 30, null));
        main = stock.mainLocation().id();
    }

    /** A, B and C of the worked examples for one product, applied at 1, 2 and 3. */
    private long[] abc(long product) {
        long a = container("A", JAN, product, 500, "1.00", true, null);
        long b = container("B", MAR, product, 300, "1.00", true, null);
        long c = container("C", MAY, product, 200, "1.00", true, null);
        history(product, a, "1.00", "APPLY", "PIECE", null, null, null);
        history(product, b, "2.00", "APPLY", "PIECE", null, null, null);
        history(product, c, "3.00", "REAPPLY", "PIECE", null, null, null);
        return new long[]{a, b, c};
    }

    private ProductEntity entity(String letter, String cardCost) {
        ProductEntity product = new ProductEntity();
        product.sku = tag + "-" + letter;
        product.name = "Backfill " + letter;
        product.colour = "rood";
        product.active = true;
        product.supplierId = supplier.id();
        product.inventoryKnown = false;
        product.stockQuantity = 0;
        product.landedCostEur = cardCost == null ? null : new BigDecimal(cardCost);
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        return product;
    }

    private long product(String letter, int unitPieces, String cardCost) {
        ProductEntity product = entity(letter, cardCost);
        if (unitPieces > 1) {
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
            product.packagingPiecesPerUnit = unitPieces;
        }
        em.persist(product);
        em.flush();
        return product.id;
    }

    private long perPieceDisplay(String letter, int pieces, String cardCost) {
        ProductEntity product = entity(letter, cardCost);
        product.packagingKind = PackagingKind.DISPLAY;
        product.packagingSalesUnit = SalesUnit.PIECE;
        product.packagingPiecesPerUnit = pieces;
        em.persist(product);
        em.flush();
        return product.id;
    }

    private long supplierRow(long productId) {
        ProductSupplierEntity row = new ProductSupplierEntity();
        row.productId = productId;
        row.supplierId = supplier.id();
        row.preferred = true;
        row.active = true;
        row.exwPrice = BigDecimal.ONE;
        row.exwCurrency = "USD";
        row.createdAt = row.updatedAt = Instant.now();
        em.persist(row);
        em.flush();
        return row.id;
    }

    /** One product on a container at USD/EUR 0,92, received on the day (booked or not), alias = letter. */
    private long container(String alias, LocalDate receivedOn, long productId, int quantity, String priceUsd,
                           boolean booked, Long partnerCustomerId) {
        PurchaseOrder created = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.92"),
                BigDecimal.ZERO);
        purchases.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, productId, quantity, new BigDecimal(priceUsd), Currency.USD, null,
                        null))));
        PurchaseOrderEntity stored = em.find(PurchaseOrderEntity.class, created.id());
        stored.alias = tag + " " + alias;
        stored.status = PurchaseOrderStatus.ONTVANGEN;
        stored.stockBooked = booked;
        stored.receivedOn = receivedOn;
        stored.partnerCustomerId = partnerCustomerId;
        em.flush();
        em.clear();
        return created.id();
    }

    private void history(long productId, long orderId, String landed, String kind, String salesUnit,
                         Integer piecesPerUnit, String goods, String enrosed) {
        ProductCostHistoryEntity row = new ProductCostHistoryEntity();
        row.productId = productId;
        row.purchaseOrderId = orderId;
        row.source = "container " + orderId;
        row.landedUnitEur = new BigDecimal(landed);
        row.kind = kind;
        row.salesUnit = salesUnit;
        row.piecesPerUnit = piecesPerUnit;
        row.goodsEur = goods == null ? null : new BigDecimal(goods);
        row.enrosedEur = enrosed == null ? null : new BigDecimal(enrosed);
        row.appliedAt = Instant.now();
        row.appliedBy = "emre";
        em.persist(row);
        em.flush();
    }

    private void level(long productId, long locationId, int quantity, int loose) {
        StockLevelEntity level = em.createQuery("from StockLevelEntity where productId = ?1 and locationId = ?2",
                StockLevelEntity.class).setParameter(1, productId).setParameter(2, locationId).getResultStream()
                .findFirst().orElse(null);
        if (level == null) {
            level = new StockLevelEntity();
            level.productId = productId;
            level.locationId = locationId;
            em.persist(level);
        }
        level.quantity = quantity;
        level.loosePieces = loose;
        em.flush();
    }

    private long location(String name) {
        StockLocationEntity location = new StockLocationEntity();
        location.code = "BF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        location.name = name;
        location.kind = StockLocation.Kind.SALES_POINT;
        location.active = true;
        location.publicPickupPoint = false;
        location.publicPickupPosition = 0;
        location.position = 50;
        em.persist(location);
        em.flush();
        return location.id;
    }

    private Customer customer() {
        return customers.create(new Customer(null, "Klant " + UUID.randomUUID(), "Buyer", "batch@example.invalid",
                null, "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null,
                LocalDate.now()));
    }

    private long partnerCustomer() {
        return customer().id();
    }

    /** A settlement of the partner container that shipped {@code quantity} pieces an hour ago. */
    private void settlement(long purchaseOrderId, long productId, int quantity) {
        var created = sales.create(customer().id(), "BE", "DAP", DocumentType.FACTUUR);
        SalesOrderEntity stored = em.find(SalesOrderEntity.class, created.id());
        stored.purpose = SalesPurpose.PARTNER_SETTLEMENT;
        stored.partnerPurchaseOrderId = purchaseOrderId;
        stored.status = QuoteStatus.UITGEREIKT;
        stored.goodsShippedAt = Instant.now().minusSeconds(3600);
        SalesOrderLineEntity line = new SalesOrderLineEntity();
        line.order = stored;
        line.productId = productId;
        line.quantity = quantity;
        line.unitPriceEur = new BigDecimal("5.00");
        line.unitCostEur = new BigDecimal("4.00");
        stored.lines.add(line);
        em.flush();
        em.clear();
    }

    /** A shipped line that already carries a stock cost, for the reset. */
    private SalesOrderLineEntity stampedSalesLine(long productId) {
        var created = sales.create(customer().id(), "BE", "DAP", DocumentType.FACTUUR);
        SalesOrderEntity stored = em.find(SalesOrderEntity.class, created.id());
        SalesOrderLineEntity line = new SalesOrderLineEntity();
        line.order = stored;
        line.productId = productId;
        line.quantity = 1;
        line.unitPriceEur = new BigDecimal("5.00");
        line.unitCostEur = new BigDecimal("1.20");
        line.stockCostEur = new BigDecimal("1.2345");
        stored.lines.add(line);
        em.flush();
        return line;
    }

    private BatchCost calculated(long orderId, long productId) {
        PurchaseOrder order = purchases.get(orderId);
        LandedCost costing = purchases.calculate(order);
        return BatchCosts.of(StockBatchBackfillService.costingLine(costing, productId, 0),
                StockBatch.CostState.CALCULATED, null, null);
    }

    private static ReportRow row(Report report, long productId) {
        return report.rows().stream().filter(row -> row.productId() == productId).findFirst()
                .orElseThrow(() -> new AssertionError("no report row for " + productId));
    }

    /** "A 500/0": the container letter, pieces in and left, in the order the report lists them (own only). */
    private static List<String> shape(ReportRow row, long[] abc) {
        return row.batches().stream().filter(batch -> batch.purchaseOrderId() != null)
                .filter(batch -> batch.owner() == StockBatch.Owner.OWN)
                .map(batch -> letter(batch.purchaseOrderId(), abc) + " " + batch.piecesIn() + "/" + batch.piecesLeft())
                .toList();
    }

    private static String letter(long orderId, long[] abc) {
        for (int i = 0; i < abc.length; i++) if (abc[i] == orderId) return String.valueOf((char) ('A' + i));
        return "?";
    }

    private StockBatch batch(long productId, long orderId) {
        return batches.forProduct(productId).stream()
                .filter(batch -> batch.purchaseOrderId() != null && batch.purchaseOrderId() == orderId).findFirst()
                .orElseThrow(() -> new AssertionError("no batch of container " + orderId));
    }

    private long count(Class<?> entity) {
        return em.createQuery("select count(e) from " + entity.getSimpleName() + " e", Long.class).getSingleResult();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(expected, actual, null);
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), (message == null ? "" : message + ": ")
                + expected + " ≠ " + actual);
    }
}
