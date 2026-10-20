package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.tica.TicaEntities.Sale;
import be.enrosed.tica.TicaStockEntities.ProductUnit;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static be.enrosed.catalog.application.ProductChannelPriceService.Unit.*;

@QuarkusTest
class ProductChannelPriceServiceTest {
    @Inject ProductChannelPriceService prices;
    @Inject EntityManager entities;

    @Test @TestTransaction void latestUndiscountedSaleIsSavedOnceAndDoesNotChangeCataloguePrice() {
        ProductEntity product = product();
        sale(product, "2", "39.90", "39.90", "0", 1);
        prices.refreshTicaPrices();
        assertEquals(new BigDecimal("19.9500"), price(product).priceExclVat());
        assertEquals("TICA", price(product).source());
        assertEquals(PIECE, price(product).unit());
        prices.refreshTicaPrices();
        assertEquals(1, prices.history(product.id).size());
        assertEquals(new BigDecimal("14.9500"), product.fixedSalesPriceEur);
    }

    @Test @TestTransaction void manualPriceWinsWhileNewSourcePricesAreStillVisible() {
        ProductEntity product = product();
        sale(product, "1", "19.95", "19.95", "0", 1);
        prices.refreshTicaPrices();
        prices.update(product.id, new ProductChannelPriceService.Change(new BigDecimal("22.00"), PIECE));
        sale(product, "1", "20.95", "20.95", "0", 2);
        prices.refreshTicaPrices();
        assertEquals(new BigDecimal("22.0000"), price(product).priceExclVat());
        assertEquals(new BigDecimal("20.9500"), price(product).observedPriceExclVat());
        assertEquals("MANUAL", price(product).source());
        assertEquals(2, prices.history(product.id).size());
        prices.update(product.id, new ProductChannelPriceService.Change(null, null));
        assertEquals(new BigDecimal("20.9500"), price(product).priceExclVat());
        assertEquals("TICA", price(product).source());
        assertEquals(3, prices.history(product.id).size());
    }

    @Test @TestTransaction void discountedAndReturnedRowsNeverReplaceTheObservedListPrice() {
        ProductEntity product = product();
        sale(product, "1", "19.95", "19.95", "0", 1);
        sale(product, "1", "30", "15", "50", 2);
        sale(product, "-1", "-40", "-40", "0", 3);
        prices.refreshTicaPrices();
        assertEquals(new BigDecimal("19.9500"), price(product).priceExclVat());
    }

    @Test @TestTransaction void correctedAndRetractedSalesUpdateTheObservationWithoutDeletingHistory() {
        ProductEntity product = product();
        Sale first = sale(product, "1", "19.95", "19.95", "0", 1);
        Sale second = sale(product, "1", "20.95", "20.95", "0", 2);
        prices.refreshTicaPrices();
        second.grossTotal = second.netTotal = new BigDecimal("21.95");
        prices.refreshTicaPrices();
        assertEquals(new BigDecimal("21.9500"), price(product).priceExclVat());
        second.active = false;
        prices.refreshTicaPrices();
        assertEquals(new BigDecimal("19.9500"), price(product).priceExclVat());
        first.active = false;
        prices.refreshTicaPrices();
        assertNull(price(product).priceExclVat());
        assertNull(price(product).source());
        assertEquals(4, prices.history(product.id).size());
    }

    @Test @TestTransaction void displaySkuDoesNotImplyADisplayPriceForAnInnerBarcode() {
        ProductEntity product = product();
        product.packagingKind = PackagingKind.DISPLAY;
        product.packagingSalesUnit = SalesUnit.DISPLAY;
        product.packagingPiecesPerUnit = 12;
        product.packagingBarcode = "6153402714762";
        Sale sale = sale(product, "1", "3.95", "3.95", "0", 1);
        sale.barcode = "6153402711747";
        prices.refreshTicaPrices();
        assertEquals(TICA_ARTICLE, price(product).unit());
        assertEquals(new BigDecimal("3.9500"), price(product).priceExclVat());
        sale.barcode = product.packagingBarcode;
        prices.refreshTicaPrices();
        assertEquals(DISPLAY, price(product).unit());
        assertEquals(2, prices.history(product.id).size());
        ProductUnit mapping = new ProductUnit();
        mapping.productId = product.id; mapping.sourceUnit = "PIECE"; mapping.persist();
        prices.refreshTicaPrices();
        assertEquals(PIECE, price(product).unit(), "an explicit channel unit controls the displayed price basis");
        assertEquals(3, prices.history(product.id).size());
    }

    @Test @TestTransaction void repeatedManualSaveIsIdempotentAndUnitsAreRequired() {
        ProductEntity product = product();
        var change = new ProductChannelPriceService.Change(new BigDecimal("12.50"), PIECE);
        prices.update(product.id, change);
        prices.update(product.id, change);
        assertEquals(1, prices.history(product.id).size());
        assertThrows(BusinessRuleException.class, () -> prices.update(product.id,
                new ProductChannelPriceService.Change(BigDecimal.TEN, null)));
        assertThrows(BusinessRuleException.class, () -> prices.update(product.id,
                new ProductChannelPriceService.Change(new BigDecimal("-1"), PIECE)));
        assertThrows(BusinessRuleException.class, () -> prices.update(product.id,
                new ProductChannelPriceService.Change(new BigDecimal("1.12345"), PIECE)));
    }

    @Test @TestTransaction void unsavedChannelHasNoImplicitCatalogueFallback() {
        ProductEntity product = product();
        assertNull(price(product).priceExclVat());
        assertNull(price(product).source());
        assertTrue(prices.history(product.id).isEmpty());
    }

    @Test void manualSaveCommittingWhileSyncWaitsForTheProductLockIsPreserved() throws Exception {
        Long productId = QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity product = product();
            sale(product, "1", "19.95", "19.95", "0", 1);
            prices.refreshTicaPrices();
            return product.id;
        });
        CountDownLatch manualHasLock = new CountDownLatch(1);
        CountDownLatch allowManualCommit = new CountDownLatch(1);
        CountDownLatch syncStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> manual = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                prices.update(productId, new ProductChannelPriceService.Change(new BigDecimal("24.95"), PIECE));
                manualHasLock.countDown();
                await(allowManualCommit);
            }));
            assertTrue(manualHasLock.await(10, TimeUnit.SECONDS));
            Future<?> sync = executor.submit(() -> {
                syncStarted.countDown();
                prices.refreshTicaPrices();
            });
            assertTrue(syncStarted.await(10, TimeUnit.SECONDS));
            // The product lock deliberately holds the source refresh behind the editor.
            Thread.sleep(500);
            assertFalse(sync.isDone(), "the sync must wait for the same product lock");
            allowManualCommit.countDown();
            manual.get(15, TimeUnit.SECONDS);
            sync.get(15, TimeUnit.SECONDS);
            var result = prices.prices(productId).getFirst();
            assertEquals("MANUAL", result.source());
            assertEquals(new BigDecimal("24.9500"), result.priceExclVat());
            assertEquals(new BigDecimal("19.9500"), result.observedPriceExclVat());
        } finally {
            allowManualCommit.countDown();
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createQuery("delete from ProductChannelPriceHistoryEntity where productId = :id")
                        .setParameter("id", productId).executeUpdate();
                entities.createQuery("delete from ProductChannelPriceEntity where productId = :id")
                        .setParameter("id", productId).executeUpdate();
                Sale.delete("productId", productId);
                entities.remove(entities.find(ProductEntity.class, productId));
            });
        }
    }

    @Test void skuChangeCommittingWhileSyncWaitsCannotApplyThePreviousSkuPrice() throws Exception {
        Long productId = QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity product = product();
            sale(product, "1", "19.95", "19.95", "0", 1);
            prices.refreshTicaPrices();
            return product.id;
        });
        CountDownLatch editorHasLock = new CountDownLatch(1);
        CountDownLatch allowEditorCommit = new CountDownLatch(1);
        CountDownLatch syncStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> editor = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                ProductEntity product = entities.find(ProductEntity.class, productId, LockModeType.PESSIMISTIC_WRITE);
                product.sku = "RENAMED-" + UUID.randomUUID();
                entities.flush();
                editorHasLock.countDown();
                await(allowEditorCommit);
            }));
            assertTrue(editorHasLock.await(10, TimeUnit.SECONDS));
            Future<?> sync = executor.submit(() -> {
                syncStarted.countDown();
                prices.refreshTicaPrices();
            });
            assertTrue(syncStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(500);
            assertFalse(sync.isDone(), "source refresh waits until the product edit commits");
            allowEditorCommit.countDown();
            editor.get(15, TimeUnit.SECONDS);
            sync.get(15, TimeUnit.SECONDS);
            assertNull(prices.prices(productId).getFirst().observedPriceExclVat(),
                    "a stale source mapping must not attach the previous SKU's price");
            assertEquals(2, prices.history(productId).size());
        } finally {
            allowEditorCommit.countDown();
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createQuery("delete from ProductChannelPriceHistoryEntity where productId = :id")
                        .setParameter("id", productId).executeUpdate();
                entities.createQuery("delete from ProductChannelPriceEntity where productId = :id")
                        .setParameter("id", productId).executeUpdate();
                Sale.delete("productId", productId);
                entities.remove(entities.find(ProductEntity.class, productId));
            });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ProductChannelPriceService.Price price(ProductEntity product) {
        return prices.prices(product.id).getFirst();
    }
    private ProductEntity product() {
        ProductEntity product = new ProductEntity();
        product.name = "Channel price test";
        product.sku = "CHANNEL-" + UUID.randomUUID();
        product.fixedSalesPriceEur = new BigDecimal("14.95");
        entities.persist(product);
        entities.flush();
        return product;
    }
    private Sale sale(ProductEntity product, String qty, String gross, String net, String discount, int day) {
        Sale sale = new Sale();
        sale.productId = product.id; sale.sku = product.sku;
        sale.exhibitor = "843"; sale.location = "TICA - Test";
        sale.transactionNumber = UUID.randomUUID().toString(); sale.lineNumber = "1";
        sale.soldAt = LocalDateTime.of(2026, 10, day, 12, 0);
        sale.quantity = new BigDecimal(qty); sale.grossTotal = new BigDecimal(gross);
        sale.netTotal = new BigDecimal(net); sale.discountPercent = new BigDecimal(discount);
        sale.description = "Channel price test"; sale.transactionType = "Verkoop";
        sale.firstSeenAt = sale.updatedAt = Instant.now(); sale.persistAndFlush();
        return sale;
    }
}
