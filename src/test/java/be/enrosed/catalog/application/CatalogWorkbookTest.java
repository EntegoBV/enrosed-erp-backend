package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogWorkbookTest {

    private FakeProducts repository;
    private CatalogWorkbook workbook;

    @BeforeEach
    void setUp() {
        repository = new FakeProducts();
        repository.add(product());
        workbook = workbookFor(repository);
    }

    @Test
    void exportIsAReadableAndGuidedNativeWorkbook() throws Exception {
        byte[] exported = workbook.export();

        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertNotNull(excel.getSheet("Producten"));
            assertNotNull(excel.getSheet("Vertalingen"));
            assertNotNull(excel.getSheet("Uitleg"));

            var products = excel.getSheet("Producten");
            assertEquals("SKU", products.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Productnaam", products.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Product breedte B (cm)", products.getRow(0).getCell(5).getStringCellValue());
            assertEquals("Product diepte D (cm)", products.getRow(0).getCell(6).getStringCellValue());
            assertEquals("Product hoogte H (cm)", products.getRow(0).getCell(7).getStringCellValue());
            assertEquals(12.5, products.getRow(1).getCell(5).getNumericCellValue());
            assertEquals(8.0, products.getRow(1).getCell(6).getNumericCellValue());
            assertEquals(25.0, products.getRow(1).getCell(7).getNumericCellValue());
            assertEquals(CellType.NUMERIC, products.getRow(1).getCell(5).getCellType());
            assertEquals(CellType.BLANK, products.getRow(1).getCell(13).getCellType());
            assertEquals("@", products.getRow(1).getCell(0).getCellStyle().getDataFormatString());
            assertFalse(products.getDataValidations().isEmpty(), "choice columns have dropdowns");
            assertNotNull(products.getPaneInformation(), "header and SKU stay in view");
            assertTrue(products.getCTWorksheet().getAutoFilter().getRef().startsWith("A1:"));
        }
    }

    @Test
    void legacyDimensionHeadingsStillImportWithoutMovingValues() throws Exception {
        byte[] edited = editedWorkbook(excel -> {
            var products = excel.getSheet("Producten");
            products.getRow(0).getCell(5).setCellValue("Product lengte (cm)");
            products.getRow(0).getCell(6).setCellValue("Product breedte (cm)");
            products.getRow(0).getCell(7).setCellValue("Product hoogte (cm)");
            products.getRow(1).getCell(5).setCellValue(31);
            products.getRow(1).getCell(6).setCellValue(22);
            products.getRow(1).getCell(7).setCellValue(44);
        });

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        Dimensions saved = repository.get("ENR-P01").dimensions();
        assertEquals(new BigDecimal("31"), saved.lengthCm());
        assertEquals(new BigDecimal("22"), saved.widthCm());
        assertEquals(new BigDecimal("44"), saved.heightCm());
    }

    @Test
    void variantSizeAndColourSwatchRoundTripAsProductMasterData() throws Exception {
        FakeProducts variantRepository = new FakeProducts();
        variantRepository.add(product().withVariantAttributes("Rood", "XL", "#A91F32"));
        CatalogWorkbook variantWorkbook = workbookFor(variantRepository);

        byte[] exported = variantWorkbook.export();
        byte[] edited;
        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(exported));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var products = excel.getSheet("Producten");
            assertEquals("Variantmaat", products.getRow(0).getCell(24).getStringCellValue());
            assertEquals("Kleurstaal (#RRGGBB)",
                    products.getRow(0).getCell(25).getStringCellValue());
            assertEquals("XL", products.getRow(1).getCell(24).getStringCellValue());
            assertEquals("#A91F32", products.getRow(1).getCell(25).getStringCellValue());
            products.getRow(1).getCell(24).setCellValue("XXL");
            products.getRow(1).getCell(25).setCellValue("#243253");
            excel.write(output);
            edited = output.toByteArray();
        }

        CatalogWorkbook.ImportResult result = variantWorkbook.importFrom(
                new ByteArrayInputStream(edited));

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals("XXL", variantRepository.get("ENR-P01").variantSize());
        assertEquals("#243253", variantRepository.get("ENR-P01").colourHex());
    }

    @Test
    void oneWorkbookUpdatesMasterDataAndTranslationsWithoutFreezingOldFallbackText()
            throws Exception {
        byte[] exported = workbook.export();
        byte[] edited;

        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(exported));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            excel.getSheet("Producten").getRow(1).getCell(1).setCellValue("Nieuwe basisnaam");

            var translations = excel.getSheet("Vertalingen");
            for (int rowIndex = 1; rowIndex <= translations.getLastRowNum(); rowIndex++) {
                if ("fr".equals(translations.getRow(rowIndex).getCell(1).getStringCellValue())) {
                    translations.getRow(rowIndex).getCell(2).setCellValue("Nom français");
                }
            }
            excel.write(output);
            edited = output.toByteArray();
        }

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(1, result.updatedProducts());
        assertEquals(1, result.updatedRows());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        Product saved = repository.get("ENR-P01");
        assertEquals("Nieuwe basisnaam", saved.name());
        assertEquals("Nom français", saved.nameIn(Language.FR));
        assertEquals("Nieuwe basisnaam", saved.nameIn(Language.EN),
                "untouched fallback cells must follow the new base name");
    }

    @Test
    void formulasAreReportedAndLeaveTheExistingFieldUntouched() throws Exception {
        byte[] exported = workbook.export();
        byte[] edited;

        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(exported));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            excel.getSheet("Producten").getRow(1).getCell(18).setCellFormula("1+1");
            excel.write(output);
            edited = output.toByteArray();
        }

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("formules")),
                result.problems().toString());
        assertEquals(new BigDecimal("25"), repository.get("ENR-P01").fixedSalesPriceEur());
    }

    @Test
    void blankNumericCellsKeepTheirNumericFormatForEditing() throws Exception {
        FakeProducts blankPriceRepository = new FakeProducts();
        blankPriceRepository.add(product(null));

        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(
                workbookFor(blankPriceRepository).export()))) {
            var fixedPrice = excel.getSheet("Producten").getRow(1).getCell(18);
            assertEquals(CellType.BLANK, fixedPrice.getCellType());
            assertEquals("0.00########", fixedPrice.getCellStyle().getDataFormatString());
        }
    }

    @Test
    void missingTranslationColumnIsRejectedBeforeAnyWrite() throws Exception {
        repository.add(productWithFrenchTranslation());
        byte[] edited = editedWorkbook(excel -> {
            excel.getSheet("Producten").getRow(1).getCell(1).setCellValue("Mag niet opslaan");
            excel.getSheet("Vertalingen").getRow(0).getCell(3)
                    .setCellValue("Verwijderde beschrijvingskolom");
        });

        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
                () -> workbook.importFrom(new ByteArrayInputStream(edited)));

        assertTrue(thrown.getMessage().contains("Beschrijving"), thrown.getMessage());
        assertEquals("Glazen roos", repository.get("ENR-P01").name());
        assertEquals("Fait main", repository.get("ENR-P01").descriptionIn(Language.FR));
    }

    @Test
    void missingLanguageRowIsRejectedWithoutReplacingStoredTranslations() throws Exception {
        repository.add(productWithFrenchTranslation());
        byte[] edited = editedWorkbook(excel -> {
            excel.getSheet("Producten").getRow(1).getCell(1).setCellValue("Mag niet opslaan");
            var translations = excel.getSheet("Vertalingen");
            for (int rowIndex = 1; rowIndex <= translations.getLastRowNum(); rowIndex++) {
                if ("fr".equals(translations.getRow(rowIndex).getCell(1).getStringCellValue())) {
                    translations.removeRow(translations.getRow(rowIndex));
                    break;
                }
            }
        });

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("ENR-P01") && problem.contains("fr")), result.problems().toString());
        assertEquals("Glazen roos", repository.get("ENR-P01").name());
        assertEquals("Fleur en verre", repository.get("ENR-P01").nameIn(Language.FR));
    }

    @Test
    void duplicateSkuAndLanguageRowIsRejectedBeforeAnyWrite() throws Exception {
        byte[] edited = editedWorkbook(excel -> {
            var translations = excel.getSheet("Vertalingen");
            translations.getRow(2).getCell(1).setCellValue("nl");
            excel.getSheet("Producten").getRow(1).getCell(1).setCellValue("Mag niet opslaan");
        });

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("staat dubbel")),
                result.problems().toString());
        assertEquals("Glazen roos", repository.get("ENR-P01").name());
    }

    @Test
    void invalidBarcodeIsReportedInsteadOfEnteringTheCatalogue() throws Exception {
        byte[] edited = editedWorkbook(excel ->
                excel.getSheet("Producten").getRow(1).getCell(13).setCellValue("123"));

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("Binnenbarcode")),
                result.problems().toString());
        assertEquals(Barcodes.none(), repository.get("ENR-P01").barcodes());
    }

    @Test
    void invalidMasterRowPreventsEveryTranslationWrite() throws Exception {
        repository.add(productWithFrenchTranslation());
        byte[] edited = editedWorkbook(excel -> {
            excel.getSheet("Producten").getRow(1).getCell(13).setCellValue("123");
            var translations = excel.getSheet("Vertalingen");
            for (int rowIndex = 1; rowIndex <= translations.getLastRowNum(); rowIndex++) {
                if ("fr".equals(translations.getRow(rowIndex).getCell(1).getStringCellValue())) {
                    translations.getRow(rowIndex).getCell(2).setCellValue("Ne doit pas être sauvegardé");
                }
            }
        });

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("Binnenbarcode")),
                result.problems().toString());
        assertEquals("Fleur en verre", repository.get("ENR-P01").nameIn(Language.FR));
        assertEquals(Barcodes.none(), repository.get("ENR-P01").barcodes());
    }

    @Test
    void negativeNumericValueIsReportedInsteadOfBeingStored() throws Exception {
        byte[] edited = editedWorkbook(excel ->
                excel.getSheet("Producten").getRow(1).getCell(5).setCellValue(-1));

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("kan niet negatief")),
                result.problems().toString());
        assertEquals(new BigDecimal("12.5"), repository.get("ENR-P01").dimensions().lengthCm());
    }

    @Test
    void zeroPiecesPerCartonIsRejectedInsteadOfSilentlyClamped() throws Exception {
        byte[] edited = editedWorkbook(excel ->
                excel.getSheet("Producten").getRow(1).getCell(11).setCellValue(0));

        CatalogWorkbook.ImportResult result = workbook.importFrom(new ByteArrayInputStream(edited));

        assertEquals(0, result.updatedProducts());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("Stuks per karton")), result.problems().toString());
        assertEquals(6, repository.get("ENR-P01").carton().piecesPerCarton());
    }

    @Test
    void extremeUsedRangeIsRejectedBeforeRowsAreAllocated() throws Exception {
        byte[] edited = editedWorkbook(excel ->
                excel.getSheet("Producten").createRow(100_001).createCell(0)
                        .setCellValue("ENR-P01"));

        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
                () -> workbook.importFrom(new ByteArrayInputStream(edited)));

        assertTrue(thrown.getMessage().contains("te veel gebruikte rijen"), thrown.getMessage());
        assertEquals("Glazen roos", repository.get("ENR-P01").name());
    }

    private byte[] editedWorkbook(Consumer<XSSFWorkbook> edit) throws Exception {
        try (XSSFWorkbook excel = new XSSFWorkbook(new ByteArrayInputStream(workbook.export()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            edit.accept(excel);
            excel.write(output);
            return output.toByteArray();
        }
    }

    private static CatalogWorkbook workbookFor(FakeProducts products) {
        ProductValidator validator = new ProductValidator(new BarcodeValidator());
        return new CatalogWorkbook(
                new ProductCsv(products, validator), new ProductTranslationCsv(products));
    }

    private static Product product() {
        return product(new BigDecimal("25"));
    }

    private static Product product(BigDecimal fixedSalesPrice) {
        return new Product(
                1L, "ENR-P01", "Glazen roos",
                new Dimensions(new BigDecimal("12.5"), new BigDecimal("8"), new BigDecimal("25")),
                "Rood", "Handgemaakt", 1L, 2L, true,
                Barcodes.none(), "0603905000",
                new Carton(new Dimensions(new BigDecimal("50"), new BigDecimal("40"),
                        new BigDecimal("30")), 6, new BigDecimal("8.5")),
                new BigDecimal("4.25"), Currency.USD, BigDecimal.ZERO,
                new BigDecimal("16"), "PO-1", new BigDecimal("25"),
                fixedSalesPrice, 12, List.of(), List.of());
    }

    private static Product productWithFrenchTranslation() {
        return product().withTexts(List.of(new ProductText(
                Language.FR, "Fleur en verre", "Fait main", "Rouge")));
    }

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
        public List<Product> findBySupplier(long supplierId) {
            return List.of();
        }

        @Override
        public Optional<Product> findById(long id) {
            return bySku.values().stream()
                    .filter(product -> product.id() != null && product.id() == id)
                    .findFirst();
        }

        @Override
        public Optional<Product> findBySku(String sku) {
            return Optional.ofNullable(bySku.get(sku));
        }

        @Override
        public Optional<Product> findByPublicHandle(String publicHandle) {
            return bySku.values().stream()
                    .filter(product -> java.util.Objects.equals(
                            product.publicHandle(), publicHandle))
                    .findFirst();
        }

        @Override
        public Product save(Product product) {
            bySku.put(product.sku(), product);
            return product;
        }

        @Override
        public void deleteById(long id) {}

        @Override
        public long countByCategory(long categoryId) { return 0; }

        @Override
        public long countByHsCode(String hsCode) { return 0; }

        @Override
        public long countBySupplier(long supplierId) { return 0; }
    }
}
