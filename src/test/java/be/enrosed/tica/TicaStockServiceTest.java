package be.enrosed.tica;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductChannelPriceService;
import be.enrosed.catalog.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.tica.TicaEntities.*;
import be.enrosed.tica.TicaStockEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class TicaStockServiceTest {
    @Inject TicaService sync;
    @Inject TicaStockService tica;
    @Inject StockService stock;
    @Inject ProductService products;
    @Inject EntityManager entities;
    @Inject PanacheProductRepository repository;
    @Inject ProductChannelPriceService prices;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    private static final Instant START = Instant.parse("2026-10-19T10:00:00Z");
    private long product, location;
    private String sku;
    private int run;

    @BeforeEach void setup() {
        QuarkusTransaction.requiringNew().run(() -> {
            entities.createQuery("delete from StockMovementEntity where ticaSaleId is not null").executeUpdate();
            Revision.deleteAll(); ProductUnit.deleteAll(); Location.deleteAll();
            TicaSettlementEntity.deleteAll(); Delivery.deleteAll(); Notice.deleteAll(); Sale.deleteAll(); Settings.deleteAll();
        });
        sync.ensureSettings();
        TicaService actual = io.quarkus.arc.ClientProxy.unwrap(sync);
        actual.enabled = true; actual.username = "TT_2132"; actual.password = Optional.of("test-only");
        sku = "TICA-STOCK-" + UUID.randomUUID();
        product = createProduct(sku, false);
        location = stock.saveLocation(new StockLocation(null, "TEST-" + UUID.randomUUID(), "Synthetic TICA stand",
                StockLocation.Kind.SALES_POINT, null, true, false, false, 100)).id();
        stock.setLevel(product, location, 20, StockMovement.Kind.STOCKTAKE, "Synthetic opening");
        run = 0;
    }

    @Test void historicalImportPostsOnceAndCorrectionsReturnsAndRetractionsStayInHistory() {
        importRows(row("1", sku, "2"));
        assertEquals(20, stock.quantityAt(product, location));
        configure(true, DATE, List.of());
        importRows(row("1", sku, "2"));
        assertEquals(18, stock.quantityAt(product, location));
        long id = sales().getFirst().id();
        int revisions = tica.history(id).revisions().size();
        importRows(row("1", sku, "2"));
        assertEquals(revisions, tica.history(id).revisions().size());
        assertEquals(1, movements(id).size());
        importRows(row("1", sku, "3"));
        assertEquals(17, stock.quantityAt(product, location));
        importRows(row("1", sku, "3"), row("2", sku, "-1"));
        assertEquals(18, stock.quantityAt(product, location));
        importRows(row("2", sku, "-1"));
        assertEquals(21, stock.quantityAt(product, location));
        assertEquals(List.of(3, -1, -2), movements(id).stream().map(m -> m.delta).toList());
        assertEquals("REVERSED", tica.history(id).revisions().getFirst().stockStatus());
        assertEquals(new BigDecimal("2"), tica.history(id).revisions().getLast().quantity());
        assertTrue(tica.history(id).revisions().stream().allMatch(r -> r.sourceSoldAt().toLocalDate().equals(DATE)));
    }

    @Test void displaySalesRequireExplicitPieceMappingThenPreserveEveryLooseStem() {
        long display = createProduct("DISPLAY-" + sku, true);
        stock.setLevel(display, location, 8, StockMovement.Kind.STOCKTAKE, "Eight displays");
        importRows(row("1", "DISPLAY-" + sku, "1"));
        configure(true, DATE, List.of());
        importRows(row("1", "DISPLAY-" + sku, "1"));
        assertEquals("UNIT_MISMATCH", sales().getFirst().stockStatus());
        assertEquals(96, stock.physicalQuantityAt(display, location));
        configure(true, DATE, List.of(new TicaStockService.ProductUnitDto(display, "PIECE", null, 0, false)));
        importRows(row("1", "DISPLAY-" + sku, "1"));
        assertEquals(7, stock.quantityAt(display, location));
        assertEquals(11, stock.loosePiecesAt(display, location));
        long id = sales().getFirst().id();
        StockMovementEntity first = movements(id).getFirst();
        assertEquals(-1, first.delta); assertEquals(11, first.looseDelta); assertEquals(12, first.unitPieces);
        assertEquals("PIECE", first.sourceUnit);
        assertEquals(0, new BigDecimal("2").compareTo(sync.analytics().getFirst().goodsCostEur()));
        importRows(row("1", "DISPLAY-" + sku, "1"));
        assertEquals(1, movements(id).size());
        importRows(row("1", "DISPLAY-" + sku, "2"));
        assertEquals(94, stock.physicalQuantityAt(display, location));
        assertEquals(10, stock.loosePiecesAt(display, location));
        importRows(row("1", "DISPLAY-" + sku, "2"), row("2", "DISPLAY-" + sku, "-1"));
        assertEquals(95, stock.physicalQuantityAt(display, location));
        importRows();
        assertEquals(96, stock.physicalQuantityAt(display, location));
        assertEquals(8, stock.quantityAt(display, location)); assertEquals(0, stock.loosePiecesAt(display, location));
    }

    @Test void wholeUnitOperationsAndOldCountClientsKeepLoosePieces() {
        long display = createProduct("DISPLAY-" + sku, true);
        stock.setLevel(display, location, 7, 11, StockMovement.Kind.STOCKTAKE, "Measured");
        stock.add(display, location, 2, StockMovement.Kind.PURCHASE_RECEIPT, "Two displays");
        assertEquals(119, stock.physicalQuantityAt(display, location));
        stock.sell(display, location, 1, "One display");
        assertEquals(107, stock.physicalQuantityAt(display, location));
        stock.setLevel(display, location, 5, StockMovement.Kind.STOCKTAKE, "Legacy caller");
        assertEquals(71, stock.physicalQuantityAt(display, location));
        long main = stock.mainLocation().id();
        stock.transfer(display, location, main, 2, "Transfer displays");
        assertEquals(47, stock.physicalQuantityAt(display, location));
        assertEquals(24, stock.physicalQuantityAt(display, main));
        stock.setLevel(display, location, 3, 0, StockMovement.Kind.STOCKTAKE, "Explicit complete count");
        assertEquals(36, stock.physicalQuantityAt(display, location));
    }

    @Test void missingMappingsAndFractionalQuantitiesNeverGuessAnAdjustment() {
        importRows(row("1", "UNMATCHED-" + sku, "1"), row("2", sku, "1.5"));
        configure(true, DATE, List.of());
        importRows(row("1", "UNMATCHED-" + sku, "1"), row("2", sku, "1.5"));
        assertEquals(Set.of("PRODUCT_UNMATCHED", "INVALID_QUANTITY"), new HashSet<>(sales().stream().map(TicaService.SaleDto::stockStatus).toList()));
        assertEquals(20, stock.quantityAt(product, location));
        assertTrue(sales().stream().allMatch(s -> s.stockPieces() == 0));
    }

    @Test void settingsAndHistoryAreProtectedAndEarlierBookedStartCannotMove() {
        importRows(row("1", sku, "2"));
        configure(true, DATE, List.of());
        importRows(row("1", sku, "2"));
        long id = sales().getFirst().id();
        long movement = movements(id).getFirst().id;
        assertThrows(BusinessRuleException.class, () -> products.deleteStockMovement(product, movement));
        assertThrows(BusinessRuleException.class, () -> configure(true, DATE.plusDays(1), List.of()));
        assertThrows(BusinessRuleException.class, () -> stock.deleteLocation(location));
        configure(false, DATE, List.of());
        importRows(row("1", sku, "3"));
        assertEquals(18, stock.quantityAt(product, location));
        configure(true, DATE, List.of());
        importRows(row("1", sku, "3"));
        assertEquals(17, stock.quantityAt(product, location));
        given().get("/api/tica/stock-settings").then().statusCode(401);
        given().get("/api/tica/sales/" + id + "/history").then().statusCode(401);
        given().contentType("application/json").body("{}").put("/api/tica/stock-settings").then().statusCode(401);
    }

    @Test void remappedProductReversesOldTargetAndAppliesNewTargetExactlyOnce() {
        long other = createProduct("OTHER-" + sku, false);
        stock.setLevel(other, location, 15, StockMovement.Kind.STOCKTAKE, "Other opening");
        importRows(row("1", sku, "2"));
        configure(true, DATE, List.of());
        importRows(row("1", sku, "2"));
        long id = sales().getFirst().id();
        importRows(row("1", "OTHER-" + sku, "2"));
        assertEquals(20, stock.quantityAt(product, location));
        assertEquals(13, stock.quantityAt(other, location));
        assertEquals(3, movements(id).size());
        importRows(row("1", "OTHER-" + sku, "2"));
        assertEquals(3, movements(id).size());
    }

    @Test void concurrentStockWritersDoNotLoseAnAdjustment() throws Exception {
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) tasks.add(executor.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int i = 0; i < 8; i++) stock.add(product, location, 1, StockMovement.Kind.PURCHASE_RECEIPT, "Concurrent receipt");
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertEquals(36, stock.quantityAt(product, location));
    }

    @Test void anAlreadyLoadedLevelIsRefreshedAfterAnotherWriterCommits() {
        QuarkusTransaction.requiringNew().run(() -> {
            StockLevelEntity cached = entities.createQuery(
                    "from StockLevelEntity where productId = :product and locationId = :location", StockLevelEntity.class)
                    .setParameter("product", product).setParameter("location", location).getSingleResult();
            assertEquals(20, cached.quantity);
            CompletableFuture.runAsync(() -> stock.add(product, location, 3, StockMovement.Kind.PURCHASE_RECEIPT, "Parallel")).join();
            stock.add(product, location, 2, StockMovement.Kind.PURCHASE_RECEIPT, "Previously loaded");
        });
        assertEquals(25, stock.quantityAt(product, location));
    }

    @Test void aStaleProductSaveCannotRestoreEarlierStockOrChangeItsNowUsedUnitBasis() {
        long main = stock.mainLocation().id();
        stock.setLevel(product, main, 10, StockMovement.Kind.STOCKTAKE, "Website stock");
        Product stale = QuarkusTransaction.requiringNew().call(() -> repository.findById(product).orElseThrow());
        stock.sell(product, main, 2, "Concurrent shipment");
        QuarkusTransaction.requiringNew().run(() -> repository.save(stale.withVariantAttributes("Changed colour", null, null)));
        assertEquals(8, products.get(product).stockQuantity());
        long display = createProduct("DISPLAY-" + sku, true);
        stock.setLevel(display, location, 8, StockMovement.Kind.STOCKTAKE, "Initial displays");
        Product changed = QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity draft = entities.find(ProductEntity.class, display);
            draft.packagingSalesUnit = SalesUnit.PIECE;
            Product proposed = repository.findById(display).orElseThrow();
            draft.packagingSalesUnit = SalesUnit.DISPLAY;
            return proposed;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            entities.find(ProductEntity.class, display); // Cached before the competing stock write.
            CompletableFuture.runAsync(() -> stock.setLevel(display, location, 7, 11, StockMovement.Kind.STOCKTAKE, "New loose stock")).join();
            assertThrows(BusinessRuleException.class, () -> repository.save(changed));
        });
        assertEquals(95, stock.physicalQuantityAt(display, location));
    }

    @Test void deletingAProductWaitsForItsFirstChannelPriceBeforeCheckingHistory() throws Exception {
        CountDownLatch priceWritten = new CountDownLatch(1), releasePrice = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> priceWriter = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                prices.update(product, new ProductChannelPriceService.Change(BigDecimal.TEN,
                        ProductChannelPriceService.Unit.PIECE));
                priceWritten.countDown();
                try { assertTrue(releasePrice.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            assertTrue(priceWritten.await(10, TimeUnit.SECONDS));
            Future<?> deletion = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                deleteStarted.countDown();
                repository.deleteById(product);
            }));
            try {
                assertTrue(deleteStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(300, TimeUnit.MILLISECONDS));
            } finally { releasePrice.countDown(); }
            priceWriter.get(10, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> deletion.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessRuleException.class, rejected.getCause());
        }
        assertNotNull(products.get(product));
        assertEquals(1, prices.history(product).size());
    }

    private long createProduct(String sku, boolean display) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity p = new ProductEntity(); p.sku = sku; p.name = "Synthetic stock product"; p.active = true;
            if (display) { p.packagingKind = PackagingKind.DISPLAY; p.packagingSalesUnit = SalesUnit.DISPLAY; p.packagingPiecesPerUnit = 12; }
            p.landedCostEur = new BigDecimal("24"); entities.persist(p); entities.flush(); return p.id;
        });
    }
    private void configure(boolean enabled, LocalDate from, List<TicaStockService.ProductUnitDto> units) {
        tica.update(new TicaStockService.SettingsDto(enabled, from, null, false,
                List.of(new TicaStockService.LocationDto("TICA - Brussel", location, null, false)), 0, 0, units));
    }
    private void importRows(TicaCsvParser.Row... rows) {
        Instant at = START.plusSeconds((run++) * 901L);
        var claim = sync.claim(at); assertNotNull(claim); sync.complete(claim, List.of(rows), at.plusSeconds(1));
    }
    private List<TicaService.SaleDto> sales() { return sync.sales(DATE, DATE, 0, 100).lines(); }
    private List<StockMovementEntity> movements(long saleId) {
        return QuarkusTransaction.requiringNew().call(() -> entities.createQuery(
                "from StockMovementEntity where ticaSaleId = :sale order by id desc", StockMovementEntity.class)
                .setParameter("sale", saleId).getResultList());
    }
    private TicaCsvParser.Row row(String line, String sku, String quantity) {
        BigDecimal q = new BigDecimal(quantity);
        return new TicaCsvParser.Row("TICA - Brussel", "2132", DATE.atTime(10, 0), "T-TEST", line, "Synthetic source",
                "EXT-" + sku, sku, "", q, BigDecimal.ZERO, q.multiply(BigDecimal.TEN), q.multiply(BigDecimal.TEN),
                new BigDecimal("21"), q.signum() > 0 ? "Verkoop" : "Retour");
    }
}
