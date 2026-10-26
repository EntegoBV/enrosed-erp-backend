package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchEntity;
import be.enrosed.catalog.adapter.out.persistence.StockBatchMoveEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.BatchCost;
import be.enrosed.catalog.domain.BatchSource;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.catalog.domain.StockBatch;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaging guard and the delete cleanup of batches (batches contract §3.5, test 13; build plan D6): once batches
 * are started, levels and batches are pieces of the product's current basis, so changing it is refused while any
 * pieces are left; a deleted product takes its batches and moves with it.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class StockBatchPackagingGuardTest {

    private static final String BATCH_REFUSAL =
            "Dit product heeft voorraad in batches; de voorraadbasis kan niet worden gewijzigd. Tel de voorraad eerst op 0.";
    private static final String LOOSE_REFUSAL =
            "Dit product heeft losse voorraad of TICA-voorraadboekingen; de vastgelegde voorraadbasis kan niet worden gewijzigd.";

    @Inject StockBatchService batches;
    @Inject StockService stock;
    @Inject ProductService products;
    @Inject ProductRepository repository;
    @Inject EntityManager entities;

    private final long base = 950_000_000L + ThreadLocalRandom.current().nextLong(1_000_000) * 100;

    @Test @TestTransaction
    void beforeTheStartAPackagingChangeWithStockIsAllowedAsToday() {
        long product = product(1);
        stock.setLevel(product, main(), 10, StockMovement.Kind.STOCKTAKE, "Telling");
        assertFalse(batches.started());

        assertDoesNotThrow(() -> repository.save(asDisplayOf(product, 12)));
        assertEquals(12, stock.unitPieces(product));
    }

    @Test @TestTransaction
    void whileStartedAnyLevelOrBatchWithPiecesRefusesTheChangeAndAllAtZeroAllowsIt() {
        long main = main();
        long product = product(1);
        batches.recordStart(0, 0, 0, 0, "test");
        stock.add(product, main, 10, StockMovement.Kind.PURCHASE_RECEIPT, "PO-GUARD",
                StockBatchContext.receipt(new BatchSource(base + 1, base + 11, "PO-GUARD", null, null, false,
                        LocalDate.of(2026, 9, 1), new BatchCost(StockBatch.CostState.CALCULATED,
                        new BigDecimal("2.0000"), null, null, null, null))));

        assertEquals(BATCH_REFUSAL, refusal(product), "a level and a batch hold pieces");

        stock.sell(product, main, 10, "F-GUARD");
        assertDoesNotThrow(() -> repository.save(asDisplayOf(product, 1)), "the same basis is no change");
        assertEquals(0, batches.totals(product).batchPieces());
        assertDoesNotThrow(() -> repository.save(asDisplayOf(product, 6)), "every level and batch at 0");
        assertEquals(6, stock.unitPieces(product));
        assertDoesNotThrow(() -> repository.save(asDisplayOf(product, 1)));

        stock.sell(product, main, 3, "F-NEGATIVE");
        assertEquals(-3, stock.quantityAt(product, main));
        assertEquals(BATCH_REFUSAL, refusal(product), "a negative level counts too");
    }

    @Test @TestTransaction
    void aPausedStartStillGuardsAndABatchAloneIsEnough() {
        long main = main();
        long product = product(1);
        batches.recordStart(0, 0, 0, 0, "test");
        stock.add(product, main, 8, StockMovement.Kind.PURCHASE_RECEIPT, "PO-PAUSE",
                StockBatchContext.receipt(new BatchSource(base + 2, base + 21, "PO-PAUSE", null, null, false,
                        LocalDate.of(2026, 9, 2), BatchCost.NONE)));
        batches.pause();
        stock.setLevel(product, main, 0, StockMovement.Kind.STOCKTAKE, "Telling op nul");
        assertEquals(0, stock.quantityAt(product, main));
        assertEquals(8, batches.totals(product).batchPieces(), "paused: the batch kept its pieces");

        assertEquals(BATCH_REFUSAL, refusal(product), "paused is still started; the batch alone refuses");
    }

    @Test @TestTransaction
    void loosePiecesStillRefuseWithTheExistingMessage() {
        long main = main();
        long display = product(12);
        stock.setLevel(display, main, 2, 5, StockMovement.Kind.STOCKTAKE, "Telling");
        assertEquals(LOOSE_REFUSAL, refusal(display), "before the start");

        batches.recordStart(0, 0, 0, 0, "test");
        assertEquals(LOOSE_REFUSAL, refusal(display), "the existing reason keeps its own message");
    }

    @Test @TestTransaction
    void deletingAProductRemovesItsBatchesAndMoves() {
        long main = main();
        long product = product(1);
        batches.recordStart(0, 0, 0, 0, "test");
        stock.setLevel(product, main, 4, StockMovement.Kind.STOCKTAKE, "Telling");
        stock.sell(product, main, 1, "F-DELETE");
        assertEquals(1, count(StockBatchEntity.class, product));
        assertEquals(2, count(StockBatchMoveEntity.class, product), "found by the count, then a sale");

        products.delete(product);
        entities.flush();

        assertEquals(0, count(StockBatchEntity.class, product));
        assertEquals(0, count(StockBatchMoveEntity.class, product));
        assertTrue(repository.findById(product).isEmpty());
    }

    /* ------------------------------------------------------------ helpers */

    private String refusal(long productId) {
        int other = stock.unitPieces(productId) == 12 ? 6 : 12;
        return assertThrows(BusinessRuleException.class, () -> repository.save(asDisplayOf(productId, other)))
                .getMessage();
    }

    /** The product sold per display of {@code pieces}; 1 means per piece without a display. */
    private Product asDisplayOf(long productId, int pieces) {
        Product product = products.get(productId);
        Packaging packaging = pieces > 1
                ? new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, pieces, SalesUnit.DISPLAY)
                : new Packaging(PackagingKind.NONE, Dimensions.empty(), null, null, SalesUnit.PIECE);
        return new Product(product.id(), product.sku(), product.name(), product.dimensions(), packaging,
                product.colour(), product.variantSize(), product.colourHex(), product.description(),
                product.categoryId(), product.supplierId(), product.supplierNote(), product.active(),
                product.familyId(), product.canonicalVariantKey(), product.canonicalBarcode(),
                product.variantPosition(), product.inventoryKnown(), product.familyKey(), product.publicHandle(),
                product.websiteStatus(), product.orderAppStatus(), product.barcodes(), product.hsCode(),
                product.carton(), product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                product.landedCostEur(), product.landedCostSource(), product.markupPct(),
                product.fixedSalesPriceEur(), product.stockQuantity(), product.photos(), product.texts(),
                product.demo());
    }

    private long product(int unitPieces) {
        ProductEntity product = new ProductEntity();
        product.sku = "GUARD-" + UUID.randomUUID();
        product.name = "Guard test product";
        product.active = true;
        product.inventoryKnown = false;
        product.stockQuantity = 0;
        product.landedCostEur = new BigDecimal("1.5000");
        if (unitPieces > 1) {
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
            product.packagingPiecesPerUnit = unitPieces;
        }
        entities.persist(product);
        entities.flush();
        return product.id;
    }

    private long main() {
        return stock.mainLocation().id();
    }

    private long count(Class<?> entity, long productId) {
        return entities.createQuery("select count(e) from " + entity.getSimpleName() + " e where e.productId = :p",
                Long.class).setParameter("p", productId).getSingleResult();
    }
}
