package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicCatalogDtoTest {

    @Test
    void resolvesRequestedLanguageAndNeverSerializesInternalCommercialData() throws Exception {
        Product product = sensitiveProduct();
        Category category = new Category(
                7L, "ROSES", "Rozen", "Geconserveerde rozen", 1,
                "Signature displays", 42L);
        PublicCatalogDto.PublicProductDto item = PublicCatalogDto.product(
                product, category, Language.EN, "https://erp.example.test/");
        PublicCatalogDto catalog = new PublicCatalogDto(
                CatalogChannel.WEBSITE, Language.EN, List.of(item));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(catalog);
        JsonNode publicProduct = mapper.readTree(json).path("products").get(0);

        assertEquals("English rose", publicProduct.path("name").asText());
        assertEquals("English public description", publicProduct.path("description").asText());
        assertEquals("Red", publicProduct.path("colour").asText());
        assertEquals(0, new BigDecimal("35.00")
                .compareTo(publicProduct.path("salesPriceEur").decimalValue()));
        assertEquals("IN_STOCK", publicProduct.path("availability").asText());
        assertEquals("https://erp.example.test/api/v1/public/catalog/products/1/photos/9",
                publicProduct.path("photos").get(0).path("url").asText());

        List<String> forbiddenFields = List.of(
                "supplierId", "supplierNote", "exwPrice", "exwCurrency", "extraUnitCost",
                "landedCostEur", "landedCostSource", "markupPct", "fixedSalesPriceEur",
                "hsCode", "barcodes", "barcodeInner", "barcodeOuter", "stockQuantity",
                "storageKey", "originalFilename", "texts", "publicationIssues");
        forbiddenFields.forEach(field -> assertFalse(publicProduct.has(field), field + " leaked"));

        assertFalse(json.contains("vendor-secret-key"), json);
        assertFalse(json.contains("supplier-private-name.jpg"), json);
        assertFalse(json.contains("PO-SECRET"), json);
        assertFalse(json.contains("supplier secret"), json);
        assertTrue(publicProduct.has("familyKey"));
        assertTrue(publicProduct.has("publicHandle"));
        assertTrue(publicProduct.has("category"));
        assertTrue(publicProduct.has("dimensions"));
        assertTrue(publicProduct.has("carton"));
        assertEquals("Signature displays",
                publicProduct.path("category").path("mobileName").asText());
        assertEquals(42L, publicProduct.path("category").path("featuredProductId").asLong());
    }

    private static Product sensitiveProduct() {
        return new Product(
                1L, "ENR-P01", "Nederlandse roos",
                new Dimensions(new BigDecimal("10"), new BigDecimal("11"), new BigDecimal("12")),
                "Rood", "Nederlandse publieke beschrijving", 7L,
                999L, true, "rose-family", "english-rose",
                PublicationState.PUBLISHED, PublicationState.DRAFT,
                new Barcodes("1234567890128", "12345678901231"), "0603.90.00",
                new Carton(new Dimensions(new BigDecimal("50"), new BigDecimal("40"),
                        new BigDecimal("30")), 6, new BigDecimal("12.5")),
                new BigDecimal("4.12"), Currency.CNY, new BigDecimal("0.25"),
                new BigDecimal("18.3456"), "PO-SECRET", new BigDecimal("91"),
                new BigDecimal("35"), 127,
                List.of(new Photo(9L, "vendor-secret-key", "supplier-private-name.jpg",
                        "image/jpeg", 654321, 1200, 800, 0)),
                List.of(new ProductText(Language.EN, "English rose",
                        "English public description", "Red")))
                .withSupplierNote("supplier secret");
    }
}
