package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
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
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static be.enrosed.catalog.application.CatalogExportServiceTest.product;

/** Generates delivery PDFs for every supported locale without starting Quarkus or an HTTP listener. */
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
                    assertTrue(extracted.replaceAll("\\s+", " ").contains(
                            PdfCatalogRendererTest.localizedFamilyName(language)), stem);
                    assertFalse(extracted.contains("###"), "all localized glyphs must render: " + stem);
                    String lower = extracted.toLowerCase(java.util.Locale.ROOT);
                    assertFalse(lower.contains("dashboard"), stem);
                    assertFalse(lower.contains("canonical"), stem);
                    assertFalse(lower.contains("provenance"), stem);
                    assertFalse(lower.contains("candidate"), stem);
                }
                written++;
            }
        }
        assertEquals(Language.values().length * CatalogExportService.Layout.values().length, written);
        try (var files = Files.list(output)) {
            assertEquals(written, files.filter(path -> path.getFileName().toString()
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

    @Test
    void colourStripKeepsSelectedVariantIdentityAndLocalizedLabelsWithOneHero() throws Exception {
        Map<String, byte[]> images = Map.of("red", colourImage(Color.RED),
                "white", colourImage(Color.WHITE), "excluded", colourImage(Color.PINK),
                "generic", colourImage(Color.BLUE));
        PdfCatalogRenderer renderer = renderer(images::get);
        Photo genericLead = thumbnailPhoto(301, "generic", 301L, true);
        Photo otherColourLead = thumbnailPhoto(302, "excluded", 302L, true);
        Product red = colourProduct(1, 0, "Rood", "Red").withPhotos(List.of(genericLead));
        Product white = colourProduct(3, 2, "Wit", "White").withPhotos(List.of(otherColourLead));
        List<CatalogFamilyReader.GalleryPhoto> published = List.of(
                new CatalogFamilyReader.GalleryPhoto(301L, "generic", "image/png", 0, null),
                new CatalogFamilyReader.GalleryPhoto(302L, "excluded", "image/png", 1, 2L),
                new CatalogFamilyReader.GalleryPhoto(303L, "red", "image/png", 2, 1L),
                new CatalogFamilyReader.GalleryPhoto(304L, "white", "image/png", 3, 3L));
        String html = renderer.renderHtml(thumbnailModel(List.of(white, red), published, true));
        String strip = colourStrip(html);
        assertTrue(strip.contains("data-sku=\"COLOUR-1\""));
        assertTrue(strip.contains("data-sku=\"COLOUR-3\""));
        assertFalse(strip.contains("COLOUR-2"));
        assertTrue(strip.indexOf("COLOUR-1") < strip.indexOf("COLOUR-3"));
        assertTrue(strip.contains("alt=\"Red\""));
        assertTrue(strip.contains("alt=\"White\""));
        assertFalse(strip.contains("Rood"));
        assertTrue(colourCell(strip, red).contains(thumbnail(images.get("red"))));
        assertTrue(colourCell(strip, white).contains(thumbnail(images.get("white"))));
        assertFalse(strip.contains(thumbnail(images.get("excluded"))));
        assertFalse(strip.contains(thumbnail(images.get("generic"))));
        assertTrue(html.contains("class=\"family-media family-media--one\""),
                "one hero photo remains independent of the two selected colour photographs");
    }

    @Test
    void colourPhotosPreferPublishedCatalogueLeadsAndRejectInternalOrGenericInheritedFallbacks() throws Exception {
        Map<String, byte[]> images = Map.of("owned", colourImage(Color.RED),
                "hero", colourImage(Color.PINK), "published", colourImage(Color.BLUE),
                "internal", colourImage(Color.GREEN));
        PdfCatalogRenderer renderer = renderer(images::get);
        Photo hero = thumbnailPhoto(401, "hero", 401L, true);
        Photo internal = thumbnailPhoto(499, "internal", 499L, true);
        Photo owned = thumbnailPhoto(501, "owned", null, false);
        Product one = colourProduct(1, 0, "Rood", "Red").withPhotos(List.of(hero, owned));
        Product two = colourProduct(2, 1, "Blauw", "Blue").withPhotos(List.of(internal));
        Product three = colourProduct(3, 2, "Wit", "White").withPhotos(List.of(internal, owned));
        Product four = colourProduct(4, 3, "Groen", "Green").withPhotos(List.of(internal));
        List<CatalogFamilyReader.GalleryPhoto> published = List.of(
                new CatalogFamilyReader.GalleryPhoto(401L, "hero", "image/png", 0, 1L),
                new CatalogFamilyReader.GalleryPhoto(402L, "published", "image/png", 1, 2L),
                new CatalogFamilyReader.GalleryPhoto(403L, "missing", "image/png", 2, 3L),
                new CatalogFamilyReader.GalleryPhoto(404L, "hero", "image/png", 3, null));
        CatalogExportService.Model model = thumbnailModel(List.of(one, two, three, four), published, true);
        String html = renderer.renderHtml(model);
        String strip = colourStrip(html);
        assertTrue(colourCell(strip, one).contains(thumbnail(images.get("hero"))),
                "the selected published catalogue image also opens this colour's cell");
        assertTrue(colourCell(strip, two).contains(thumbnail(images.get("published"))));
        assertTrue(colourCell(strip, three).contains(thumbnail(images.get("owned"))));
        assertTrue(colourCell(strip, four).contains("variant-photo-empty"));
        assertFalse(strip.contains(thumbnail(images.get("internal"))));
        assertFalse(colourCell(strip, three).contains(thumbnail(images.get("hero"))),
                "a generic family image cannot replace another colour's original");
        try (var pdf = Loader.loadPDF(renderer.render(model).content())) {
            assertEquals(2, pdf.getNumberOfPages(), "cover plus one complete four-colour family page");
            String text = new PDFTextStripper().getText(pdf);
            for (Product selected : model.products()) assertTrue(text.contains(selected.sku()));
        }
    }

    @Test
    void catalogueLeadOverridesAnOlderOriginalAndMissingLeadFallsBackWithoutDeletingPhotos() throws Exception {
        Map<String, byte[]> images = Map.of("old", colourImage(Color.RED),
                "corrected", colourImage(Color.PINK), "owned-lead", colourImage(Color.BLUE));
        PdfCatalogRenderer renderer = renderer(images::get);
        Photo original = thumbnailPhoto(601, "old", null, false);
        Photo importedLead = thumbnailPhoto(602, "corrected", 602L, true);
        Photo ownedLead = thumbnailPhoto(603, "owned-lead", null, true);
        Photo missingLead = thumbnailPhoto(604, "missing", 604L, true);
        Product imported = colourProduct(1, 0, "Rood", "Red")
                .withPhotos(List.of(original, importedLead));
        Product owned = colourProduct(2, 1, "Blauw", "Blue")
                .withPhotos(List.of(original, ownedLead));
        Product fallback = colourProduct(3, 2, "Roze", "Pink")
                .withPhotos(List.of(original, missingLead));
        List<CatalogFamilyReader.GalleryPhoto> published = List.of(
                new CatalogFamilyReader.GalleryPhoto(602L, "corrected", "image/png", 0, 1L),
                new CatalogFamilyReader.GalleryPhoto(604L, "missing", "image/png", 1, 3L));

        String strip = colourStrip(renderer.renderHtml(
                thumbnailModel(List.of(imported, owned, fallback), published, true)));

        assertTrue(colourCell(strip, imported).contains(thumbnail(images.get("corrected"))),
                "a newly imported family catalogue lead wins over an earlier owned original");
        assertFalse(colourCell(strip, imported).contains(thumbnail(images.get("old"))));
        assertTrue(colourCell(strip, owned).contains(thumbnail(images.get("owned-lead"))),
                "an explicit product-owned catalogue lead also wins over an older original");
        assertTrue(colourCell(strip, fallback).contains(thumbnail(images.get("old"))),
                "the existing original remains usable when the selected lead cannot be read");
        assertEquals(List.of(original, importedLead), imported.photos());
        assertEquals(List.of(original, missingLead), fallback.photos());
    }

    @Test
    void disablingPhotosAlsoDisablesEveryColourThumbnail() throws Exception {
        byte[] image = colourImage(Color.RED);
        Product one = colourProduct(1, 0, "Rood", "Red")
                .withPhotos(List.of(thumbnailPhoto(501, "owned", null, false)));
        Product two = colourProduct(2, 1, "Blauw", "Blue")
                .withPhotos(List.of(thumbnailPhoto(502, "owned", null, false)));
        String html = renderer(image).renderHtml(thumbnailModel(List.of(one, two), List.of(), false));
        assertFalse(html.contains("<table class=\"variant-photos\""));
        assertFalse(html.contains(thumbnail(image)));
    }

    @Test
    void brandCoverContainsTheGoldLogoAndTitleWithoutAProductPhotograph() throws Exception {
        byte[] image = colourImage(Color.RED);
        Product one = colourProduct(1, 0, "Rood", "Red")
                .withPhotos(List.of(thumbnailPhoto(501, "owned", null, false)));
        String html = renderer(image).renderHtml(thumbnailModel(List.of(one), List.of(), true));
        int start = html.indexOf("<section class=\"page cover\">");
        String cover = html.substring(start, html.indexOf("</section>", start));
        assertTrue(cover.contains("class=\"cover-logo\""));
        assertTrue(cover.contains("Colour collection"));
        assertTrue(cover.contains("Selected products"));
        assertFalse(cover.contains("editorial-grid"));
        assertFalse(cover.contains("cover-media"));
        assertEquals(1, cover.split("<img ", -1).length - 1);
    }

    private static Product colourProduct(long id, int position, String dutch, String english) {
        return product(id, "COLOUR-" + id, 100L, 1L, position)
                .withVariantAttributes(dutch, "", "")
                .withTexts(List.of(new ProductText(Language.EN, "Colour " + id, "", english, "")));
    }

    private static Photo thumbnailPhoto(long id, String key, Long familyPhotoId, boolean lead) {
        return new Photo(id, key, key + ".png", "image/png", 100, 64, 96, 0,
                familyPhotoId, lead ? Set.of(be.enrosed.catalog.domain.PhotoRole.CATALOGUE) : Set.of());
    }

    private static CatalogExportService.Model thumbnailModel(List<Product> products,
            List<CatalogFamilyReader.GalleryPhoto> photos, boolean includePhotos) {
        var family = new CatalogFamilyReader.Family(100L, "colour-family", "colour-family", 1L,
                "collection", "Collection", 0, 0, "Selected colour family", "", "", "",
                List.of(), null, List.of(), List.of(), photos);
        var options = new CatalogExportService.BrochureOptions(false, false, false, false, false,
                "Colour collection", "Selected products");
        var request = new CatalogExportService.Request(products.stream().map(Product::id).toList(),
                false, includePhotos, 1, "Colour collection", "", "en",
                CatalogExportService.Layout.BROCHURE, options, false);
        return new CatalogExportService.Model(products, Map.of(),
                List.of(new CatalogExportService.FamilyGroup(family, products, null, false)), request);
    }

    private static String colourStrip(String html) {
        int start = html.indexOf("<table class=\"variant-photos\"");
        assertTrue(start >= 0, "the selected family has a colour-photo strip");
        return html.substring(start, html.indexOf("</table>", start));
    }

    private static String colourCell(String strip, Product product) {
        int start = strip.indexOf("<td data-sku=\"" + product.sku() + "\"");
        assertTrue(start >= 0);
        return strip.substring(start, strip.indexOf("</td>", start));
    }

    private static String thumbnail(byte[] image) {
        return new PdfImageEncoder().encodeContainedTrimmed(image, 40, 26, 720,
                Color.WHITE);
    }

    private static byte[] colourImage(Color colour) throws Exception {
        BufferedImage image = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 64, 96);
        graphics.setColor(colour);
        graphics.fillOval(8, 8, 48, 60);
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(24, 64, 16, 24);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static PdfCatalogRenderer renderer(byte[] image) throws Exception {
        return renderer(ignored -> image);
    }

    private static PdfCatalogRenderer renderer(java.util.function.Function<String, byte[]> images)
            throws Exception {
        ProductService productService = mock(ProductService.class);
        when(productService.photoData(anyString())).thenAnswer(call ->
                new ByteArrayInputStream(images.apply(call.getArgument(0))));
        PhotoStorage photoStorage = mock(PhotoStorage.class);
        when(photoStorage.read(anyString())).thenAnswer(call ->
                new ByteArrayInputStream(images.apply(call.getArgument(0))));
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
