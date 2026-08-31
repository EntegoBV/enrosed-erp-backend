package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Csv;
import be.enrosed.shared.Language;
import be.enrosed.shared.LocalizationIncompleteException;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.qute.Engine;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Variant;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Generates the 16 delivery PDFs without starting Quarkus or an HTTP listener. */
class PdfCatalogStandaloneQaTest {

    @Test
    void writesAndValidatesSimpleAndBrochureForEverySupportedLocale() throws Exception {
        String configured = System.getProperty("catalog.qa.output");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set -Dcatalog.qa.output to create delivery QA PDFs");
        Path output = Path.of(configured).toAbsolutePath();
        Files.createDirectories(output);

        byte[] image = resource("catalog-assets/counter-bowl-retail.jpg");
        PdfCatalogRenderer renderer = renderer(image);
        Photo photo = new Photo(90L, "qa-counter.jpg", "qa-counter.jpg", "image/jpeg",
                image.length, 1536, 1024, 0);

        int written = 0;
        for (Language language : Language.values()) {
            for (CatalogExportService.Layout layout : CatalogExportService.Layout.values()) {
                CatalogDocumentRenderer.Document document = renderer.render(
                        PdfCatalogRendererTest.localizedQaModel(language, layout, photo));
                String stem = layout.name().toLowerCase() + "-" + language.code();
                Path target = output.resolve("enrosed-catalog-" + stem + ".pdf");
                Files.write(target, document.content());
                assertTrue(document.content().length > 20_000, stem);
                assertEquals("%PDF", new String(document.content(), 0, 4,
                        StandardCharsets.US_ASCII));
                try (PDDocument pdf = Loader.loadPDF(document.content())) {
                    assertEquals(layout == CatalogExportService.Layout.SIMPLE ? 1 : 7,
                            pdf.getNumberOfPages(), stem);
                    if (layout == CatalogExportService.Layout.BROCHURE) {
                        for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                            assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                                    > pdf.getPage(page).getMediaBox().getWidth(),
                                    stem + " page " + (page + 1));
                        }
                    }
                    String extracted = new PDFTextStripper().getText(pdf);
                    assertTrue(extracted.contains("B × D × H"), stem);
                    assertTrue(extracted.contains(
                            PdfCatalogRendererTest.localizedFamilyName(language)), stem);
                    String lower = extracted.toLowerCase(java.util.Locale.ROOT);
                    assertFalse(lower.contains("dashboard"), stem);
                    assertFalse(lower.contains("canonical"), stem);
                    assertFalse(lower.contains("provenance"), stem);
                    assertFalse(lower.contains("candidate"), stem);
                }
                written++;
            }
        }
        assertEquals(16, written);
        try (var files = Files.list(output)) {
            assertEquals(16, files.filter(path -> path.getFileName().toString()
                    .startsWith("enrosed-catalog-") && path.toString().endsWith(".pdf")).count());
        }
    }

    @Test
    void strictBrochureOnlyRequiresVariantNameWhenTheStandaloneCardRendersIt()
            throws Exception {
        byte[] image = resource("catalog-assets/counter-bowl-retail.jpg");
        PdfCatalogRenderer renderer = renderer(image);
        Photo photo = new Photo(91L, "qa-counter.jpg", "qa-counter.jpg", "image/jpeg",
                image.length, 1536, 1024, 0);
        CatalogExportService.Model complete = PdfCatalogRendererTest.localizedQaModel(
                Language.FR, CatalogExportService.Layout.BROCHURE, photo);
        Product source = complete.products().getFirst();
        Product withoutFrenchName = source.withTexts(source.texts().stream()
                .map(text -> text.language() == Language.FR
                        ? new ProductText(Language.FR, "", text.description(), text.colour(),
                                text.variantSize())
                        : text)
                .toList());
        CatalogExportService.FamilyGroup canonicalSource = complete.families().getFirst();
        CatalogExportService.Model canonical = new CatalogExportService.Model(
                List.of(withoutFrenchName), complete.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        canonicalSource.content(), List.of(withoutFrenchName),
                        canonicalSource.category(), false)), complete.request());

        String html = renderer.renderHtml(canonical);
        assertTrue(html.contains(PdfCatalogRendererTest.localizedFamilyName(Language.FR)));

        CatalogExportService.Model standalone = new CatalogExportService.Model(
                List.of(withoutFrenchName), complete.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        null, List.of(withoutFrenchName), canonicalSource.category(), true)),
                complete.request());
        assertEquals(List.of("products.1.name"), renderer.missingTranslations(standalone));
        LocalizationIncompleteException failure = assertThrows(
                LocalizationIncompleteException.class, () -> renderer.renderHtml(standalone));
        assertEquals(List.of("products.1.name"), failure.missingPaths());
    }

    private static PdfCatalogRenderer renderer(byte[] image) throws Exception {
        ProductService productService = mock(ProductService.class);
        when(productService.photoData(anyString())).thenAnswer(ignored ->
                new ByteArrayInputStream(image));
        PhotoStorage photoStorage = mock(PhotoStorage.class);
        when(photoStorage.read(anyString())).thenAnswer(ignored ->
                new ByteArrayInputStream(image));
        CompanyProfileService company = mock(CompanyProfileService.class);
        when(company.get()).thenReturn(CompanyProfile.empty());
        ContentTranslationService content = mock(ContentTranslationService.class);
        Map<Language, Map<String, String>> localizedCopy = catalogCopy();
        when(content.values(eq(ContentScope.CATALOG), any(Language.class))).thenAnswer(call ->
                localizedCopy.get(call.getArgument(1, Language.class)));
        when(content.missingRequired(eq(ContentScope.CATALOG), any(Language.class)))
                .thenReturn(List.of());

        Engine engine = Engine.builder().addDefaults()
                .addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new HtmlEscaper(List.of(Variant.TEXT_HTML))).build();
        PdfImageEncoder encoder = new PdfImageEncoder();
        return new PdfCatalogRenderer(
                engine.parse(Files.readString(
                        Path.of("src/main/resources/templates/catalog.html")),
                        Variant.forContentType(Variant.TEXT_HTML)),
                engine.parse(Files.readString(
                        Path.of("src/main/resources/templates/catalog-brochure.html")),
                        Variant.forContentType(Variant.TEXT_HTML)),
                productService, photoStorage, new Brand(), company,
                new CatalogPdfFonts(new PdfFonts()), encoder,
                new CatalogEditorialAssets(encoder), content);
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing test resource " + name);
            return input.readAllBytes();
        }
    }

    private static Map<Language, Map<String, String>> catalogCopy() throws Exception {
        List<List<String>> rows;
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("i18n/public-content.csv")) {
            if (input == null) throw new IllegalStateException("Missing CATALOG copy seed");
            rows = Csv.parseRows(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        List<String> header = rows.getFirst();
        Map<Language, Integer> columns = new EnumMap<>(Language.class);
        for (Language language : Language.values()) columns.put(
                language, header.indexOf(language.code()));
        Map<Language, Map<String, String>> result = new EnumMap<>(Language.class);
        for (Language language : Language.values()) result.put(language, new LinkedHashMap<>());
        for (List<String> row : rows.subList(1, rows.size())) {
            if (!"CATALOG".equalsIgnoreCase(row.getFirst())) continue;
            for (Language language : Language.values()) {
                result.get(language).put(row.get(1), row.get(columns.get(language)));
            }
        }
        result.replaceAll((language, values) -> Map.copyOf(values));
        return Map.copyOf(result);
    }
}
