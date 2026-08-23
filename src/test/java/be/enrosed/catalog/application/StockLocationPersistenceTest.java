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

        /* Broken and demo pieces leave the shelf under their own kind. */
        stock.takeOut(product.id, main.id(), 3, StockMovement.Kind.DAMAGED, "gevallen in het magazijn");
        stock.takeOut(product.id, main.id(), 1, StockMovement.Kind.DEMO, "klant Janssens");
        assertEquals(56, stock.quantityAt(product.id, main.id()), "60 on the shelf, 3 broken, 1 demo");
        assertThrows(BusinessRuleException.class,
                () -> stock.takeOut(product.id, main.id(), 5, StockMovement.Kind.SALE, "nee"),
                "only damaged or demo leave this way");
        stock.noteDamagedOnArrival(product.id, main.id(), 2, "PO-11");
        assertEquals(56, stock.quantityAt(product.id, main.id()), "broken on arrival never reached the shelf");

        List<StockMovement> book = products.stockMovements(product.id);
        assertEquals(9, book.size());
        assertEquals(StockMovement.Kind.DAMAGED, book.get(0).kind());
        assertEquals(-2, book.get(0).delta(), "counted as damage, stock untouched");
        assertEquals(56, book.get(0).quantityAfter());
        assertEquals(StockMovement.Kind.DEMO, book.get(1).kind());
        /* Newest first: damage on arrival, demo, damaged, then the stocktake and the transfer. */
        assertEquals(StockMovement.Kind.STOCKTAKE, book.get(3).kind());
        assertEquals(-5, book.get(3).delta());
        assertEquals(tica.id(), book.get(3).locationId());
        assertEquals(StockMovement.Kind.TRANSFER_IN, book.get(4).kind());
        assertEquals("Magazijn · bus van maandag", book.get(4).reference());
        assertEquals(StockMovement.Kind.TRANSFER_OUT, book.get(5).kind());
        assertEquals("TICA Aalsmeer · bus van maandag", book.get(5).reference());
        assertEquals(main.id(), book.get(8).locationId(), "the first receipt landed in the warehouse");
        assertEquals(tica.id(), book.get(7).locationId(), "the second receipt followed the order's door");

        /* Flip the switch: the stand counts for the website too. */
        stock.saveLocation(new StockLocation(tica.id(), tica.code(), tica.name(), tica.kind(), null,
                true, true, false, 1));
        assertEquals(91, products.get(product.id).stockQuantity(), "56 in the warehouse + 35 at the stand");

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
