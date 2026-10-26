package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.BatchSource;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stock cost of a shipped sales line (batches contract §4.3, tests B2): at shipment the server writes what the
 * pieces that left cost, FIFO over the batches, and the margin reads it from then on; the quote snapshot stays visible
 * as the expected cost. Container-cost documents, partner deals and a paused start keep today's margin. Every case runs
 * in one rolled-back transaction, its start row included.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class SalesStockCostTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 10);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 10);
    private static final LocalDate SEP = LocalDate.of(2026, 9, 1);

    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject PartnerFinancingService financing;
    @Inject SalesRepositories.Orders orders;
    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    /** Container and line ids of the hand-booked batches, far above anything the shared test database holds. */
    private final long base = 970_000_000L + ThreadLocalRandom.current().nextLong(1_000_000) * 100;

    @Test @TestTransaction
    void aStandardInvoiceShippedOverTwoBatchesTakesTheWeightedFifoCostAndTheMarginFollowsIt() {
        long customer = customer();
        long rose = product(null, "1.0000");
        long vase = product(null, "1.0000");
        start();
        receive(rose, 1, JAN, 120, cost("2.0000", null));
        receive(rose, 2, MAR, 120, cost("3.0000", null));
        receive(vase, 3, JAN, 120, cost("4.0000", null));
        SalesOrderEntity draft = draft(customer);
        line(draft, rose, 180, "8.45", "5.10");
        line(draft, vase, 144, "9.00", "5.40");
        SalesOrder invoice = issued(draft);

        SalesOrder shipped = sales.shipGoods(invoice.id());

        assertNotNull(shipped.goodsShippedAt());
        assertEquals(new BigDecimal("2.3333"), lineOf(shipped, rose).stockCostEur(),
                "the answer of the shipment already carries it: (120 × 2 + 60 × 3) / 180");
        assertEquals(new BigDecimal("4.2333"), lineOf(shipped, vase).stockCostEur(),
                "24 pieces without a batch count at the line's expected cost: (120 × 4 + 24 × 5,40) / 144");
        assertEquals(List.of(0, 60), lefts(rose), "the oldest batch went first");
        PricedOrder priced = sales.price(shipped);
        PricedOrder.Line roseLine = pricedOf(priced, rose);
        assertEquals(new BigDecimal("5.1000"), roseLine.expectedUnitCost(), "the quote snapshot stays the expectation");
        assertEquals(new BigDecimal("2.3333"), roseLine.stockUnitCost());
        assertEquals(new BigDecimal("2.3333"), roseLine.landedUnitCost(), "the margin uses the stock cost");
        assertTrue(roseLine.costTotal().subtract(new BigDecimal("419.994")).abs().compareTo(new BigDecimal("0.01")) <= 0,
                "180 × 2,3333: " + roseLine.costTotal());
        assertEquals(0, roseLine.marginEur().compareTo(roseLine.net().subtract(roseLine.costTotal())));
        assertEquals(new BigDecimal("4.2333"), pricedOf(priced, vase).landedUnitCost());
        assertEquals(0, new BigDecimal("1029.59").compareTo(priced.totals().costTotal()),
                "180 × 2,3333 + 144 × 4,2333 = 1029,5892");
        assertEquals(0, financing.accounting(shipped, priced).recognizedCostEur()
                .compareTo(priced.totals().costTotal()), "Analyses' recognised cost follows");

        /* A save of the shipped document never writes the stamp, so it can never wipe it either. */
        List<SalesOrderLine> plain = shipped.lines().stream().map(line -> new SalesOrderLine(line.id(),
                line.productId(), line.quantity(), line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek(),
                line.unitCostEur(), line.unavailable(), line.requestedQuantity(), line.unitsPerOrderStep())).toList();
        orders.save(shipped.withLinesAndPallets(plain, shipped.pallets()));
        em.flush();
        em.clear();
        assertEquals(new BigDecimal("2.3333"), lineOf(sales.get(invoice.id()), rose).stockCostEur());
    }

    @Test @TestTransaction
    void aBerekendBatchWhoseContainerGotFreightAfterBookingIsStampedWithTodaysCalculation() {
        long customer = customer();
        Supplier supplier = supplier();
        long dome = product(supplier.id(), "1.0000");
        start();
        PurchaseOrder container = booked(supplier, null, dome, 120);
        BigDecimal booked = batches.forPurchaseOrder(container.id()).getFirst().unitCostEur();
        em.find(PurchaseOrderEntity.class, container.id()).freightUsd = new BigDecimal("4000");
        em.flush();
        LandedCost today = purchases.calculate(purchases.get(container.id()));
        BigDecimal todays = today.lines().getFirst().landedUnitEur().setScale(4, RoundingMode.HALF_UP);
        assertTrue(todays.compareTo(booked) > 0, "the freight came after the booking");
        SalesOrderEntity draft = draft(customer);
        line(draft, dome, 60, "9.00", "1.00");

        SalesOrder shipped = sales.shipGoods(issued(draft).id());

        assertEquals(todays, lineOf(shipped, dome).stockCostEur(), "not the preliminary cost of the booking");
        StockBatch batch = batches.forPurchaseOrder(container.id()).getFirst();
        assertEquals(StockBatch.CostState.CALCULATED, batch.costState());
        assertEquals(todays, batch.unitCostEur());
        assertEquals(60, batch.piecesLeft());
    }

    @Test @TestTransaction
    void aContainerSaleTakesItsOwnContainerFirstAtTheCostWithoutEnrosedKost() {
        long customer = customer();
        Supplier supplier = supplier();
        long bowl = product(supplier.id(), "1.0000");
        start();
        receive(bowl, 4, JAN, 120, cost("2.0000", null));
        PurchaseOrder container = booked(supplier, null, bowl, 120);
        purchases.applyToProducts(container.id());
        StockBatch applied = batches.forPurchaseOrder(container.id()).getFirst();
        assertTrue(applied.unitCostExclEnrosedEur().compareTo(applied.unitCostEur()) < 0, "the container carries Enrosed kost");
        SalesOrderEntity draft = draft(customer);
        draft.sourcePurchaseOrderId = container.id();
        line(draft, bowl, 60, "9.00", applied.unitCostEur().toPlainString());

        SalesOrder shipped = sales.shipGoods(issued(draft).id());

        assertEquals(applied.unitCostExclEnrosedEur(), lineOf(shipped, bowl).stockCostEur());
        assertEquals(60, batches.forPurchaseOrder(container.id()).getFirst().piecesLeft(), "pinned to its container");
        assertEquals(120, oldest(bowl).piecesLeft(), "the older own batch is left alone");
    }

    @Test @TestTransaction
    void containerCostDocumentsPartnerSettlementsAndAPausedStartKeepTheirOwnCost() {
        long customer = customer();
        Supplier supplier = supplier();
        long atCost = product(null, "1.0000");
        long partnerGoods = product(supplier.id(), "1.0000");
        long paused = product(null, "1.0000");
        start();
        receive(atCost, 5, JAN, 120, cost("2.0000", null));
        receive(partnerGoods, 6, JAN, 120, cost("2.0000", null));
        receive(paused, 7, JAN, 120, cost("2.0000", null));

        SalesOrderEntity containerCost = draft(customer);
        containerCost.markupMode = MarkupMode.CONTAINER_COST;
        line(containerCost, atCost, 24, null, "2.5000");
        SalesOrder atCostInvoice = issued(containerCost);
        PricedOrder before = sales.price(atCostInvoice);
        SalesOrder atCostShipped = sales.shipGoods(atCostInvoice.id());
        assertNull(lineOf(atCostShipped, atCost).stockCostEur(), "container cost keeps its own rule");
        PricedOrder after = sales.price(atCostShipped);
        assertEquals(pricedOf(before, atCost).unitPrice(), pricedOf(after, atCost).unitPrice());
        assertEquals(pricedOf(before, atCost).landedUnitCost(), pricedOf(after, atCost).landedUnitCost());
        assertNull(pricedOf(after, atCost).stockUnitCost());
        assertEquals(0, before.totals().marginEur().compareTo(after.totals().marginEur()));
        assertEquals(96, oldest(atCost).piecesLeft(), "the pieces still leave the batches");

        PurchaseOrder partnerContainer = booked(supplier, 515_151L, partnerGoods, 120);
        SalesOrderEntity settlement = draft(customer);
        settlement.purpose = SalesPurpose.PARTNER_SETTLEMENT;
        settlement.partnerSettlement = true;
        settlement.partnerPurchaseOrderId = partnerContainer.id();
        settlement.partnerSharePct = new BigDecimal("50");
        line(settlement, partnerGoods, 36, "4.00", "2.0000");
        SalesOrder settled = sales.shipGoods(issued(settlement).id());
        assertNull(lineOf(settled, partnerGoods).stockCostEur(), "a partner deal keeps its own margin");
        StockBatch partnerBatch = batches.forPurchaseOrder(partnerContainer.id()).getFirst();
        assertEquals(StockBatch.Owner.PARTNER, partnerBatch.owner());
        assertEquals(84, partnerBatch.piecesLeft(), "the settlement is pinned to the partner batch");
        assertEquals(120, oldest(partnerGoods).piecesLeft(), "own pieces stay");

        batches.pause();
        SalesOrderEntity whilePaused = draft(customer);
        line(whilePaused, paused, 24, "8.00", "3.0000");
        SalesOrder pausedShipped = sales.shipGoods(issued(whilePaused).id());
        assertNull(lineOf(pausedShipped, paused).stockCostEur(), "paused: no stamp");
        assertEquals(new BigDecimal("3.0000"), pricedOf(sales.price(pausedShipped), paused).landedUnitCost());
        assertEquals(120, oldest(paused).piecesLeft(), "paused: the batches are not touched");
    }

    @Test @TestTransaction
    void aCreditNoteCopiesTheStockCostAndItsReturnGoesBackToTheBatchTheSaleTookLast() {
        long customer = customer();
        long rose = product(null, "1.0000");
        start();
        receive(rose, 8, JAN, 120, cost("2.0000", null));
        receive(rose, 9, MAR, 120, cost("3.0000", null));
        SalesOrderEntity draft = draft(customer);
        line(draft, rose, 180, "8.45", "5.10");
        SalesOrder shipped = sales.shipGoods(issued(draft).id());
        assertEquals(List.of(0, 60), lefts(rose));

        SalesOrder creditNote = sales.createCreditNote(shipped.id(), new SalesOrderService.CreditNoteRequest(
                CreditReason.RETURN, List.of(new SalesOrderService.CreditLine(rose, 30, null)), List.of(), false, null));
        assertEquals(new BigDecimal("2.3333"), lineOf(creditNote, rose).unitCostEur(),
                "the credit note reverses the cost the margin used");
        assertNull(lineOf(creditNote, rose).stockCostEur());
        sales.issueInvoice(creditNote.id());
        sales.returnGoods(creditNote.id());

        assertEquals(List.of(0, 90), lefts(rose), "back into the most recently consumed batch of that invoice");
        assertTrue(batches.check().stream().noneMatch(row -> row.productId() == rose));
    }

    /* ---------------------------------------------------------------- helpers */

    private void start() {
        batches.recordStart(0, 0, 0, 0, "test");
    }

    private static BatchCost cost(String unit, String excl) {
        return new BatchCost(StockBatch.CostState.APPLIED, new BigDecimal(unit),
                excl == null ? null : new BigDecimal(excl), null, null, null);
    }

    /** Pieces from container {@code base + n} into the warehouse, a batch with this cost. */
    private void receive(long productId, int n, LocalDate receivedOn, int pieces, BatchCost cost) {
        stock.add(productId, stock.mainLocation().id(), pieces, StockMovement.Kind.PURCHASE_RECEIPT, "PO-SSC-" + n,
                StockBatchContext.receipt(new BatchSource(base + n, base + n * 10L, "PO-SSC-" + n, null, null, false,
                        receivedOn, cost)));
    }

    /** Pieces left per batch of the product, oldest first. */
    private List<Integer> lefts(long productId) {
        return em.createQuery("select b.piecesLeft from StockBatchEntity b where b.productId = :p "
                + "order by b.receivedOn, b.id", Integer.class).setParameter("p", productId).getResultList();
    }

    private StockBatch oldest(long productId) {
        return batches.forProduct(productId).stream().filter(batch -> JAN.equals(batch.receivedOn())).findFirst()
                .orElseThrow();
    }

    private long customer() {
        return customers.create(new Customer(null, "Stock cost " + UUID.randomUUID(), "Buyer",
                "stock-cost@example.invalid", null, "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen",
                "DAP", null, null, LocalDate.now())).id();
    }

    private long product(Long supplierId, String cardCost) {
        var product = new ProductEntity();
        product.sku = "SSC-" + UUID.randomUUID();
        product.name = "Stock cost rose";
        product.active = true;
        product.supplierId = supplierId;
        product.piecesPerCarton = 12;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
        product.cartonWeightKg = BigDecimal.ONE;
        product.landedCostEur = new BigDecimal(cardCost);
        em.persist(product);
        em.flush();
        return product.id;
    }

    private Supplier supplier() {
        return suppliers.save(new Supplier(null, "Stock cost " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
    }

    /** A container of this supplier with costs on every part, received and booked (a partner one when given). */
    private PurchaseOrder booked(Supplier supplier, Long partnerCustomerId, long productId, int quantity) {
        PurchaseOrder concept = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        long id = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false,
                null, List.of(new PurchaseOrderLine(null, productId, quantity, new BigDecimal("1.20"), Currency.USD,
                        null, null)))).order().id();
        PurchaseOrderEntity row = em.find(PurchaseOrderEntity.class, id);
        row.status = PurchaseOrderStatus.BESTELD;
        row.orderDate = JAN;
        row.partnerCustomerId = partnerCustomerId;
        row.freightUsd = new BigDecimal("1000");
        row.destinationCostsEur = new BigDecimal("300");
        row.extraRevenueEur = new BigDecimal("120");
        em.flush();
        return purchases.receive(id, new PurchaseOrderService.Receipt(List.of(), true, null, SEP, null));
    }

    private SalesOrderEntity draft(long customerId) {
        SalesOrder created = sales.create(customerId, "BE", "DAP", DocumentType.FACTUUR);
        return em.find(SalesOrderEntity.class, created.id());
    }

    private static void line(SalesOrderEntity order, long productId, int quantity, String price, String cost) {
        var line = new SalesOrderLineEntity();
        line.order = order;
        line.productId = productId;
        line.quantity = quantity;
        line.unitPriceEur = price == null ? null : new BigDecimal(price);
        line.unitCostEur = new BigDecimal(cost);
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

    private static SalesOrderLine lineOf(SalesOrder order, long productId) {
        return order.lines().stream().filter(line -> productId == line.productId()).findFirst().orElseThrow();
    }

    private static PricedOrder.Line pricedOf(PricedOrder priced, long productId) {
        return priced.lines().stream().filter(line -> line.productId() == productId).findFirst().orElseThrow();
    }
}
