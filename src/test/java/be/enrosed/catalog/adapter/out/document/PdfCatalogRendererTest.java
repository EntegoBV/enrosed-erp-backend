package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.enrosed.catalog.application.CatalogExportServiceTest.product;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

@QuarkusTest
class PdfCatalogRendererTest {

    @Inject PdfCatalogRenderer renderer;
    @Inject PhotoStorage photoStorage;

    @Test
    void simpleAndBrochureUseDistinctBrandedTemplatesWithoutInternalAuditCopy() throws Exception {
        byte[] webp = java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                "/images/soap-roos-in-box-480.webp")).readAllBytes();
        String key = "sha256-" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(webp)) + ".webp";
        PhotoStorage.Stored stored = photoStorage.storeKnown(
                key, "soap-roos-in-box-480.webp", "image/webp", webp);
        Photo fixture = new Photo(1L, key, "soap-roos-in-box-480.webp", "image/webp",
                stored.sizeBytes(), stored.widthPx(), stored.heightPx(), 0);
        CatalogExportService.Model simple = withPhoto(
                model(1, CatalogExportService.Layout.SIMPLE), fixture);
        CatalogExportService.Model brochure = withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), fixture);

        String simpleHtml = renderer.renderHtml(simple);
        String brochureHtml = renderer.renderHtml(brochure);

        assertTrue(simpleHtml.contains("#120c0a"));
        assertTrue(simpleHtml.contains("SKU-1"));
        assertTrue(simpleHtml.contains("data:image/jpeg;base64,"));
        assertTrue(brochureHtml.contains("A lasting collection"));
        assertTrue(brochureHtml.contains("Product B × D × H"));
        assertFalse(brochureHtml.toLowerCase().contains("candidate"));
        assertFalse(brochureHtml.toLowerCase().contains("provenance"));
        assertFalse(brochureHtml.toLowerCase().contains("confidence"));
        assertFalse(brochureHtml.toLowerCase().contains("dashboard"));
        assertFalse(brochureHtml.toLowerCase().contains("canonical"));
        assertTrue(brochureHtml.contains("1 FAMILY · 1 SELECTED VARIANT"));
        assertTrue(simpleHtml.contains("1 item"));

        CatalogDocumentRenderer.Document simplePdf = renderer.render(simple);
        CatalogDocumentRenderer.Document brochurePdf = renderer.render(brochure);
        assertPdf(simplePdf);
        assertPdf(brochurePdf);
        assertEquals("enrosed-catalogus.pdf", simplePdf.filename());
        assertEquals("enrosed-wholesale-brochure.pdf", brochurePdf.filename());
        try (PDDocument pdf = Loader.loadPDF(simplePdf.content())) {
            assertEquals(1, pdf.getNumberOfPages());
        }
        try (PDDocument pdf = Loader.loadPDF(brochurePdf.content())) {
            assertEquals(8, pdf.getNumberOfPages());
            String extracted = new PDFTextStripper().getText(pdf);
            assertTrue(extracted.contains("Confirm"));
            assertTrue(extracted.contains("gifting"));
        }

        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("simple.pdf"), simplePdf.content());
        Files.write(qa.resolve("brochure.pdf"), brochurePdf.content());
    }

    @Test
    void allFiftySevenSelectedSkusStayWithinABoundedRenderBudget() {
        assertTimeout(Duration.ofSeconds(45), () -> {
            CatalogDocumentRenderer.Document simple = renderer.render(
                    model(57, CatalogExportService.Layout.SIMPLE));
            CatalogDocumentRenderer.Document brochure = renderer.render(
                    model(57, CatalogExportService.Layout.BROCHURE));
            assertPdf(simple);
            assertPdf(brochure);
        });
    }

    private static CatalogExportService.Model model(
            int productCount, CatalogExportService.Layout layout) {
        Category category = new Category(
                1L, "counter", "Counter Displays", "Retail-ready products", 0);
        List<Product> products = new ArrayList<>();
        List<CatalogExportService.FamilyGroup> groups = new ArrayList<>();
        long id = 1;
        int familyCount = (productCount + 2) / 3;
        for (int familyIndex = 0; familyIndex < familyCount; familyIndex++) {
            List<Product> variants = new ArrayList<>();
            for (int variant = 0; variant < 3 && products.size() < productCount; variant++) {
                Product product = product(id, "SKU-" + id, 100L + familyIndex, 1L, variant)
                        .withVariantAttributes(variant == 0 ? "Rood" : variant == 1 ? "Roos" : "Wit",
                                variant == 0 ? "Small" : variant == 1 ? "Medium" : "Large",
                                variant == 0 ? "#9D263A" : variant == 1 ? "#D28AA0" : "#F0E9DF");
                variants.add(product);
                products.add(product);
                id++;
            }
            CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                    100L + familyIndex, "family-" + familyIndex,
                    "family-" + familyIndex, 1L, "counter", "Counter Displays", 0,
                    familyIndex, "Compact Red " + (familyIndex + 1),
                    "A lasting collection for gift-ready retail.",
                    "A refined presentation with selected colour and size variants.",
                    "Counter display", List.of("No daily water", "Gift-ready presentation"),
                    null, List.of(), List.of(), List.of());
            groups.add(new CatalogExportService.FamilyGroup(
                    family, List.copyOf(variants), category, false));
        }
        CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                true, true, true, true, true,
                "A lasting collection", "Ready for retail.");
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, true, false, 0, "ENROSED Wholesale", null, "en", layout, options);
        Map<Long, Category> categories = new LinkedHashMap<>();
        categories.put(category.id(), category);
        return new CatalogExportService.Model(products, categories, groups, request);
    }

    private static CatalogExportService.Model withPhoto(
            CatalogExportService.Model model, Photo photo) {
        Product pictured = model.products().getFirst().withPhotos(List.of(photo));
        CatalogExportService.FamilyGroup oldGroup = model.families().getFirst();
        CatalogExportService.FamilyGroup group = new CatalogExportService.FamilyGroup(
                oldGroup.content(), List.of(pictured), oldGroup.category(), oldGroup.synthetic());
        CatalogExportService.Request oldRequest = model.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), oldRequest.includePrices(), true, 2,
                oldRequest.title(), oldRequest.intro(), oldRequest.language(),
                oldRequest.layout(), oldRequest.brochure());
        return new CatalogExportService.Model(
                List.of(pictured), model.categoriesById(), List.of(group), request);
    }

    private static void assertPdf(CatalogDocumentRenderer.Document document) {
        assertEquals("application/pdf", document.contentType());
        assertTrue(document.content().length > 1_000);
        assertTrue(new String(document.content(), 0, 4, StandardCharsets.US_ASCII)
                .equals("%PDF"));
    }
}
