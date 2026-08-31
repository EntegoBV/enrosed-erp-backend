package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductSupplierNoteTest {

    @Test
    void compatibilityConstructionStartsWithoutSupplierNote() {
        assertNull(product().supplierNote());
    }

    @Test
    void everyCopyOperationCarriesTheSupplierNote() {
        Product source = product().withSupplierNote("Alleen leveren per volle omdoos");

        List<Product> copies = List.of(
                source.withSku("SKU-2"),
                source.withVariantAttributes("Wit", "XL", "#FFFFFF"),
                source.withPhotos(List.of()),
                source.withStockQuantity(12),
                source.withDemo(true),
                source.withActive(false),
                source.withCategoryId(99L),
                source.withLandedCost(null, null),
                source.withTexts(List.of()),
                source.withPublicationMetadata("family", "handle",
                        PublicationState.DRAFT, PublicationState.READY),
                source.withCanonicalIdentity(8L, "variant", null, 2, true));

        for (Product copy : copies) {
            assertEquals("Alleen leveren per volle omdoos", copy.supplierNote());
        }
    }

    private static Product product() {
        return new Product(
                1L, "SKU-1", "Product", Dimensions.empty(), null, null,
                2L, 3L, true, Barcodes.none(), null, Carton.empty(),
                null, Currency.USD, null, null, null, null, null, 0,
                List.of(), List.of());
    }
}
