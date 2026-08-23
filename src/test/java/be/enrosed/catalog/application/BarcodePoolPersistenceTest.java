package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
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

@QuarkusTest
class BarcodePoolPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject BarcodePoolService pool;

    @Test
    @TestTransaction
    void pastedCodesAreSortedIntoFreeInvalidAndTaken() {
        ProductEntity product = new ProductEntity();
        product.sku = "SKU-POOL-1";
        product.name = "Pool rose";
        product.active = true;
        product.barcodeInner = "5410000000019";
        product.piecesPerCarton = 6;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();

        String pasted = "5410000000026, 5410000000033 5410000000019 (al op een product) 1234 5410000000026";
        BarcodePoolService.Intake intake = pool.add(pasted);

        assertEquals(List.of("5410000000026", "5410000000033"), intake.added());
        assertEquals(List.of("1234"), intake.invalid());
        assertEquals(List.of("5410000000019"), intake.inUse());
        assertEquals(List.of(), intake.duplicate(), "the same code pasted twice is taken once");
        assertEquals(2, pool.count());

        /* Looking at the next code does not strike it: tap it three times, same code. */
        assertEquals("5410000000026", pool.next());
        assertEquals("5410000000026", pool.next());
        assertEquals(List.of("5410000000026", "5410000000033"), pool.free());

        /* Saving a product carrying the code does. */
        pool.consume("5410000000026", null, "");
        assertEquals(List.of("5410000000033"), pool.free());
        pool.consume("5410000000033");
        BusinessRuleException empty = assertThrows(BusinessRuleException.class, pool::next);
        assertEquals("Geen vrije EAN-codes meer in de lijst; voeg er eerst toe onder Instellingen",
                empty.getMessage());
    }
}
