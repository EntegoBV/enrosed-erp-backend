package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.application.ProductSupplierWrite;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batches on the purchase side (batches contract §4.1, tests B2; build plan D3-D5 and §6): booking a container makes
 * Berekend batches with its supplier, version row and owner; receipt corrections follow onto the batch; "Kostprijzen
 * toepassen" writes the batches always and the card only without a newer container on it; the shipment refresh puts
 * today's calculation on Berekend batches only. Every case runs in one rolled-back transaction, its start row included.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class StockBatchPurchaseFlowTest {

    private static final BigDecimal CNY_USD = new BigDecimal("0.14");
    private static final BigDecimal USD_EUR = new BigDecimal("0.90");
    private static final LocalDate ORDERED = LocalDate.of(2026, 1, 5);
    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEP = LocalDate.of(2026, 9, 1);

    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject ProductService products;
    @Inject ProductSupplierService productSuppliers;
    @Inject ProductCostHistoryService history;
    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void bookingMakesBerekendBatchesWithTheContainersSupplierAndOwnerOnlyAfterTheStart() {
        Supplier supplier = supplier("Boek");
        ProductEntity display = product(supplier, 12);
        ProductEntity piece = product(supplier, 1);
        PurchaseOrder early = booked(supplier, AUG, null, line(piece, 40, "1.00"));
        assertTrue(batches.forPurchaseOrder(early.id()).isEmpty(), "before the start booking makes no batch");

        start();
        PurchaseOrder own = booked(supplier, SEP, null, line(display, 50, "6.00"), line(piece, 300, "1.20"));
        LandedCost costing = purchases.calculate(purchases.get(own.id()));
        List<StockBatch> rows = batches.forPurchaseOrder(own.id());
        assertEquals(2, rows.size());
        StockBatch displays = batchOf(rows, display.id);
        assertEquals(StockBatch.Kind.RECEIPT, displays.kind());
        assertEquals(StockBatch.Owner.OWN, displays.owner());
        assertEquals(StockBatch.CostState.CALCULATED, displays.costState());
        assertEquals(12, displays.unitPieces());
        assertEquals(600, displays.piecesIn(), "50 displays of 12");
        assertEquals(600, displays.piecesLeft());
        assertEquals(unit(costing, display.id), displays.unitCostEur(), "the container's calculation per display");
        assertNotNull(displays.parts());
        assertNull(displays.costAppliedAt());
        assertEquals(supplier.id(), displays.supplierId());
        assertEquals(own.number(), displays.orderNumber());
        assertEquals(SEP, displays.receivedOn());
        assertEquals(lineOf(purchases.get(own.id()), display.id).id(), displays.purchaseOrderLineId());
        assertEquals(lineOf(purchases.get(own.id()), display.id).productSupplierId(), displays.productSupplierId());
        StockBatch pieces = batchOf(rows, piece.id);
        assertEquals(300, pieces.piecesIn());
        assertEquals(unit(costing, piece.id), pieces.unitCostEur());

        PurchaseOrder partner = booked(supplier, SEP, 424_242L, line(piece, 60, "1.10"));
        StockBatch partnerBatch = batches.forPurchaseOrder(partner.id()).getFirst();
        assertEquals(StockBatch.Owner.PARTNER, partnerBatch.owner(), "a partner container's pieces are not ours");
        assertEquals(60, partnerBatch.piecesIn());
    }

    @Test @TestTransaction
    void aLineOfSupplierBsRowBooksABatchThatNamesBsRow() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = products.create(new Product(null, "KB-" + UUID.randomUUID(), "Glass dome", Dimensions.empty(),
                "Rood", null, null, a.id(), true, Barcodes.none(), null, Carton.empty(),
                new BigDecimal("2.35"), Currency.USD, new BigDecimal("0.10"), null, null, null, null, 0,
                List.of(), List.of()));
        ProductSupplier bRow = productSuppliers.add(dome.id(), new ProductSupplierWrite(b.id(), "B · dikker glas",
                "CUL-GD-10", new BigDecimal("2.10"), Currency.USD, new BigDecimal("0.15"), null, null, null, null),
                b.name());
        start();

        PurchaseOrder ofB = booked(b, SEP, null, new PurchaseOrderLine(null, dome.id(), 100, null, null, null, null));

        assertEquals(bRow.id(), purchases.get(ofB.id()).lines().getFirst().productSupplierId());
        StockBatch batch = batches.forPurchaseOrder(ofB.id()).getFirst();
        assertEquals(bRow.id(), batch.productSupplierId(), "the batch remembers B's version row");
        assertEquals(b.id(), batch.supplierId());
        assertEquals(100, batch.piecesIn());
    }

    @Test @TestTransaction
    void aReceiptCorrectionFollowsOntoItsBatchAndExtraDamageIsPinnedWithoutASecondReport() {
        Supplier supplier = supplier("Correctie");
        ProductEntity piece = product(supplier, 1);
        start();
        PurchaseOrder older = booked(supplier, AUG, null, line(piece, 100, "1.00"));
        PurchaseOrder newer = booked(supplier, SEP, null, line(piece, 100, "1.10"));
        int reportsBefore = stock.movementsForPurchaseOrder(newer.id()).size();

        correct(newer.id(), piece.id, 110, 0);
        StockBatch batch = batches.forPurchaseOrder(newer.id()).getFirst();
        assertEquals(110, batch.piecesIn(), "a count up after booking: the batch's own correction");
        assertEquals(110, batch.piecesLeft());

        correct(newer.id(), piece.id, 95, 0);
        batch = batches.forPurchaseOrder(newer.id()).getFirst();
        assertEquals(95, batch.piecesIn(), "a count down lowers what came in");
        assertEquals(95, batch.piecesLeft());
        assertEquals(StockBatch.CostState.CALCULATED, batch.costState());

        correct(newer.id(), piece.id, 95, 5);
        batch = batches.forPurchaseOrder(newer.id()).getFirst();
        assertEquals(95, batch.piecesIn());
        assertEquals(90, batch.piecesLeft(), "the extra damage leaves this container's batch");
        assertEquals(5, batch.lostPieces());
        StockBatchMove damage = batches.moves(batch.id(), 1).getFirst();
        assertEquals("DAMAGED", damage.kind());
        assertEquals(-5, damage.pieces());
        assertTrue(damage.pinned());
        assertEquals(newer.id(), damage.purchaseOrderId());
        assertEquals(100, batches.forPurchaseOrder(older.id()).getFirst().piecesLeft(), "the older batch is untouched");
        assertEquals(reportsBefore, stock.movementsForPurchaseOrder(newer.id()).size(),
                "the line already counts the damage: no second report under the container");
        assertTrue(batches.check().stream().noneMatch(row -> row.productId() == piece.id));
    }

    @Test @TestTransaction
    void applyingWritesTheBatchesAlwaysAndAnOlderContainerKeepsANewerCardUnlessItIsNamed() {
        Supplier supplier = supplier("Toepassen");
        ProductEntity piece = product(supplier, 1);
        start();
        PurchaseOrder older = booked(supplier, AUG, null, line(piece, 100, "1.00"));
        PurchaseOrder newer = booked(supplier, SEP, null, line(piece, 100, "1.50"));

        LandedCost newerCost = purchases.applyToProducts(newer.id());
        BigDecimal newerUnit = newerCost.lines().getFirst().landedUnitEur();
        StockBatch newerBatch = batches.forPurchaseOrder(newer.id()).getFirst();
        assertEquals(StockBatch.CostState.APPLIED, newerBatch.costState());
        assertEquals(newerUnit.setScale(4, RoundingMode.HALF_UP), newerBatch.unitCostEur());
        assertEquals("emre", newerBatch.costAppliedBy());
        assertNotNull(newerBatch.costAppliedAt());
        assertEquals(0, newerUnit.compareTo(products.get(piece.id).landedCostEur()));
        assertEquals(newer.number(), products.get(piece.id).landedCostSource());
        assertEquals(1, history.history(piece.id).size());
        assertEquals(StockBatch.CostState.CALCULATED, batches.forPurchaseOrder(older.id()).getFirst().costState(),
                "another container's batch is not touched");
        assertTrue(purchases.get(newer.id()).notes().contains(
                "1 product(en) bijgewerkt in de catalogus; batches: 100 stuks op voorraad."),
                purchases.get(newer.id()).notes());

        BigDecimal olderUnit = purchases.applyToProducts(older.id()).lines().getFirst().landedUnitEur();
        assertTrue(olderUnit.compareTo(newerUnit) < 0);
        StockBatch olderBatch = batches.forPurchaseOrder(older.id()).getFirst();
        assertEquals(StockBatch.CostState.APPLIED, olderBatch.costState(), "the batch always takes its container's cost");
        assertEquals(olderUnit.setScale(4, RoundingMode.HALF_UP), olderBatch.unitCostEur());
        assertEquals(0, newerUnit.compareTo(products.get(piece.id).landedCostEur()), "the newer card is kept");
        assertEquals(newer.number(), products.get(piece.id).landedCostSource());
        assertEquals(1, history.history(piece.id).size(), "a kept card writes no history row");
        assertTrue(purchases.get(older.id()).notes().contains("0 product(en) bijgewerkt in de catalogus, "
                        + "1 productkaart(en) met een nieuwere container behouden; batches: 100 stuks op voorraad."),
                purchases.get(older.id()).notes());

        purchases.applyToProducts(older.id(), new PurchaseOrderService.ApplyRequest(List.of(piece.id)));
        assertEquals(0, olderUnit.compareTo(products.get(piece.id).landedCostEur()),
                "named in the request (Ook op de productkaart): the older cost goes on the card");
        assertEquals(older.number(), products.get(piece.id).landedCostSource());
        List<ProductCostEntry> rows = history.history(piece.id);
        assertEquals(2, rows.size());
        assertEquals(older.id(), rows.getFirst().purchaseOrderId());
    }

    @Test @TestTransaction
    void theShipmentRefreshPutsTodaysCalculationOnBerekendBatchesOfEveryProductAndLeavesAppliedOnesAlone() {
        Supplier supplier = supplier("Vracht");
        ProductEntity first = product(supplier, 1);
        ProductEntity second = product(supplier, 12);
        start();
        PurchaseOrder applied = booked(supplier, AUG, null, line(first, 100, "1.00"));
        purchases.applyToProducts(applied.id());
        PurchaseOrder calculated = booked(supplier, SEP, null, line(first, 200, "1.20"), line(second, 30, "6.00"));
        BigDecimal appliedBefore = batches.forPurchaseOrder(applied.id()).getFirst().unitCostEur();
        BigDecimal firstBefore = batchOf(batches.forPurchaseOrder(calculated.id()), first.id).unitCostEur();
        BigDecimal secondBefore = batchOf(batches.forPurchaseOrder(calculated.id()), second.id).unitCostEur();

        purchases.refreshCalculatedBatches(List.of(first.id, second.id));
        assertEquals(firstBefore, batchOf(batches.forPurchaseOrder(calculated.id()), first.id).unitCostEur(),
                "nothing changed: the same figure");

        for (long id : List.of(applied.id(), calculated.id())) {
            entities.find(PurchaseOrderEntity.class, id).freightUsd = new BigDecimal("3000");
        }
        entities.flush();
        LandedCost now = purchases.calculate(purchases.get(calculated.id()));
        purchases.refreshCalculatedBatches(List.of(first.id, second.id, first.id));

        StockBatch firstNow = batchOf(batches.forPurchaseOrder(calculated.id()), first.id);
        StockBatch secondNow = batchOf(batches.forPurchaseOrder(calculated.id()), second.id);
        assertEquals(unit(now, first.id), firstNow.unitCostEur(), "one shipment, both products of the container");
        assertEquals(unit(now, second.id), secondNow.unitCostEur());
        assertTrue(firstNow.unitCostEur().compareTo(firstBefore) > 0, "the freight went up");
        assertTrue(secondNow.unitCostEur().compareTo(secondBefore) > 0);
        assertEquals(StockBatch.CostState.CALCULATED, firstNow.costState());
        assertNull(firstNow.costAppliedAt());
        StockBatch appliedNow = batches.forPurchaseOrder(applied.id()).getFirst();
        assertEquals(StockBatch.CostState.APPLIED, appliedNow.costState());
        assertEquals(appliedBefore, appliedNow.unitCostEur(), "an applied batch never changes by itself");
    }

    @Test @TestTransaction
    void withBatchesStartedALiveProductWithOpenPublicationPointsStillTakesTheContainerCost() {
        Supplier supplier = supplier("Live");
        ProductEntity live = product(supplier, 12);
        live.websiteStatus = PublicationState.PUBLISHED;
        entities.flush();
        assertFalse(products.get(live.id).publicationIssues().isEmpty(), "the live product has open points");
        start();
        PurchaseOrder order = booked(supplier, SEP, null, line(live, 18, "8.50"));

        LandedCost result = purchases.applyToProducts(order.id());

        BigDecimal unit = result.lines().getFirst().landedUnitEur();
        StockBatch batch = batches.forPurchaseOrder(order.id()).getFirst();
        assertEquals(StockBatch.CostState.APPLIED, batch.costState());
        assertEquals(unit.setScale(4, RoundingMode.HALF_UP), batch.unitCostEur());
        assertEquals(0, unit.compareTo(products.get(live.id).landedCostEur()), "the card is written");
        assertEquals(order.number(), products.get(live.id).landedCostSource());
        assertEquals(1, history.history(live.id).size());
        assertEquals(PublicationState.PUBLISHED,
                products.get(live.id).publicationState(CatalogChannel.WEBSITE), "still live");
    }

    /* ---------------------------------------------------------- helpers */

    private void start() {
        batches.recordStart(0, 0, 0, 0, "test");
    }

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, "Batch " + name + " " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
    }

    /** A product of this supplier with a real carton, sold per display of {@code unitPieces} (1: per piece). */
    private ProductEntity product(Supplier supplier, int unitPieces) {
        ProductEntity product = new ProductEntity();
        product.sku = "KF-" + UUID.randomUUID();
        product.name = "Batch flow rose";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
        if (unitPieces > 1) {
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingPiecesPerUnit = unitPieces;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
        }
        entities.persist(product);
        entities.flush();
        return product;
    }

    private static PurchaseOrderLine line(ProductEntity product, int quantity, String exwUsd) {
        return new PurchaseOrderLine(null, product.id, quantity, new BigDecimal(exwUsd), Currency.USD, null, null);
    }

    /** Ordered on {@link #ORDERED}, with costs on every part, received on {@code receivedOn} and booked into stock. */
    private PurchaseOrder booked(Supplier supplier, LocalDate receivedOn, Long partnerCustomerId,
                                 PurchaseOrderLine... lines) {
        PurchaseOrder concept = purchases.create(supplier.id(), CNY_USD, USD_EUR, BigDecimal.TEN);
        long id = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false,
                null, List.of(lines))).order().id();
        PurchaseOrderEntity row = entities.find(PurchaseOrderEntity.class, id);
        row.status = PurchaseOrderStatus.BESTELD;
        row.orderDate = ORDERED;
        row.partnerCustomerId = partnerCustomerId;
        row.freightUsd = new BigDecimal("1000");
        row.destinationCostsEur = new BigDecimal("300");
        row.extraRevenueEur = new BigDecimal("120");
        entities.flush();
        PurchaseOrder received = purchases.receive(id, new PurchaseOrderService.Receipt(List.of(), true, null,
                receivedOn, null));
        assertTrue(received.isStockBooked());
        return received;
    }

    /** Saves the received container with this product's count and damage corrected. */
    private void correct(long orderId, long productId, int quantity, int damaged) {
        PurchaseOrder current = purchases.get(orderId);
        List<PurchaseOrderLine> lines = current.lines().stream().map(line -> !Long.valueOf(productId)
                .equals(line.productId()) ? line : new PurchaseOrderLine(line.id(), line.productId(), quantity,
                line.exwPrice(), line.exwCurrency(), line.extraUnitCost(), line.orderedQuantity(), line.priceBasis(),
                damaged, line.receiptUnitValueEur(), line.issueNote(), line.extraShareEur(), line.productSupplierId(),
                line.supplierVersionLabel(), line.supplierReference())).toList();
        purchases.update(orderId, current.withReceipt(current.status(), current.receivedOn(), current.paidTotalEur(),
                current.isStockBooked(), current.notes(), lines));
        entities.flush();
    }

    private static StockBatch batchOf(List<StockBatch> rows, long productId) {
        return rows.stream().filter(batch -> batch.productId() == productId).findFirst()
                .orElseThrow(() -> new AssertionError("no batch for product " + productId));
    }

    private static PurchaseOrderLine lineOf(PurchaseOrder order, long productId) {
        return order.lines().stream().filter(line -> Long.valueOf(productId).equals(line.productId())).findFirst()
                .orElseThrow();
    }

    private static BigDecimal unit(LandedCost costing, long productId) {
        return costing.lines().stream().filter(line -> Long.valueOf(productId).equals(line.productId())).findFirst()
                .orElseThrow().landedUnitEur().setScale(4, RoundingMode.HALF_UP);
    }
}
