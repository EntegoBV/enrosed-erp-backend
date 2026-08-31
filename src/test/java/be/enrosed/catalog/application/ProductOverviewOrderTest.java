package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductOverviewOrderTest {

    @Test
    void followsCategoryFamilyAndCanonicalVariantPosition() {
        Product lateWhite = product(12L, 20L, "Late family", "White", 1, "L-W");
        Product earlyWhite = product(22L, 10L, "Early family", "White", 1, "E-W");
        Product earlyRed = product(21L, 10L, "Early family", "Red", 0, "E-R");
        Product firstCategory = product(31L, 30L, "First category", "Blue", 0, "F-B");

        Map<Long, ProductOverviewOrder.FamilyRank> ranks = Map.of(
                10L, new ProductOverviewOrder.FamilyRank(2, 3, "Early family"),
                20L, new ProductOverviewOrder.FamilyRank(2, 8, "Late family"),
                30L, new ProductOverviewOrder.FamilyRank(1, 9, "First category"));

        List<Long> orderedIds = List.of(lateWhite, earlyWhite, earlyRed, firstCategory).stream()
                .sorted(ProductOverviewOrder.comparator(ranks))
                .map(Product::id)
                .toList();

        assertEquals(List.of(31L, 21L, 22L, 12L), orderedIds);
    }

    private static Product product(
            long id, long familyId, String name, String colour, int variantPosition, String sku) {
        return new Product(
                id, sku, name, Dimensions.empty(), colour, null, null, null, true,
                familyId, null, null, variantPosition, true, "family-" + familyId, null,
                null, null, Barcodes.none(), null, Carton.empty(), BigDecimal.ONE,
                Currency.USD, BigDecimal.ZERO, null, null, BigDecimal.ZERO, null,
                0, List.of(), List.of());
    }
}
