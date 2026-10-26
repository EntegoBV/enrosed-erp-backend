package be.enrosed.inventory;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.catalog.domain.StockBatchTotals;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.application.StockBatchBackfillService;
import be.enrosed.inventory.application.StockBatchViews;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.PartnerFinancingService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.CreditReason;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One product's life with batches, end to end (batches contract §10, B3 after B2; build plan §7.2 R2): "Batches
 * opbouwen", a container received and booked later, a shipment that first puts today's calculation on the Berekend
 * batches, "Kostprijzen toepassen", a second shipment over two batches, a credit note with a return, a recount down and
 * up. After every step the batches hold exactly the stock, and every margin reads the stock cost the shipment wrote.
 * Everything runs in one rolled-back transaction: the stock of the shared test database is set to zero first (as in
 * {@code StockBatchBackfillTest}), so {@code check()} has to be empty for every product, not only for these.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class StockBatchFlowTest {

    private static final LocalDate ORDERED = LocalDate.of(2026, 1, 5);
    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEP = LocalDate.of(2026, 9, 1);
    private static final BigDecimal EXPECTED = new BigDecimal("2.5000");

    @Inject StockBatchBackfillService backfill;
    @Inject StockBatchViews views;
    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject ProductService products;
    @Inject ProductCostHistoryService history;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject PartnerFinancingService financing;
    @Inject EntityManager em;

    @Test @TestTransaction
    void receiveBookShipApplyShipReturnAndRecountKeepTheBatchesEqualToTheStockAndTheMarginsOnTheStockCost() {
        quiet();
        Supplier supplier = suppliers.save(new Supplier(null, "Flow " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        long rose = product(supplier);
        long vase = product(supplier);
        long customer = customer();
        long main = stock.mainLocation().id();

        /* Batches opbouwen: nothing on the shelf, so nothing to build; from here on every hook writes. */
        StockBatchBackfillService.Report report = backfill.start();
        assertNotNull(report.startedAt());
        assertTrue(batches.active());
        assertTrue(batches.forProduct(rose).isEmpty());

        /* Receive: container A is in (100 roses, 4 broken; 50 vases), its stock not booked yet. */
        long a = ordered(supplier, line(rose, 100, "1.00"), line(vase, 50, "2.00"));
        PurchaseOrder received = purchases.receive(a, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(rose, 100, 4)), false, null, AUG, null));
        assertEquals(PurchaseOrderStatus.ONTVANGEN, received.status());
        assertFalse(received.isStockBooked());
        assertTrue(batches.forPurchaseOrder(a).isEmpty(), "received but not booked: no batch yet");
        assertFalse(views.forPurchaseOrder(a).booked());

        /* Book: one Berekend batch per line with the usable pieces at the container's calculation of today. */
        purchases.bookStock(a);
        LandedCost booking = purchases.calculate(purchases.get(a));
        StockBatch roseA = batchOf(a, rose);
        assertEquals(StockBatch.Kind.RECEIPT, roseA.kind());
        assertEquals(StockBatch.CostState.CALCULATED, roseA.costState());
        assertEquals(96, roseA.piecesIn(), "the 4 broken pieces never reached the shelf");
        assertEquals(96, roseA.piecesLeft());
        assertEquals(unit(booking, rose), roseA.unitCostEur());
        assertEquals(supplier.id(), roseA.supplierId());
        assertEquals(lineOf(purchases.get(a), rose).productSupplierId(), roseA.productSupplierId());
        assertEquals(AUG, roseA.receivedOn());
        assertEquals(50, batchOf(a, vase).piecesLeft());
        assertEquals(96, stock.quantityAt(rose, main));
        assertTrue(views.forPurchaseOrder(a).booked());
        assertClean("after booking");

        long b = ordered(supplier, line(rose, 100, "1.50"));
        purchases.receive(b, new PurchaseOrderService.Receipt(List.of(), true, null, SEP, null));
        BigDecimal costB = batchOf(b, rose).unitCostEur();
        assertEquals(unit(purchases.calculate(purchases.get(b)), rose), costB);
        assertEquals(List.of(96, 100), lefts(rose), "A first, then B");

        /* The freight bill of A comes in after the booking: both of A's batches are behind today's calculation. */
        em.find(PurchaseOrderEntity.class, a).freightUsd = new BigDecimal("4000");
        em.flush();
        LandedCost withFreight = purchases.calculate(purchases.get(a));
        BigDecimal roseToday = unit(withFreight, rose);
        BigDecimal vaseToday = unit(withFreight, vase);
        assertTrue(roseToday.compareTo(roseA.unitCostEur()) > 0, "the freight went up");
        assertEquals(2, views.forPurchaseOrder(a).costs().stale(), "both live batches of A show the newer figure");

        /* Ship invoice 1: the refresh puts today's calculation on A's batches first, the stamp takes it. */
        SalesOrderEntity first = draft(customer);
        line(first, rose, 60, "6.00");
        line(first, vase, 20, "9.00");
        SalesOrder shipped1 = sales.shipGoods(issued(first).id());
        assertEquals(roseToday, lineOf(shipped1, rose).stockCostEur(), "60 roses, all from A at today's calculation");
        assertEquals(vaseToday, lineOf(shipped1, vase).stockCostEur(), "one shipment refreshes both products of A");
        assertEquals(roseToday, batchOf(a, rose).unitCostEur());
        assertEquals(StockBatch.CostState.CALCULATED, batchOf(a, rose).costState());
        assertEquals(0, views.forPurchaseOrder(a).costs().stale());
        assertEquals(List.of(36, 100), lefts(rose));
        assertEquals(30, batchOf(a, vase).piecesLeft());
        assertMarginOnStock(shipped1, rose, roseToday);
        assertSheet(shipped1.id(), rose, 60, roseToday);
        assertClean("after the first shipment");

        /* Apply A after its destination invoice: the batches become Toegepast, the card and its history follow. */
        em.find(PurchaseOrderEntity.class, a).destinationCostsEur = new BigDecimal("900");
        em.flush();
        BigDecimal applied = purchases.applyToProducts(a).lines().stream()
                .filter(cost -> Long.valueOf(rose).equals(cost.productId())).findFirst().orElseThrow()
                .landedUnitEur().setScale(4, RoundingMode.HALF_UP);
        assertNotEquals(roseToday, applied, "the destination costs changed the figure once more");
        StockBatch appliedA = batchOf(a, rose);
        assertEquals(StockBatch.CostState.APPLIED, appliedA.costState());
        assertEquals(applied, appliedA.unitCostEur());
        assertEquals("emre", appliedA.costAppliedBy());
        assertEquals(0, applied.compareTo(products.get(rose).landedCostEur().setScale(4, RoundingMode.HALF_UP)),
                "no newer container on the card: the card takes A's cost");
        assertEquals(1, history.history(rose).size());
        assertEquals(StockBatch.CostState.CALCULATED, batchOf(b, rose).costState(), "B is not applied");
        assertEquals(roseToday, lineOf(sales.get(shipped1.id()), rose).stockCostEur(),
                "a shipped line keeps the cost its pieces left with");
        assertSheet(shipped1.id(), rose, 60, roseToday);
        assertMarginOnStock(sales.get(shipped1.id()), rose, roseToday);

        /* Ship invoice 2: 36 from A at the applied cost, 44 from B at its calculation. */
        SalesOrderEntity second = draft(customer);
        line(second, rose, 80, "6.00");
        SalesOrder shipped2 = sales.shipGoods(issued(second).id());
        BigDecimal weighted = applied.multiply(BigDecimal.valueOf(36)).add(costB.multiply(BigDecimal.valueOf(44)))
                .divide(BigDecimal.valueOf(80), 4, RoundingMode.HALF_UP);
        assertEquals(weighted, lineOf(shipped2, rose).stockCostEur(), "(36 × A + 44 × B) / 80");
        assertEquals(List.of(0, 56), lefts(rose));
        assertEquals(costB, batchOf(b, rose).unitCostEur(), "B's container did not change: the same figure");
        assertMarginOnStock(shipped2, rose, weighted);
        StockBatchViews.SaleLine sheet = assertSheet(shipped2.id(), rose, 80, weighted);
        assertEquals(List.of(36, 44), sheet.batches().stream().map(StockBatchViews.SaleBatch::pieces).toList());
        assertEquals(List.of(applied, costB),
                sheet.batches().stream().map(StockBatchViews.SaleBatch::unitCostEur).toList());
        assertClean("after the second shipment");

        /* Credit note with a return of 30: it reverses the stock cost; the pieces go back to the batch taken last. */
        SalesOrder creditNote = sales.createCreditNote(shipped2.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.RETURN, List.of(new SalesOrderService.CreditLine(rose, 30, null)), List.of(), false,
                null));
        assertEquals(weighted, lineOf(creditNote, rose).unitCostEur(), "the credit note copies the stock cost");
        assertNull(lineOf(creditNote, rose).stockCostEur());
        sales.issueInvoice(creditNote.id());
        sales.returnGoods(creditNote.id());
        assertEquals(List.of(0, 86), lefts(rose), "B, the most recent batch the invoice took, capped at its 44");
        StockBatchMove back = batches.moves(batchOf(b, rose).id(), 1).getFirst();
        assertEquals("SALE_RETURN", back.kind());
        assertEquals(30, back.pieces());
        assertEquals(86, stock.quantityAt(rose, main));
        assertClean("after the return");

        /* Recount: 80 counted takes 6 FIFO (A is empty, so B); 83 the next count adds a Telling batch at the card. */
        stock.setLevel(rose, main, 80, StockMovement.Kind.STOCKTAKE, "Telling flow");
        StockBatch countedB = batchOf(b, rose);
        assertEquals(80, countedB.piecesLeft());
        assertEquals(6, countedB.lostPieces(), "a count down is a loss on the batch it came from");
        assertEquals(0, batchOf(a, rose).piecesLeft());
        assertClean("after the count down");
        stock.setLevel(rose, main, 83, StockMovement.Kind.STOCKTAKE, "Telling flow");
        StockBatch found = batches.forProduct(rose).stream().filter(batch -> batch.kind() == StockBatch.Kind.COUNT)
                .findFirst().orElseThrow();
        assertEquals(3, found.piecesLeft());
        assertEquals(StockBatch.CostState.CARD, found.costState());
        assertEquals(applied, found.unitCostEur(), "found pieces count at the card cost, A's since the apply");

        /* The end: the batches hold the shelf for every product, the views say the same, the margins stay. */
        assertTrue(batches.check().isEmpty(), () -> "check: " + batches.check());
        assertTrue(views.check().isEmpty());
        assertEquals(0, views.status().differences());
        StockBatchViews.ProductSummary summary = views.forProduct(rose).summary();
        assertEquals(83, summary.stockPieces());
        assertEquals(83, summary.batchPieces());
        assertEquals(0, summary.unassignedPieces());
        assertEquals(0, summary.differencePieces());
        BigDecimal value = costB.multiply(BigDecimal.valueOf(80)).add(applied.multiply(BigDecimal.valueOf(3)));
        assertTrue(summary.valueEur().subtract(value).abs().compareTo(new BigDecimal("0.02")) <= 0,
                "80 × B + 3 × card = " + value + ", shown " + summary.valueEur());
        assertEquals(costB, summary.nextUnitCostEur(), "B goes next");
        assertEquals(30, stock.quantityAt(vase, main));
        assertEquals(30, batchOf(a, vase).piecesLeft());
        assertMarginOnStock(sales.get(shipped1.id()), rose, roseToday);
        assertMarginOnStock(sales.get(shipped2.id()), rose, weighted);
    }

    /**
     * The way back of build plan §7.2 R2: while paused a shipment still books its stock, but no batch moves and no
     * stamp is written, so the check shows the drift; after "Hervatten", "Herstellen" books it away and the next
     * shipment stamps again.
     */
    @Test @TestTransaction
    void aShipmentWhilePausedShowsAsADifferenceThatHerstellenBooksAwayAfterResuming() {
        quiet();
        Supplier supplier = suppliers.save(new Supplier(null, "Flow pause " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        long rose = product(supplier);
        long customer = customer();
        long main = stock.mainLocation().id();
        backfill.start();
        long a = ordered(supplier, line(rose, 100, "1.00"));
        purchases.receive(a, new PurchaseOrderService.Receipt(List.of(), true, null, AUG, null));
        BigDecimal costA = batchOf(a, rose).unitCostEur();
        SalesOrderEntity before = draft(customer);
        line(before, rose, 30, "6.00");
        assertEquals(costA, lineOf(sales.shipGoods(issued(before).id()), rose).stockCostEur());
        assertEquals(70, batchOf(a, rose).piecesLeft());

        batches.pause();
        assertTrue(views.status().paused());
        SalesOrderEntity paused = draft(customer);
        line(paused, rose, 20, "6.00");
        SalesOrder shippedPaused = sales.shipGoods(issued(paused).id());
        assertNotNull(shippedPaused.goodsShippedAt(), "the shipment itself is never blocked");
        assertNull(lineOf(shippedPaused, rose).stockCostEur(), "paused: no stamp");
        assertEquals(EXPECTED, sales.price(shippedPaused).lines().getFirst().landedUnitCost(),
                "paused: the margin keeps the quote's cost");
        assertEquals(50, stock.quantityAt(rose, main));
        assertEquals(70, batchOf(a, rose).piecesLeft(), "paused: the batch is not touched");
        List<StockBatchViews.CheckRow> drift = views.check();
        assertEquals(1, drift.size(), () -> "drift: " + drift);
        assertEquals(rose, drift.getFirst().productId());
        assertEquals(50, drift.getFirst().stockPieces());
        assertEquals(70, drift.getFirst().batchPieces());
        assertEquals(20, drift.getFirst().differencePieces());
        assertEquals(1, views.status().differences());
        assertEquals("Batches staan op pauze.", assertThrows(BusinessRuleException.class,
                () -> views.repair(List.of(rose))).getMessage(),
                "Herstellen waits for Hervatten");

        batches.resume();
        assertTrue(views.repair(List.of(rose)).isEmpty(), "Herstellen books the difference");
        assertEquals(50, batchOf(a, rose).piecesLeft());
        StockBatchMove repair = batches.moves(batchOf(a, rose).id(), 1).getFirst();
        assertEquals("REPAIR", repair.kind());
        assertEquals(-20, repair.pieces());
        assertClean("after Herstellen");

        SalesOrderEntity after = draft(customer);
        line(after, rose, 10, "6.00");
        SalesOrder shippedAfter = sales.shipGoods(issued(after).id());
        assertMarginOnStock(shippedAfter, rose, costA);
        assertEquals(40, batchOf(a, rose).piecesLeft());
        assertClean("after resuming");
    }

    /* ---------------------------------------------------------------- checks */

    /** The batches of the whole (quieted) database hold exactly its stock, and no take is left unassigned. */
    private void assertClean(String when) {
        em.flush();
        List<StockBatchTotals> rows = batches.check();
        assertTrue(rows.isEmpty(), when + ": " + rows);
    }

    /**
     * The margin of the shipped invoice's line reads the stock cost: the quote's cost stays the expectation, the
     * landed cost, the line cost, the margin and Analyses' recognised cost follow the stamp.
     */
    private void assertMarginOnStock(SalesOrder shipped, long productId, BigDecimal stockCost) {
        assertEquals(stockCost, lineOf(shipped, productId).stockCostEur());
        assertNotEquals(0, EXPECTED.compareTo(stockCost), "the test needs a stock cost other than the expected one");
        PricedOrder priced = sales.price(shipped);
        PricedOrder.Line line = priced.lines().stream().filter(candidate -> candidate.productId() == productId)
                .findFirst().orElseThrow();
        assertEquals(EXPECTED, line.expectedUnitCost(), "Verwacht op de factuur");
        assertEquals(stockCost, line.stockUnitCost());
        assertEquals(stockCost, line.landedUnitCost(), "the margin uses the stock cost");
        BigDecimal cost = stockCost.multiply(BigDecimal.valueOf(lineOf(shipped, productId).quantity()));
        assertTrue(line.costTotal().subtract(cost).abs().compareTo(new BigDecimal("0.01")) <= 0,
                "quantity × stock cost = " + cost + ", line cost " + line.costTotal());
        assertEquals(0, line.marginEur().compareTo(line.net().subtract(line.costTotal())));
        assertEquals(0, financing.accounting(shipped, priced).recognizedCostEur()
                .compareTo(priced.totals().costTotal()), "Analyses' recognised cost follows");
    }

    /** The invoice's cost sheet: what the pieces cost per batch, summing to the stamp. */
    private StockBatchViews.SaleLine assertSheet(long salesOrderId, long productId, int pieces, BigDecimal stamp) {
        StockBatchViews.SaleCost sheet = views.forSalesOrder(salesOrderId);
        assertTrue(sheet.shipped());
        StockBatchViews.SaleLine line = sheet.lines().stream().filter(candidate -> candidate.productId() == productId)
                .findFirst().orElseThrow();
        assertEquals(pieces, line.pieces());
        assertEquals(0, line.withoutBatchPieces());
        assertEquals(0, line.withoutCostPieces());
        assertFalse(line.exclEnrosed());
        BigDecimal sum = BigDecimal.ZERO;
        for (StockBatchViews.SaleBatch batch : line.batches()) {
            sum = sum.add(batch.unitCostEur().multiply(BigDecimal.valueOf(batch.pieces())));
        }
        assertEquals(stamp, sum.divide(BigDecimal.valueOf(pieces), 4, RoundingMode.HALF_UP),
                "the sheet sums to the margin's cost");
        return line;
    }

    /* --------------------------------------------------------------- helpers */

    /** Nothing on any shelf, no batch, no run and no booked container: the database the start sees. */
    private void quiet() {
        em.createQuery("delete from StockBatchMoveEntity").executeUpdate();
        em.createQuery("delete from StockBatchEntity").executeUpdate();
        em.createQuery("delete from StockBatchRunEntity").executeUpdate();
        em.createQuery("update " + PurchaseOrderEntity.class.getName() + " set status = ?1")
                .setParameter(1, PurchaseOrderStatus.CONCEPT).executeUpdate();
        em.createQuery("update StockLevelEntity set quantity = 0, loosePieces = 0").executeUpdate();
        em.clear();
    }

    /** A product of this supplier sold per piece, with a real carton and no card cost yet. */
    private long product(Supplier supplier) {
        ProductEntity product = new ProductEntity();
        product.sku = "SBF-" + UUID.randomUUID();
        product.name = "Batch flow rose";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
        product.cartonWeightKg = BigDecimal.ONE;
        em.persist(product);
        em.flush();
        return product.id;
    }

    private static PurchaseOrderLine line(long productId, int quantity, String exwUsd) {
        return new PurchaseOrderLine(null, productId, quantity, new BigDecimal(exwUsd), Currency.USD, null, null);
    }

    /** A container of this supplier, ordered, with costs on every part. */
    private long ordered(Supplier supplier, PurchaseOrderLine... lines) {
        PurchaseOrder concept = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        long id = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false,
                null, List.of(lines))).order().id();
        PurchaseOrderEntity row = em.find(PurchaseOrderEntity.class, id);
        row.status = PurchaseOrderStatus.BESTELD;
        row.orderDate = ORDERED;
        row.freightUsd = new BigDecimal("1000");
        row.destinationCostsEur = new BigDecimal("300");
        row.extraRevenueEur = new BigDecimal("120");
        em.flush();
        return id;
    }

    private long customer() {
        return customers.create(new Customer(null, "Batch flow " + UUID.randomUUID(), "Buyer",
                "batch-flow@example.invalid", null, "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen",
                "DAP", null, null, LocalDate.now())).id();
    }

    private SalesOrderEntity draft(long customerId) {
        SalesOrder created = sales.create(customerId, "BE", "DAP", DocumentType.FACTUUR);
        return em.find(SalesOrderEntity.class, created.id());
    }

    /** A line quoted at the expected cost of {@link #EXPECTED}. */
    private static void line(SalesOrderEntity order, long productId, int quantity, String price) {
        SalesOrderLineEntity line = new SalesOrderLineEntity();
        line.order = order;
        line.productId = productId;
        line.quantity = quantity;
        line.unitPriceEur = new BigDecimal(price);
        line.unitCostEur = EXPECTED;
        order.lines.add(line);
    }

    /** Issued with a fixed freight, so it can be shipped. */
    private SalesOrder issued(SalesOrderEntity order) {
        order.freightPricingStrategy = FreightPricingStrategy.FIXED;
        order.manualFreightEur = new BigDecimal("120.00");
        order.freight = FreightState.AANGEVULD;
        order.status = QuoteStatus.UITGEREIKT;
        em.flush();
        em.clear();
        return sales.get(order.id);
    }

    private StockBatch batchOf(long purchaseOrderId, long productId) {
        return batches.forPurchaseOrder(purchaseOrderId).stream().filter(batch -> batch.productId() == productId)
                .findFirst().orElseThrow(() -> new AssertionError("no batch for product " + productId));
    }

    /** Pieces left per container batch of the product, oldest first. */
    private List<Integer> lefts(long productId) {
        return em.createQuery("select b.piecesLeft from StockBatchEntity b where b.productId = :p "
                + "and b.purchaseOrderId is not null order by b.receivedOn, b.id", Integer.class)
                .setParameter("p", productId).getResultList();
    }

    private static PurchaseOrderLine lineOf(PurchaseOrder order, long productId) {
        return order.lines().stream().filter(line -> Long.valueOf(productId).equals(line.productId())).findFirst()
                .orElseThrow();
    }

    private static SalesOrderLine lineOf(SalesOrder order, long productId) {
        return order.lines().stream().filter(line -> productId == line.productId()).findFirst().orElseThrow();
    }

    private static BigDecimal unit(LandedCost costing, long productId) {
        return costing.lines().stream().filter(line -> Long.valueOf(productId).equals(line.productId())).findFirst()
                .orElseThrow().landedUnitEur().setScale(4, RoundingMode.HALF_UP);
    }
}
