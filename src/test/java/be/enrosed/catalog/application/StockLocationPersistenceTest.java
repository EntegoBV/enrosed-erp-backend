package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StockLocationPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject StockService stock;
    @Inject ProductService products;

    @Test
    @TestTransaction
    void theWebsiteFigureCountsOnlyTheWarehouseWhileTheBookTellsTheWholeStory() {
        ProductEntity product = product("SKU-LOC-1");
        StockLocation main = stock.mainLocation();
        StockLocation tica = stock.saveLocation(new StockLocation(null, null, "TICA Aalsmeer",
                StockLocation.Kind.SALES_POINT, null, true, false, false, 1));

        /* A receipt lands in the warehouse unless the order names another door. */
        products.adjustStock(product.id, 100, "PO-9");
        assertEquals(100, stock.quantityAt(product.id, main.id()));
        products.receiveStock(product.id, 10, "PO-10", tica.id());
        assertEquals(10, stock.quantityAt(product.id, tica.id()));
        stock.setLevel(product.id, tica.id(), 0, StockMovement.Kind.MANUAL_CORRECTION, "reset");
        assertEquals(100, products.get(product.id).stockQuantity());

        /* Forty pieces go to the stand: the website figure drops, the total does not. */
        stock.transfer(product.id, main.id(), tica.id(), 40, "bus van maandag");
        assertEquals(60, stock.quantityAt(product.id, main.id()));
        assertEquals(40, stock.quantityAt(product.id, tica.id()));
        assertEquals(60, products.get(product.id).stockQuantity(), "TICA does not count for the website");
        assertEquals(100, stock.levelsFor(product.id).stream().mapToInt(StockLevel::quantity).sum());

        /* More than lies there cannot leave. */
        BusinessRuleException tooMany = assertThrows(BusinessRuleException.class,
                () -> stock.transfer(product.id, main.id(), tica.id(), 61, null));
        assertTrue(tooMany.getMessage().contains("maar 60 stuks"), tooMany.getMessage());

        /* A recount at the stand. */
        stock.setLevel(product.id, tica.id(), 35, StockMovement.Kind.STOCKTAKE, "Telling TICA");

        List<StockMovement> book = products.stockMovements(product.id);
        assertEquals(6, book.size());
        assertEquals(StockMovement.Kind.STOCKTAKE, book.get(0).kind());
        assertEquals(-5, book.get(0).delta());
        assertEquals(tica.id(), book.get(0).locationId());
        assertEquals(StockMovement.Kind.TRANSFER_IN, book.get(1).kind());
        assertEquals("Magazijn · bus van maandag", book.get(1).reference());
        assertEquals(StockMovement.Kind.TRANSFER_OUT, book.get(2).kind());
        assertEquals("TICA Aalsmeer · bus van maandag", book.get(2).reference());
        assertEquals(main.id(), book.get(5).locationId(), "the first receipt landed in the warehouse");
        assertEquals(tica.id(), book.get(4).locationId(), "the second receipt followed the order's door");

        /* Flip the switch: the stand counts for the website too. */
        stock.saveLocation(new StockLocation(tica.id(), tica.code(), tica.name(), tica.kind(), null,
                true, true, false, 1));
        assertEquals(95, products.get(product.id).stockQuantity());

        /* A location holding stock cannot be deleted; the warehouse never can. */
        assertThrows(BusinessRuleException.class, () -> stock.deleteLocation(tica.id()));
        assertThrows(BusinessRuleException.class, () -> stock.deleteLocation(main.id()));
    }

    private ProductEntity product(String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Located rose";
        product.active = true;
        product.inventoryKnown = false;
        product.stockQuantity = 0;
        product.piecesPerCarton = 6;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }
}
