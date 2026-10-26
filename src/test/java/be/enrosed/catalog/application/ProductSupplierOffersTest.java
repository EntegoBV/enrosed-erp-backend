package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who makes what: the rows of one supplier plus a legacy offer for products whose own supplier it is. */
class ProductSupplierOffersTest {

    private static final long A = 10L;
    private static final long B = 20L;

    @Test
    void rowsOfTheSupplierAreOffersAlsoWhenInactive() {
        Product dome = product(1L, A, "2.35");
        Product vase = product(2L, A, "4.00");
        ProductSupplier domeAtB = row(11L, 1L, B, false, true, "2.10");
        ProductSupplier vaseAtB = row(12L, 2L, B, false, false, "3.80");

        Map<Long, ProductSupplier> offers = ProductSupplierOffers.of(B, List.of(dome, vase), List.of(domeAtB, vaseAtB));

        assertEquals(List.of(1L, 2L), List.copyOf(offers.keySet()));
        assertSame(domeAtB, offers.get(1L));
        assertSame(vaseAtB, offers.get(2L));
        assertFalse(offers.get(2L).active(), "an inactive row is still an offer");
    }

    @Test
    void aProductOfItsOwnSupplierWithoutARowGetsALegacyOfferFromTheProduct() {
        Product legacy = product(1L, A, "2.35").withSupplierNote("Glass 2 mm");
        Product withOtherRows = product(2L, A, "4.00");
        ProductSupplier otherSupplierRow = row(21L, 2L, B, false, true, "3.90");

        Map<Long, ProductSupplier> offers = ProductSupplierOffers.of(A, List.of(legacy, withOtherRows),
                List.of(otherSupplierRow));

        ProductSupplier offer = offers.get(1L);
        assertNull(offer.id());
        assertTrue(offer.legacy());
        assertTrue(offer.preferred());
        assertTrue(offer.active());
        assertEquals(A, offer.supplierId());
        assertEquals(0, new BigDecimal("2.35").compareTo(offer.exwPrice()));
        assertEquals(Currency.USD, offer.exwCurrency());
        assertEquals(0, new BigDecimal("0.10").compareTo(offer.extraUnitCost()));
        assertEquals("Glass 2 mm", offer.supplierNote());
        assertTrue(offers.get(2L).legacy(), "rows of another supplier do not hide the product's own supplier");
        assertEquals(A, offers.get(2L).supplierId());
    }

    @Test
    void aProductWithARowOfTheSupplierGetsTheRowNeverALegacyOffer() {
        Product dome = product(1L, A, "2.35");
        ProductSupplier own = row(11L, 1L, A, true, true, "2.30");

        Map<Long, ProductSupplier> offers = ProductSupplierOffers.of(A, List.of(dome), List.of(own));

        assertSame(own, offers.get(1L));
        assertFalse(offers.get(1L).legacy());
    }

    @Test
    void orphanRowsAndProductsOfOtherSuppliersAreIgnored() {
        Product other = product(1L, A, "2.35");
        ProductSupplier orphan = row(31L, 99L, B, false, true, "1.00");

        Map<Long, ProductSupplier> offers = ProductSupplierOffers.of(B, List.of(other), List.of(orphan));

        assertTrue(offers.isEmpty());
    }

    static Product product(long id, Long supplierId, String exw) {
        return new Product(id, "SKU-" + id, "Product " + id, Dimensions.empty(), "Rood", null, null, supplierId,
                true, Barcodes.none(), null, Carton.empty(),
                exw == null ? null : new BigDecimal(exw), Currency.USD, new BigDecimal("0.10"),
                null, null, null, null, 0, List.of(), List.of());
    }

    static ProductSupplier row(long id, long productId, long supplierId, boolean preferred, boolean active,
                               String exw) {
        Instant at = Instant.parse("2026-10-25T19:02:11.123456Z");
        return new ProductSupplier(id, productId, supplierId, preferred, active, null, null,
                new BigDecimal(exw), Currency.USD, null, null, null, null, at, at);
    }
}
