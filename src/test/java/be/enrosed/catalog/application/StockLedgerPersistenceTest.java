package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.StockMovement;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class StockLedgerPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ProductService products;

    @Test
    @TestTransaction
    void everyStockChangeLandsInTheBookNewestFirstWithItsOrigin() {
        ProductEntity product = new ProductEntity();
        product.sku = "SKU-LEDGER-1";
        product.name = "Ledger rose";
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

        products.adjustStock(product.id, 120, "PO-7");
        products.setStock(product.id, 100);

        List<StockMovement> book = products.stockMovements(product.id);
        assertEquals(2, book.size());

        StockMovement manual = book.get(0);
        assertEquals(StockMovement.Kind.MANUAL_CORRECTION, manual.kind());
        assertEquals(-20, manual.delta(), "a recount from 120 down to 100");
        assertEquals(100, manual.quantityAfter());
        assertEquals("systeem", manual.actor(), "no signed-in user in this test");

        StockMovement receipt = book.get(1);
        assertEquals(StockMovement.Kind.PURCHASE_RECEIPT, receipt.kind());
        assertEquals(120, receipt.delta());
        assertEquals(120, receipt.quantityAfter());
        assertEquals("PO-7", receipt.reference());
        assertEquals("Inkooporder ontvangen", receipt.kind().dutchLabel());

        /* Striking a line cleans the book but leaves the count alone. */
        products.deleteStockMovement(product.id, manual.id());
        assertEquals(1, products.stockMovements(product.id).size());
        assertEquals(100, products.get(product.id).stockQuantity());
        assertThrows(be.enrosed.shared.NotFoundException.class,
                () -> products.deleteStockMovement(product.id + 1, receipt.id()),
                "a line belongs to its product; another product's id does not reach it");
    }
}
