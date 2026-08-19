package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPublicationTest {

    @Test
    void legacyProductsDefaultToPrivateDrafts() {
        Product product = legacyProduct();

        assertEquals(PublicationState.DRAFT, product.publicationState(CatalogChannel.WEBSITE));
        assertEquals(PublicationState.DRAFT, product.publicationState(CatalogChannel.ORDER_APP));
        assertTrue(!product.isPublishedToAnyPublicChannel());
    }

    @Test
    void readinessReportsEveryMissingPublicRequirementInDutch() {
        Product incomplete = new Product(
                1L, " ", "", Dimensions.empty(), null, " ", null, null, false,
                null, null, PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), null,
                new Carton(Dimensions.empty(), 0, BigDecimal.ZERO),
                null, Currency.USD, null, null, null, null, null,
                0, List.of(), List.of());

        assertEquals(List.of(
                "Product is niet actief",
                "SKU ontbreekt",
                "Naam ontbreekt",
                "Categorie ontbreekt",
                "Beschrijving ontbreekt",
                "Minstens één foto is verplicht",
                "Verkoopprijs ontbreekt of is niet positief",
                "Omdoos is ongeldig: vul positieve afmetingen en minstens 1 stuk per doos in",
                "Publieke handle ontbreekt"), incomplete.publicationIssues());
    }

    @Test
    void completeSkuIsReadyAndFixedSalesPriceWins() {
        Product product = readyProduct();

        assertTrue(product.publicationIssues().isEmpty(), product.publicationIssues().toString());
        assertEquals(new BigDecimal("42.50"), product.computedSalesPriceEur());

        List<Product> copies = List.of(
                product.withSku("ENR-P02"),
                product.withPhotos(product.photos()),
                product.withStockQuantity(99),
                product.withLandedCost(new BigDecimal("11"), "PO-2"),
                product.withTexts(List.of()));
        copies.forEach(copy -> {
            assertEquals(product.familyKey(), copy.familyKey());
            assertEquals(product.publicHandle(), copy.publicHandle());
            assertEquals(product.websiteStatus(), copy.websiteStatus());
            assertEquals(product.orderAppStatus(), copy.orderAppStatus());
        });
    }

    private static Product legacyProduct() {
        return new Product(
                1L, "ENR-P01", "Roos", new Dimensions(one(), one(), one()),
                "Rood", "Een mooie roos", 1L, 2L, true,
                Barcodes.none(), null,
                new Carton(new Dimensions(one(), one(), one()), 6, one()),
                one(), Currency.USD, BigDecimal.ZERO, new BigDecimal("10"), null,
                new BigDecimal("25"), null, 0, List.of(), List.of());
    }

    private static Product readyProduct() {
        return new Product(
                1L, "ENR-P01", "Roos", new Dimensions(one(), one(), one()),
                "Rood", "Een mooie roos", 1L, 2L, true,
                "rose-family", "rode-roos", PublicationState.READY, PublicationState.DRAFT,
                Barcodes.none(), "0603", new Carton(new Dimensions(one(), one(), one()), 6, one()),
                one(), Currency.USD, BigDecimal.ZERO, new BigDecimal("10"), "PO-1",
                new BigDecimal("25"), new BigDecimal("42.5"), 8,
                List.of(new Photo(9L, "private-key", "rose.jpg", "image/jpeg",
                        100, 10, 20, 0)), List.of());
    }

    private static BigDecimal one() {
        return BigDecimal.ONE;
    }
}
