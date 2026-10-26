package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.BatchSource;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.application.StockBatchBackfillService;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch endpoints as the screens call them (batches contract §5.2): every field name, the status through a pause,
 * a product with an own and a partner batch, a container's rows with the stale check, a batch's moves page by page,
 * a shipment's cost per batch, the check with its repair, the dry run and the reset that needs RESET.
 *
 * <p>HTTP calls run in their own transactions, so the fixture is committed; a started run makes every stock hook
 * write, so the reset in {@link #removeRows()} always removes it again, with the rows this test made.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class StockBatchResourceTest {

    private static final String BASE = "/api/stock-batches";
    private static final LocalDate RECEIVED = LocalDate.of(2026, 8, 20);

    private static final Set<String> BATCH = Set.of("id", "productId", "kind", "owner", "purchaseOrderId",
            "orderNumber", "containerName", "containerGone", "supplierId", "supplierName", "productSupplierId",
            "receivedOn", "bookedAt", "unitPieces", "piecesIn", "piecesLeft", "soldPieces", "lostPieces",
            "earlierPieces", "otherPieces", "costState", "unitCostEur", "unitCostExclEnrosedEur", "parts",
            "costAppliedAt", "costAppliedBy", "valueLeftEur", "next", "calcUnitEur", "stale", "note");
    private static final Set<String> PARTS = Set.of("goodsEur", "originEur", "freightEur", "dutyEur",
            "destinationEur", "enrosedEur", "separateEur");
    private static final Set<String> SUMMARY = Set.of("stockPieces", "batchPieces", "unassignedPieces",
            "differencePieces", "valueEur", "partnerValueEur", "valueComplete", "nextUnitCostEur", "nextBatchId");
    private static final Set<String> MOVE = Set.of("id", "batchId", "kind", "pieces", "unitPieces", "unitCostEur",
            "reference", "salesOrderId", "ticaSaleId", "purchaseOrderId", "pinned", "settles", "restores",
            "movementGone", "at", "actor");
    private static final Set<String> STATUS = Set.of("started", "paused", "startedAt", "startedBy", "pausedAt",
            "pausedBy", "differences");
    private static final Set<String> CHECK_ROW = Set.of("productId", "sku", "name", "unitPieces", "stockPieces",
            "batchPieces", "unassignedPieces", "differencePieces");

    @Inject StockBatchService batches;
    @Inject StockBatchBackfillService backfill;
    @Inject StockService stock;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> salesOrderIds = new ArrayList<>();
    private Long supplierId;

    /** What the fixture made, by name. */
    private record Fixture(long display, long perPiece, long order, String orderNumber, String alias,
                           long shipped, long unshipped, long partnerOrder) {}

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            backfill.reset();
            if (!productIds.isEmpty()) {
                em.createQuery("delete from StockMovementEntity where productId in :ids").setParameter("ids", productIds)
                        .executeUpdate();
                em.createQuery("delete from StockLevelEntity where productId in :ids").setParameter("ids", productIds)
                        .executeUpdate();
            }
            for (Long id : salesOrderIds) {
                em.createNativeQuery("delete from quote_event where salesOrderId = :id").setParameter("id", id)
                        .executeUpdate();
                em.createNativeQuery("delete from activity_log where entity_type = 'SALES_ORDER' and entity_id = :id")
                        .setParameter("id", Long.toString(id)).executeUpdate();
                SalesOrderEntity order = em.find(SalesOrderEntity.class, id);
                if (order != null) em.remove(order);
            }
            em.flush();
            for (Long id : orderIds) {
                em.createNativeQuery("delete from purchase_order_line where order_id = :id").setParameter("id", id)
                        .executeUpdate();
                em.createNativeQuery("delete from purchase_order where id = :id").setParameter("id", id).executeUpdate();
            }
            if (!productIds.isEmpty()) {
                em.createNativeQuery("delete from product where id in (:ids)").setParameter("ids", productIds)
                        .executeUpdate();
            }
            if (supplierId != null) {
                em.createNativeQuery("delete from supplier where id = :id").setParameter("id", supplierId)
                        .executeUpdate();
            }
        });
        productIds.clear();
        orderIds.clear();
        salesOrderIds.clear();
        supplierId = null;
    }

    @Test
    @TestSecurity(user = "viewer", roles = "viewer")
    void onlyAnAdminSeesTheBatches() {
        given().get(BASE + "/status").then().statusCode(403);
        given().contentType("application/json").body("{}").post(BASE + "/backfill").then().statusCode(403);
    }

    @Test
    void beforeTheStartEverythingAnswersNotStartedAndTheDryRunReportsWithoutWriting() {
        assertFalse(QuarkusTransaction.requiringNew().call(() -> batches.started()), "no start left by another test");
        long product = QuarkusTransaction.requiringNew().call(() -> {
            supplierId = supplier();
            return product(1, "2.0000");
        });
        JsonPath status = json(given().get(BASE + "/status"));
        assertEquals(STATUS, keys(status.getMap("$")));
        assertEquals(false, status.get("started"));
        assertEquals(false, status.get("paused"));
        assertEquals(0, (int) status.get("differences"));

        JsonPath shown = json(given().get(BASE + "/products/{id}", product));
        assertEquals(false, shown.get("started"));
        assertTrue(shown.getList("batches").isEmpty());
        assertEquals(SUMMARY, keys(shown.getMap("summary")));
        assertMoney("0", shown.get("summary.valueEur"));
        assertEquals(true, shown.get("summary.valueComplete"));
        given().get(BASE + "/check").then().statusCode(200).body("size()", equalTo(0));

        JsonPath report = json(given().contentType("application/json").body("{}").post(BASE + "/backfill"));
        assertEquals(Set.of("dryRun", "startedAt", "products", "batches", "openingPieces", "unassignedPieces",
                "valueEur", "rows", "notBooked"), keys(report.getMap("$")));
        assertEquals(true, report.get("dryRun"), "without dryRun the call is a dry run");
        assertNull(report.get("startedAt"));
        List<Map<String, Object>> rows = report.getList("rows");
        if (!rows.isEmpty()) {
            assertEquals(Set.of("productId", "sku", "name", "unitPieces", "stockPieces", "openingPieces",
                    "unassignedPieces", "valueEur", "flags", "notes", "batches"), rows.getFirst().keySet());
        }
        assertFalse(QuarkusTransaction.requiringNew().call(() -> batches.started()), "the dry run starts nothing");
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                "select count(b) from StockBatchEntity b", Long.class).getSingleResult()));

        given().contentType("application/json").body("{}").post(BASE + "/check/repair").then().statusCode(409)
                .body("message", equalTo("Batches zijn nog niet gestart."));
        given().contentType("application/json").body("{}").post(BASE + "/pause").then().statusCode(409)
                .body("message", equalTo("Batches zijn nog niet gestart."));
    }

    @Test
    void theScreensReadTheBatchesOfAProductAContainerAndAShipmentThenCheckRepairAndReset() {
        Fixture f = fixture();

        /* ---- status */
        JsonPath status = json(given().get(BASE + "/status"));
        assertEquals(STATUS, keys(status.getMap("$")));
        assertEquals(true, status.get("started"));
        assertEquals(false, status.get("paused"));
        assertNotNull(status.get("startedAt"));
        assertEquals(sizeOfCheck(), (int) status.get("differences"), "differences = the rows of the check");

        /* ---- product: the own batch goes first, the partner batch is apart */
        JsonPath product = json(given().get(BASE + "/products/{id}", f.display()));
        assertEquals(Set.of("productId", "started", "unitPieces", "batches", "summary"), keys(product.getMap("$")));
        assertEquals(true, product.get("started"));
        assertEquals(12, (int) product.get("unitPieces"));
        List<Map<String, Object>> list = product.getList("batches");
        assertEquals(2, list.size());
        Map<String, Object> own = list.get(0);
        Map<String, Object> partner = list.get(1);
        assertEquals(BATCH, own.keySet());
        assertEquals(BATCH, partner.keySet());
        assertEquals("RECEIPT", own.get("kind"));
        assertEquals("OWN", own.get("owner"));
        assertEquals(f.alias(), own.get("containerName"), "the Herkenbare naam");
        assertEquals(f.orderNumber(), own.get("orderNumber"));
        assertEquals(false, own.get("containerGone"));
        assertEquals("Backfill supplier", ((String) own.get("supplierName")).substring(0, 17));
        assertEquals("2026-08-20", own.get("receivedOn"));
        assertEquals(true, own.get("next"));
        assertEquals(600, own.get("piecesIn"));
        assertEquals(576, own.get("piecesLeft"), "two displays shipped");
        assertEquals(24, own.get("soldPieces"));
        assertEquals("APPLIED", own.get("costState"));
        assertMoney("1.0000", own.get("unitCostEur"));
        assertMoney("0.9000", own.get("unitCostExclEnrosedEur"));
        assertMoney("48.00", own.get("valueLeftEur"));
        Map<String, Object> parts = castMap(own.get("parts"));
        assertEquals(PARTS, parts.keySet());
        assertMoney("0.8000", parts.get("goodsEur"));
        assertNull(parts.get("freightEur"));
        assertNotNull(own.get("calcUnitEur"), "a live batch of a container that exists is compared with today");
        assertEquals(true, own.get("stale"), "€ 1 against the container's calculation of today");
        assertEquals("PARTNER", partner.get("owner"));
        assertEquals(true, partner.get("containerGone"), "a container that no longer exists");
        assertEquals("PO-GONE", partner.get("containerName"), "keeps the number the batch froze");
        assertNull(partner.get("calcUnitEur"));
        assertEquals(false, partner.get("stale"));
        assertEquals(false, partner.get("next"));
        assertNull(partner.get("parts"));
        Map<String, Object> summary = product.getMap("summary");
        assertEquals(SUMMARY, summary.keySet());
        assertMoney("48.00", summary.get("valueEur"), "own only");
        assertMoney("20.00", summary.get("partnerValueEur"), "120 pieces at € 2 per display");
        assertEquals(true, summary.get("valueComplete"));
        assertMoney("1.0000", summary.get("nextUnitCostEur"));
        assertEquals(((Number) own.get("id")).longValue(), ((Number) summary.get("nextBatchId")).longValue());
        assertEquals(696, summary.get("stockPieces"));
        assertEquals(696, summary.get("batchPieces"));
        assertEquals(0, summary.get("differencePieces"));
        long ownId = ((Number) own.get("id")).longValue();

        /* ---- moves, newest first, page by page */
        JsonPath moves = json(given().queryParam("limit", 1).get(BASE + "/{id}/moves", ownId));
        assertEquals(2, (int) moves.get("total"));
        List<Map<String, Object>> page = moves.getList("moves");
        assertEquals(1, page.size());
        assertEquals(MOVE, page.getFirst().keySet());
        assertEquals("SALE", page.getFirst().get("kind"));
        assertEquals(-24, page.getFirst().get("pieces"));
        assertEquals(f.shipped(), ((Number) page.getFirst().get("salesOrderId")).longValue());
        assertEquals(false, page.getFirst().get("settles"));
        assertEquals(false, page.getFirst().get("restores"));
        assertEquals(false, page.getFirst().get("movementGone"));
        List<Map<String, Object>> all = json(given().get(BASE + "/{id}/moves", ownId)).getList("moves");
        assertEquals(List.of("SALE", "PURCHASE_RECEIPT"), all.stream().map(move -> move.get("kind")).toList());
        assertEquals(1, json(given().queryParam("limit", 0).get(BASE + "/{id}/moves", ownId)).getList("moves").size(),
                "the limit stays within 1..500");
        given().get(BASE + "/{id}/moves", 987_654_321_987L).then().statusCode(404);

        /* ---- the container: rows, totals, live-only costs and the stale figure */
        JsonPath container = json(given().get(BASE + "/purchase-orders/{id}", f.order()));
        assertEquals(Set.of("started", "booked", "rows", "totals", "costs"), keys(container.getMap("$")));
        assertEquals(true, container.get("booked"));
        List<Map<String, Object>> rows = container.getList("rows");
        assertEquals(1, rows.size());
        Set<String> rowKeys = new TreeSet<>(BATCH);
        rowKeys.add("sku");
        rowKeys.add("productName");
        assertEquals(rowKeys, new TreeSet<>(rows.getFirst().keySet()), "every batch field plus the product");
        assertEquals("Batch resource display", rows.getFirst().get("productName"));
        assertEquals(true, rows.getFirst().get("stale"));
        assertEquals(Map.of("piecesIn", 600, "piecesLeft", 576, "valueComplete", true),
                Map.of("piecesIn", container.get("totals.piecesIn"), "piecesLeft", container.get("totals.piecesLeft"),
                        "valueComplete", container.get("totals.valueComplete")));
        assertMoney("48.00", container.get("totals.valueLeftEur"));
        Map<String, Object> costs = container.getMap("costs");
        assertEquals(Set.of("applied", "calculated", "card", "none", "stale", "staleLargestEur"), costs.keySet());
        assertEquals(1, costs.get("applied"));
        assertEquals(0, costs.get("calculated"));
        assertEquals(1, costs.get("stale"));
        BigDecimal change = toDecimal(rows.getFirst().get("calcUnitEur")).subtract(new BigDecimal("1.0000"));
        assertMoney(change.toPlainString(), costs.get("staleLargestEur"), "signed: today's calculation − the batch");
        given().get(BASE + "/purchase-orders/{id}", 987_654_321_987L).then().statusCode(404);

        /* ---- the shipment: per batch, on the excl. Enrosed basis of a container sale */
        JsonPath sale = json(given().get(BASE + "/sales-orders/{id}", f.shipped()));
        assertEquals(Set.of("salesOrderId", "shipped", "lines"), keys(sale.getMap("$")));
        assertEquals(true, sale.get("shipped"));
        List<Map<String, Object>> lines = sale.getList("lines");
        assertEquals(1, lines.size());
        Map<String, Object> line = lines.getFirst();
        assertEquals(Set.of("productId", "unitPieces", "pieces", "exclEnrosed", "withoutBatchPieces",
                "withoutCostPieces", "batches"), line.keySet());
        assertEquals(f.display(), ((Number) line.get("productId")).longValue());
        assertEquals(12, line.get("unitPieces"));
        assertEquals(24, line.get("pieces"));
        assertEquals(true, line.get("exclEnrosed"));
        assertEquals(0, line.get("withoutBatchPieces"));
        assertEquals(0, line.get("withoutCostPieces"));
        List<Map<String, Object>> taken = castList(line.get("batches"));
        assertEquals(1, taken.size());
        assertEquals(Set.of("batchId", "kind", "containerName", "orderNumber", "pieces", "unitPieces", "unitCostEur",
                "unitCostExclEnrosedEur"), taken.getFirst().keySet());
        assertEquals(ownId, ((Number) taken.getFirst().get("batchId")).longValue());
        assertEquals("RECEIPT", taken.getFirst().get("kind"));
        assertEquals(f.alias(), taken.getFirst().get("containerName"));
        assertEquals(24, taken.getFirst().get("pieces"));
        assertEquals(12, taken.getFirst().get("unitPieces"));
        assertMoney("1.0000", taken.getFirst().get("unitCostEur"));
        assertMoney("0.9000", taken.getFirst().get("unitCostExclEnrosedEur"));
        JsonPath notShipped = json(given().get(BASE + "/sales-orders/{id}", f.unshipped()));
        assertEquals(false, notShipped.get("shipped"));
        assertTrue(notShipped.getList("lines").isEmpty());
        /* the per-piece product: 5 found in a Telling batch, 8 sold, 3 without batch */
        JsonPath perPiece = json(given().get(BASE + "/products/{id}", f.perPiece()));
        assertEquals(3, (int) perPiece.get("summary.unassignedPieces"));
        assertEquals(0, (int) perPiece.get("summary.differencePieces"));
        assertEquals("COUNT", perPiece.get("batches[0].kind"));
        assertEquals("CARD", perPiece.get("batches[0].costState"));

        /* ---- pause, drift, check, repair */
        JsonPath paused = json(given().contentType("application/json").body("{}").post(BASE + "/pause"));
        assertEquals(true, paused.get("paused"));
        assertEquals("emre", paused.get("pausedBy"));
        QuarkusTransaction.requiringNew().run(() -> stock.sell(f.display(), stock.mainLocation().id(), 1, "F-PAUSED"));
        JsonPath drift = json(given().get(BASE + "/status"));
        assertEquals(true, drift.get("paused"));
        assertEquals(sizeOfCheck(), (int) drift.get("differences"));
        List<Map<String, Object>> check = json(given().get(BASE + "/check")).getList("$");
        Map<String, Object> off = check.stream().filter(row -> ((Number) row.get("productId")).longValue() == f.display())
                .findFirst().orElseThrow(() -> new AssertionError("the paused sale shows in the check"));
        assertEquals(CHECK_ROW, off.keySet());
        assertEquals(12, off.get("differencePieces"), "one display left without its batch move");
        assertEquals(684, off.get("stockPieces"));
        assertEquals(696, off.get("batchPieces"));
        given().contentType("application/json").body(Map.of("productIds", List.of(f.display())))
                .post(BASE + "/check/repair").then().statusCode(409).body("message", equalTo("Batches staan op pauze."));
        JsonPath resumed = json(given().contentType("application/json").body("{}").post(BASE + "/resume"));
        assertEquals(false, resumed.get("paused"));
        assertNull(resumed.get("pausedAt"));
        List<Map<String, Object>> after = json(given().contentType("application/json")
                .body(Map.of("productIds", List.of(f.display()))).post(BASE + "/check/repair")).getList("$");
        assertTrue(after.stream().noneMatch(row -> ((Number) row.get("productId")).longValue() == f.display()),
                "Herstellen booked the difference");
        assertEquals(684, (int) json(given().get(BASE + "/products/{id}", f.display())).get("summary.batchPieces"));

        /* ---- the start is refused once started; the reset needs RESET */
        given().contentType("application/json").body("{}").queryParam("dryRun", false).post(BASE + "/backfill")
                .then().statusCode(409).body("message", org.hamcrest.Matchers.startsWith("Batches zijn al gestart op "));
        given().contentType("application/json").body("{}").post(BASE + "/reset").then().statusCode(409)
                .body("message", equalTo("Bevestig met RESET."));
        given().contentType("application/json").body(Map.of("confirm", "reset")).post(BASE + "/reset").then()
                .statusCode(409).body("message", equalTo("Bevestig met RESET."));
        assertTrue(QuarkusTransaction.requiringNew().call(() -> batches.started()), "a refused reset changes nothing");
        JsonPath reset = json(given().contentType("application/json").body(Map.of("confirm", "RESET"))
                .post(BASE + "/reset"));
        assertEquals(false, reset.get("started"));
        assertEquals(false, json(given().get(BASE + "/products/{id}", f.display())).get("started"));
    }

    /* -------------------------------------------------------------- fixture */

    /**
     * A display product (12 per display) received 50 displays from a container named by its alias at € 1 per display
     * (parts 0,80 goods + 0,10 Enrosed kost; 0,90 without), 10 displays of a partner container that no longer exists
     * at € 2, two displays shipped on an invoice from that container; a per-piece product with 5 found and 8 sold.
     */
    private Fixture fixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            assertFalse(batches.started(), "no start left by another test");
            supplierId = supplier();
            long display = product(12, "8.0000");
            long perPiece = product(1, "2.0000");
            String alias = "Stolpen " + UUID.randomUUID().toString().substring(0, 6);
            PurchaseOrder created = purchases.create(supplierId, new BigDecimal("0.14"), new BigDecimal("0.92"),
                    BigDecimal.ZERO);
            orderIds.add(created.id());
            purchases.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                    List.of(new PurchaseOrderLine(null, display, 50, new BigDecimal("5.00"), Currency.USD, null,
                            null))));
            PurchaseOrderEntity stored = em.find(PurchaseOrderEntity.class, created.id());
            stored.alias = alias;
            stored.status = PurchaseOrderStatus.ONTVANGEN;
            stored.stockBooked = true;
            stored.receivedOn = RECEIVED;
            long lineId = stored.lines.getFirst().id;
            String number = stored.number;
            em.flush();

            Customer customer = customers.create(new Customer(null, "Batch klant " + UUID.randomUUID(), "Buyer",
                    "batch@example.invalid", null, "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen",
                    "DAP", null, null, LocalDate.now()));
            long shipped = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR).id();
            long unshipped = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR).id();
            salesOrderIds.add(shipped);
            salesOrderIds.add(unshipped);
            SalesOrderEntity invoice = em.find(SalesOrderEntity.class, shipped);
            invoice.sourcePurchaseOrderId = created.id();
            invoice.goodsShippedAt = Instant.now();
            em.flush();

            batches.recordStart(0, 0, 0, 0, "resource test");
            long main = stock.mainLocation().id();
            BatchCost applied = new BatchCost(StockBatch.CostState.APPLIED, new BigDecimal("1.0000"),
                    new BigDecimal("0.9000"), new ProductCostBreakdown(new BigDecimal("0.8000"), null, null, null,
                    null, new BigDecimal("0.1000"), null), Instant.now(), "emre");
            stock.add(display, main, 50, StockMovement.Kind.PURCHASE_RECEIPT, number, StockBatchContext.receipt(
                    new BatchSource(created.id(), lineId, number, supplierId, null, false, RECEIVED, applied)));
            long gone = 950_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000);
            stock.add(display, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-GONE", StockBatchContext.receipt(
                    new BatchSource(gone, gone, "PO-GONE", supplierId, null, true, RECEIVED.plusDays(10),
                            new BatchCost(StockBatch.CostState.CALCULATED, new BigDecimal("2.0000"), null, null, null,
                                    null))));
            stock.sell(display, main, 2, "F-BATCH", StockBatchContext.sale(shipped, null));
            stock.setLevel(perPiece, main, 5, StockMovement.Kind.STOCKTAKE, "Telling");
            stock.sell(perPiece, main, 8, "F-BATCH-2");
            return new Fixture(display, perPiece, created.id(), number, alias, shipped, unshipped, gone);
        });
    }

    private long supplier() {
        Supplier saved = suppliers.save(new Supplier(null, "Backfill supplier " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB Ningbo", "Ningbo", 30, null));
        return saved.id();
    }

    private long product(int unitPieces, String cardCost) {
        ProductEntity product = new ProductEntity();
        product.sku = "BRT-" + UUID.randomUUID();
        product.name = unitPieces > 1 ? "Batch resource display" : "Batch resource piece";
        product.colour = "rood";
        product.active = true;
        product.supplierId = supplierId;
        product.inventoryKnown = false;
        product.stockQuantity = 0;
        product.landedCostEur = new BigDecimal(cardCost);
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        if (unitPieces > 1) {
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
            product.packagingPiecesPerUnit = unitPieces;
        }
        em.persist(product);
        em.flush();
        productIds.add(product.id);
        return product.id;
    }

    /* -------------------------------------------------------------- helpers */

    private static JsonPath json(io.restassured.response.Response response) {
        return response.then().statusCode(200).extract().jsonPath(
                JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    private int sizeOfCheck() {
        return json(given().get(BASE + "/check")).getList("$").size();
    }

    private static Set<String> keys(Map<String, Object> map) {
        return map.keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static BigDecimal toDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static void assertMoney(String expected, Object actual) {
        assertMoney(expected, actual, null);
    }

    private static void assertMoney(String expected, Object actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(toDecimal(actual)),
                (message == null ? "" : message + ": ") + expected + " ≠ " + actual);
    }
}
