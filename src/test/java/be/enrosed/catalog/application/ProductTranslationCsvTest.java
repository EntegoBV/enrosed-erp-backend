package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exchange with the translation file.
 *
 * The danger is not in the ordinary cases but at the edges: a semicolon in
 * a description tears the file apart, and an export read back in unchanged
 * must do nothing instead of filling every language with the Dutch text.
 */
class ProductTranslationCsvTest {

    private FakeProducts products;
    private ProductTranslationCsv csv;

    @BeforeEach
    void setUp() {
        products = new FakeProducts();
        csv = new ProductTranslationCsv(products);
    }

    @Test
    @DisplayName("de export zet per product een rij per taal, met de basiswaarden als vertrekpunt")
    void exportsRowPerLanguage() {
        products.add(product(1L, "ENR-P01", "Glass flower", "Rood", "Handgemaakt"));

        String text = new String(csv.export(), StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();

        assertEquals("sku;taal;naam;beschrijving;kleur", stripBom(lines.get(0)));
        assertEquals(1 + Language.values().length, lines.size(), "kop plus een rij per taal");
        assertEquals("ENR-P01;nl;Glass flower;Handgemaakt;Rood", lines.get(1));
        assertEquals("ENR-P01;fr;Glass flower;Handgemaakt;Rood", lines.get(2));
    }

    @Test
    @DisplayName("een puntkomma in de beschrijving trekt het bestand niet uit elkaar")
    void quotesSeparators() {
        products.add(product(1L, "ENR-P01", "Glass flower", "Rood", "Groot; met doos"));

        String text = new String(csv.export(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"Groot; met doos\""), text);

        /* And it comes back out intact. */
        csv.importFrom(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Groot; met doos", products.get("ENR-P01").description());
    }

    @Test
    @DisplayName("de export ongewijzigd terugladen verandert niets")
    void reimportOfUntouchedExportChangesNothing() {
        products.add(product(1L, "ENR-P01", "Glass flower", "Rood", "Handgemaakt"));

        byte[] exported = csv.export();
        ProductTranslationCsv.ImportResult result =
                csv.importFrom(new ByteArrayInputStream(exported));

        assertEquals(1, result.updatedProducts());
        assertEquals(0, result.updatedRows(), "niets is echt vertaald");
        assertTrue(products.get("ENR-P01").texts().isEmpty());
    }

    @Test
    @DisplayName("ingevulde vertalingen komen op het product terecht")
    void importsTranslations() {
        products.add(product(1L, "ENR-P01", "Glass flower", "Rood", "Handgemaakt"));

        String file = """
                sku;taal;naam;beschrijving;kleur
                ENR-P01;fr;Fleur en verre;Fait main;Rouge
                ENR-P01;en;Glass flower;Handmade;Red
                """;
        ProductTranslationCsv.ImportResult result =
                csv.importFrom(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.updatedProducts());
        assertTrue(result.problems().isEmpty(), result.problems().toString());

        Product saved = products.get("ENR-P01");
        assertEquals("Fleur en verre", saved.nameIn(Language.FR));
        assertEquals("Rouge", saved.colourIn(Language.FR));
        assertEquals("Handmade", saved.descriptionIn(Language.EN));

        /* The English name equalled the base name, so it does not count as a translation. */
        ProductText english = saved.textIn(Language.EN);
        assertNull(english.name());

        /* Where nothing is set, it falls back to the base. */
        assertEquals("Glass flower", saved.nameIn(Language.DE));
    }

    @Test
    @DisplayName("onbekende SKU's en talen worden gemeld, niet stil overgeslagen")
    void reportsProblems() {
        products.add(product(1L, "ENR-P01", "Glass flower", "Rood", null));

        String file = """
                sku;taal;naam;beschrijving;kleur
                ENR-P99;fr;Fleur;;Rouge
                ENR-P01;xx;Fleur;;Rouge
                """;
        ProductTranslationCsv.ImportResult result =
                csv.importFrom(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, result.updatedProducts());
        assertEquals(2, result.problems().size(), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("ENR-P99")));
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("xx")));
    }

    @Test
    @DisplayName("een bestand met de verkeerde kolommen wordt geweigerd")
    void rejectsWrongHeader() {
        String file = "product;language\nENR-P01;fr\n";
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                be.enrosed.shared.BusinessRuleException.class,
                () -> csv.importFrom(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8))));
        assertTrue(thrown.getMessage().contains("sku"), thrown.getMessage());
    }

    /* ---------------------------------------------------------- hulpstukken */

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }

    private static Product product(Long id, String sku, String name, String colour, String description) {
        return new Product(id, sku, name, new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                colour, description, null, null, true,
                Barcodes.none(), "0603905000",
                new Carton(new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), 6, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());
    }

    /** Just enough repository to test the exchange without a database. */
    private static final class FakeProducts implements ProductRepository {
        private final Map<String, Product> bySku = new LinkedHashMap<>();

        void add(Product product) {
            bySku.put(product.sku(), product);
        }

        Product get(String sku) {
            return bySku.get(sku);
        }

        @Override
        public List<Product> findAll() {
            return new ArrayList<>(bySku.values());
        }

        @Override
        public Optional<Product> findById(long id) {
            return bySku.values().stream().filter(p -> p.id() != null && p.id() == id).findFirst();
        }

        @Override
        public Optional<Product> findBySku(String sku) {
            return Optional.ofNullable(bySku.get(sku));
        }

        @Override
        public Product save(Product product) {
            bySku.put(product.sku(), product);
            return product;
        }

        @Override
        public void deleteById(long id) {
            findById(id).ifPresent(product -> bySku.remove(product.sku()));
        }

        @Override
        public long countByCategory(long categoryId) {
            return 0;
        }

        @Override
        public long countBySupplier(long supplierId) {
            return 0;
        }

        @Override
        public long countByHsCode(String hsCode) {
            return 0;
        }

        @Override
        public List<Product> findBySupplier(long supplierId) {
            return List.of();
        }
    }
}
