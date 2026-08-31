package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.CategoryText;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.LocalizationIncompleteException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.enrosed.catalog.application.CatalogExportServiceTest.product;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class PdfCatalogRendererTest {

    @Inject PdfCatalogRenderer renderer;
    @Inject PhotoStorage photoStorage;
    @Inject CatalogEditorialAssets editorialAssets;
    @Inject PdfImageEncoder imageEncoder;

    @Test
    void simpleAndBrochureUseDistinctBrandedTemplatesWithoutInternalAuditCopy() throws Exception {
        Photo fixture = storedPhoto(1L, "/images/soap-roos-in-box-480.webp",
                "soap-roos-in-box-480.webp", "image/webp");
        Photo counterFixture = storedPhoto(2L, "/catalog-assets/counter-bowl-retail.jpg",
                "counter-bowl-retail.jpg", "image/jpeg");
        Photo preservedFixture = storedPhoto(3L, "/catalog-assets/preserved-roses.jpg",
                "preserved-roses.jpg", "image/jpeg");
        CatalogExportService.Model simple = withPhoto(
                model(1, CatalogExportService.Layout.SIMPLE), fixture);
        CatalogExportService.Model brochure = withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), fixture);

        String simpleHtml = renderer.renderHtml(simple);
        String brochureHtml = renderer.renderHtml(brochure);

        assertTrue(simpleHtml.contains("#120c0a"));
        assertTrue(simpleHtml.contains("SKU-1"));
        assertTrue(simpleHtml.contains("data:image/jpeg;base64,"));
        assertFalse(simpleHtml.contains("Beschrijving"));
        assertTrue(brochureHtml.contains("A lasting collection"));
        assertTrue(brochureHtml.contains("Product B × D × H"));
        assertTrue(brochureHtml.contains("The complete range at a glance."));
        assertTrue(brochureHtml.contains("Reference prices per piece in EUR"));
        assertTrue(brochureHtml.contains("href=\"#family-01\""));
        assertTrue(brochureHtml.contains("id=\"family-01\""));
        assertFalse(brochureHtml.contains("€0.00"));
        assertFalse(brochureHtml.contains("ENROSED atelier"));
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
            assertEquals(7, pdf.getNumberOfPages());
            assertTrue(pdf.getPage(0).getMediaBox().getHeight()
                    > pdf.getPage(0).getMediaBox().getWidth());
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                        > pdf.getPage(page).getMediaBox().getWidth());
            }
            assertTrue(pdf.getPage(1).getAnnotations().stream()
                    .anyMatch(PDAnnotationLink.class::isInstance));
            String extracted = new PDFTextStripper().getText(pdf);
            assertTrue(extracted.contains("Confirm"));
            assertTrue(extracted.contains("gifting"));
            assertTrue(extracted.contains("complete range"));
        }

        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("simple.pdf"), simplePdf.content());
        Files.write(qa.resolve("brochure-single.pdf"), brochurePdf.content());
        CatalogDocumentRenderer.Document qaBrochure = renderer.render(
                comparisonQaModel(counterFixture, preservedFixture, fixture));
        try (PDDocument pdf = Loader.loadPDF(qaBrochure.content())) {
            assertEquals(24, pdf.getNumberOfPages());
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                        > pdf.getPage(page).getMediaBox().getWidth());
            }
        }
        Files.write(qa.resolve("brochure.pdf"), qaBrochure.content());
    }

    @Test
    void overviewIncludesEverySelectedFamilyAcrossAsManyEightCardPagesAsNeeded() {
        String html = renderer.renderHtml(model(57, CatalogExportService.Layout.BROCHURE));
        assertEquals(3, occurrences(html, "class=\"page overview-page\""));
        assertEquals(19, occurrences(html, "class=\"overview-card overview-card--"));
        List<String> overviewPages = overviewPageFragments(html);
        assertEquals(List.of(7, 6, 6), overviewPages.stream()
                .map(page -> occurrences(page, "class=\"overview-card overview-card--"))
                .toList());
        for (String overviewPage : overviewPages) {
            assertTrue(occurrences(overviewPage, "class=\"overview-card overview-card--") <= 8,
                    "an A4 overview page must never exceed eight fixed cards");
        }
        assertTrue(html.contains("href=\"#family-19\""));
        assertTrue(html.contains("id=\"family-19\""));
        assertFalse(html.contains("family-20"));
        assertTrue(html.indexOf("class=\"page overview-page\"")
                < html.indexOf("id=\"family-01\""),
                "the complete glance must precede every family detail page");
    }

    @Test
    void anOddFinalOverviewCardIntentionallySpansBothColumns() {
        String html = renderer.renderHtml(model(1, CatalogExportService.Layout.BROCHURE));

        assertTrue(html.contains("class=\"overview-cell overview-cell--wide\" colspan=\"2\""));
        assertEquals(1, occurrences(html, "class=\"overview-card overview-card--"));
        assertFalse(html.contains("class=\"overview-cell overview-empty\""));
    }

    @Test
    void missingCatalogueFamilyPhotoFallsBackToAValidProductOwnedPhoto() throws Exception {
        Photo owned = storedPhoto(94L, "/images/soap-roos-in-box-480.webp",
                "owned-soap-rose.webp", "image/webp");
        CatalogExportService.Model pictured = withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), owned);
        CatalogExportService.FamilyGroup originalGroup = pictured.families().getFirst();
        CatalogFamilyReader.Family original = originalGroup.content();
        CatalogFamilyReader.Family missingFamilyPhoto = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), original.name(),
                original.summary(), original.description(), original.format(), original.highlights(),
                original.dimensions(), original.texts(), original.packages(),
                List.of(new CatalogFamilyReader.GalleryPhoto(
                        999L, "missing-catalogue-photo.webp", "image/webp", 0,
                        pictured.products().getFirst().id())));
        CatalogExportService.Model fallback = new CatalogExportService.Model(
                pictured.products(), pictured.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        missingFamilyPhoto, originalGroup.variants(), originalGroup.category(), false)),
                pictured.request());

        String html = renderer.renderHtml(fallback);

        assertFalse(html.contains("class=\"image-placeholder\""),
                "a missing catalogue-family blob must not suppress a valid product fallback");
        String expectedHero = imageEncoder.encodeContained(
                photoBytes(owned), 1_600, 900, new Color(255, 252, 248));
        assertTrue(html.contains(expectedHero));
    }

    @Test
    void curatedCatalogHeroReplacesAnOutlierPrimaryButKeepsTheActualPhotoBesideIt()
            throws Exception {
        Photo actual = storedPhoto(941L, "/images/soap-roos-in-box-480.webp",
                "soap-led-actual.webp", "image/webp");
        CatalogExportService.Model catalog = withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led");

        String html = renderer.renderHtml(catalog);
        String curatedHero = editorialAssets.contained(
                "families/soap-rose-box-led.png", 1_600, 900);
        String actualSide = imageEncoder.encodeContained(
                photoBytes(actual), 600, 900, new Color(255, 252, 248));

        assertFalse(curatedHero.isBlank());
        assertTrue(html.contains(curatedHero));
        assertTrue(html.contains(actualSide));

        CatalogDocumentRenderer.Document document = renderer.render(catalog);
        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("curated-soap-led.pdf"), document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(7, pdf.getNumberOfPages());
        }
    }

    @Test
    void curatedCatalogHeroStaysHiddenWhenProductPhotosAreDisabled() throws Exception {
        Photo actual = storedPhoto(942L, "/images/soap-roos-in-box-480.webp",
                "soap-led-disabled.webp", "image/webp");
        CatalogExportService.Model catalog = withPhotoBudget(withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led"), false, 0);

        String html = renderer.renderHtml(catalog);
        String curatedHero = editorialAssets.contained(
                "families/soap-rose-box-led.png", 1_600, 900);
        String actualSide = imageEncoder.encodeContained(
                photoBytes(actual), 600, 900, new Color(255, 252, 248));

        assertFalse(html.contains(curatedHero));
        assertFalse(html.contains(actualSide));
        assertTrue(html.contains("class=\"image-placeholder\""));
    }

    @Test
    void curatedCatalogHeroConsumesTheSinglePhotoBudgetWithoutAnActualSideImage()
            throws Exception {
        Photo actual = storedPhoto(943L, "/images/soap-roos-in-box-480.webp",
                "soap-led-single.webp", "image/webp");
        CatalogExportService.Model catalog = withPhotoBudget(withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led"), true, 1);

        String html = renderer.renderHtml(catalog);
        String curatedHero = editorialAssets.contained(
                "families/soap-rose-box-led.png", 1_600, 900);
        String actualSide = imageEncoder.encodeContained(
                photoBytes(actual), 600, 900, new Color(255, 252, 248));

        assertTrue(html.contains(curatedHero));
        assertFalse(html.contains(actualSide));
    }

    @Test
    void internalInheritedPhotoNeverLeaksIntoSimpleOrBrochureFallback() throws Exception {
        Photo inheritedSource = storedPhoto(95L, "/catalog-assets/counter-bowl-retail.jpg",
                "internal-family-photo.jpg", "image/jpeg");
        Photo inherited = new Photo(
                inheritedSource.id(), inheritedSource.storageKey(), inheritedSource.originalFilename(),
                inheritedSource.contentType(), inheritedSource.sizeBytes(), inheritedSource.widthPx(),
                inheritedSource.heightPx(), 0, 501L);
        Photo owned = storedPhoto(96L, "/images/soap-roos-in-box-480.webp",
                "owned-product-photo.webp", "image/webp");
        List<Photo> photos = List.of(inherited, owned);
        CatalogExportService.Model simple = withPhotos(
                model(1, CatalogExportService.Layout.SIMPLE), photos);
        CatalogExportService.Model brochure = withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE), photos);

        String simpleHtml = renderer.renderHtml(simple);
        String brochureHtml = renderer.renderHtml(brochure);
        String inheritedSimple = imageEncoder.encode(photoBytes(inherited), 700);
        String ownedSimple = imageEncoder.encode(photoBytes(owned), 700);
        String inheritedHero = imageEncoder.encodeContained(
                photoBytes(inherited), 1_600, 900, new Color(255, 252, 248));
        String ownedHero = imageEncoder.encodeContained(
                photoBytes(owned), 1_600, 900, new Color(255, 252, 248));

        assertFalse(simpleHtml.contains(inheritedSimple));
        assertTrue(simpleHtml.contains(ownedSimple));
        assertFalse(brochureHtml.contains(inheritedHero));
        assertTrue(brochureHtml.contains(ownedHero));
    }

    @Test
    void catalogueInheritedPhotoRemainsAvailableToTheSimpleLayout() throws Exception {
        Photo inheritedSource = storedPhoto(97L, "/catalog-assets/counter-bowl-retail.jpg",
                "catalogue-family-photo.jpg", "image/jpeg");
        long familyPhotoId = 502L;
        Photo inherited = new Photo(
                inheritedSource.id(), inheritedSource.storageKey(), inheritedSource.originalFilename(),
                inheritedSource.contentType(), inheritedSource.sizeBytes(), inheritedSource.widthPx(),
                inheritedSource.heightPx(), 0, familyPhotoId);
        CatalogExportService.Model pictured = withPhoto(
                model(1, CatalogExportService.Layout.SIMPLE), inherited);
        CatalogExportService.FamilyGroup originalGroup = pictured.families().getFirst();
        CatalogFamilyReader.Family original = originalGroup.content();
        CatalogFamilyReader.Family catalogueFamily = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), original.name(),
                original.summary(), original.description(), original.format(), original.highlights(),
                original.dimensions(), original.texts(), original.packages(),
                List.of(new CatalogFamilyReader.GalleryPhoto(
                        familyPhotoId, inherited.storageKey(), inherited.contentType(), 0,
                        pictured.products().getFirst().id())));
        CatalogExportService.Model catalogue = new CatalogExportService.Model(
                pictured.products(), pictured.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        catalogueFamily, originalGroup.variants(), originalGroup.category(), false)),
                pictured.request());

        String html = renderer.renderHtml(catalogue);

        assertTrue(html.contains(imageEncoder.encode(photoBytes(inherited), 700)),
                "a CATALOGUE-published inherited family photo must remain usable");
    }

    @Test
    void brochureUsesDifferentPrintResolutionEditorialAssetsForFrontAndBackCover()
            throws Exception {
        String front = editorialAssets.image("catalog-cover-v3.png");
        String back = editorialAssets.image("catalog-back-cover-v1.png");
        String html = renderer.renderHtml(model(1, CatalogExportService.Layout.BROCHURE));

        assertFalse(front.isBlank());
        assertFalse(back.isBlank());
        assertNotEquals(front, back);
        BufferedImage frontImage = dataImage(front);
        BufferedImage backImage = dataImage(back);
        assertEquals(2_480, frontImage.getWidth());
        assertEquals(3_508, frontImage.getHeight());
        assertEquals(2_480, backImage.getWidth());
        assertEquals(3_508, backImage.getHeight());
        assertEquals(1, occurrences(html, front));
        assertEquals(1, occurrences(html, back));
    }

    @Test
    void longFamilyCopyAndFourVariantsUseCompactDetailWithoutClippingOrExtraPages()
            throws Exception {
        CatalogExportService.Model source = model(4, CatalogExportService.Layout.BROCHURE);
        CatalogFamilyReader.Family original = source.families().getFirst().content();
        String finalMarker = "FINAL-CATALOG-COPY-MARKER";
        String description = ("Wholesale presentation details, care guidance, display advice and "
                + "ordering context for professional buyers. ").repeat(12) + finalMarker;
        CatalogFamilyReader.Family detailed = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), original.name(),
                "A complete retail presentation with practical buying information for trade.",
                description, original.format(),
                List.of("Gift-ready presentation", "No daily water",
                        "Four coordinated wholesale variants"),
                original.dimensions(), original.texts(), original.packages(), original.photos());
        CatalogExportService.Model dense = new CatalogExportService.Model(
                source.products(), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        detailed, source.products(), source.families().getFirst().category(), false)),
                source.request());

        String html = renderer.renderHtml(dense);
        assertTrue(html.contains(
                "class=\"page family-page family-page--counter family-page--compact\""));
        CatalogDocumentRenderer.Document document = renderer.render(dense);
        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("dense-family.pdf"), document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(7, pdf.getNumberOfPages());
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                        > pdf.getPage(page).getMediaBox().getWidth());
            }
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains(finalMarker));
            assertTrue(text.contains("SKU-4"));
            assertTrue(maxTextBottom(pdf, 4) < pdf.getPage(3).getMediaBox().getHeight() - 4,
                    "dense family text must remain inside the visible A4 sheet");
        }
    }

    @Test
    void extremeFamilyContentFailsClearlyInsteadOfBeingSilentlyClipped() {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        CatalogFamilyReader.Family original = source.families().getFirst().content();
        CatalogFamilyReader.Family extreme = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), original.name(),
                original.summary(), "Veel te lange catalogustekst. ".repeat(140),
                original.format(), original.highlights(), original.dimensions(), original.texts(),
                original.packages(), original.photos());
        CatalogExportService.Model oversized = new CatalogExportService.Model(
                source.products(), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        extreme, source.products(), source.families().getFirst().category(), false)),
                source.request());

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> renderer.renderHtml(oversized));
        assertTrue(error.getMessage().contains("te veel tekst, varianten of beelden"));
        assertTrue(error.getMessage().contains(original.name()));
    }

    @Test
    void extremeFormatAndVariantCellsFailClearlyBeforeTheFixedPageCanClipThem() {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        CatalogExportService.FamilyGroup originalGroup = source.families().getFirst();
        CatalogFamilyReader.Family original = originalGroup.content();
        CatalogFamilyReader.Family extreme = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), original.name(),
                original.summary(), original.description(),
                "Uitzonderlijk lange retailpresentatie ".repeat(9), original.highlights(),
                original.dimensions(), original.texts(), original.packages(), original.photos());
        Product base = source.products().getFirst();
        Product extremeVariant = base.withVariantAttributes(
                "Ceremonieel bordeaux met een uitzonderlijk lange kleurnaam ".repeat(7),
                "Professionele presentatiemaat voor een zeer brede toonbank ".repeat(7),
                base.colourHex());
        CatalogExportService.Model oversized = new CatalogExportService.Model(
                List.of(extremeVariant), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        extreme, List.of(extremeVariant), originalGroup.category(), false)),
                source.request());

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> renderer.renderHtml(oversized));
        assertTrue(error.getMessage().contains("te veel tekst, varianten of beelden"));
        assertTrue(error.getMessage().contains(original.name()));
    }

    @Test
    void longDutchCoverAndFamilyTitlesStayOnTheirAssignedA4Pages() throws Exception {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        CatalogFamilyReader.Family original = source.families().getFirst().content();
        String familyTitle = "Gepreserveerde Bowl rozen in een luxe presentatie voor de toonbank";
        CatalogFamilyReader.Family renamed = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), familyTitle,
                original.summary(), original.description(), original.format(), original.highlights(),
                original.dimensions(), original.texts(), original.packages(), original.photos());
        String coverTitle = "Nederlandse handelscatalogus voor professionele bloemisten";
        CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                true, true, true, true, true, coverTitle, "Collectie voor retailpresentaties");
        CatalogExportService.Request oldRequest = source.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), oldRequest.includePrices(), oldRequest.includePhotos(),
                oldRequest.photosPerProduct(), oldRequest.title(), oldRequest.intro(), "nl",
                oldRequest.layout(), options);
        CatalogExportService.Model localized = new CatalogExportService.Model(
                source.products(), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        renamed, source.families().getFirst().variants(),
                        source.families().getFirst().category(), false)), request);

        String html = renderer.renderHtml(localized);
        assertTrue(html.contains("height: 297mm; min-height: 297mm; max-height: 297mm"));
        assertTrue(html.contains("page-break-inside: avoid"));

        CatalogDocumentRenderer.Document document = renderer.render(localized);
        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("long-dutch-titles.pdf"), document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(7, pdf.getNumberOfPages());
            PDFTextStripper cover = new PDFTextStripper();
            cover.setStartPage(1);
            cover.setEndPage(1);
            assertTrue(normalizeWhitespace(cover.getText(pdf)).contains(coverTitle));
            PDFTextStripper detail = new PDFTextStripper();
            detail.setStartPage(4);
            detail.setEndPage(4);
            assertTrue(normalizeWhitespace(detail.getText(pdf)).contains(familyTitle));
            assertTrue(maxTextBottom(pdf, 4) < pdf.getPage(3).getMediaBox().getHeight() - 4,
                    "long Dutch title and footer must remain inside the visible A4 sheet");
        }
    }

    @Test
    void overviewProductLinksUseTheRequestedLocaleAndReachTheDetailPage() {
        Photo photo = new Photo(93L, "unused.jpg", "unused.jpg", "image/jpeg",
                1L, 1, 1, 0);
        for (Language language : Language.values()) {
            String html = renderer.renderHtml(localizedQaModel(
                    language, CatalogExportService.Layout.BROCHURE, photo));
            assertTrue(html.contains("href=\"#family-01\""), language.code());
            assertTrue(html.contains("id=\"family-01\""), language.code());
            assertTrue(html.contains(localizedFamilyName(language)), language.code());
        }
    }

    @Test
    void unavailableReferencePriceIsNeverRenderedAsZero() {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        Product unavailable = withoutReferencePrice(source.products().getFirst());
        CatalogExportService.FamilyGroup oldGroup = source.families().getFirst();
        CatalogExportService.Model model = new CatalogExportService.Model(
                List.of(unavailable), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        oldGroup.content(), List.of(unavailable), oldGroup.category(), false)),
                source.request());

        String html = renderer.renderHtml(model);
        assertTrue(html.contains("Price on request"));
        assertFalse(html.contains("€0.00"));
        assertFalse(html.contains("€ 0,00"));
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

    @Test
    void writesAllEightLocaleQaCatalogsWhenRequested() throws Exception {
        String configured = System.getProperty("catalog.qa.output");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set -Dcatalog.qa.output to create delivery QA PDFs");
        Path output = Path.of(configured);
        Files.createDirectories(output);
        Photo photo = storedPhoto(90L, "/images/soap-roos-in-box-480.webp",
                "soap-roos-in-box-480.webp", "image/webp");

        int written = 0;
        for (Language language : Language.values()) {
            for (CatalogExportService.Layout layout : CatalogExportService.Layout.values()) {
                CatalogDocumentRenderer.Document document = renderer.render(
                        localizedQaModel(language, layout, photo));
                assertPdf(document);
                String stem = layout.name().toLowerCase() + "-" + language.code();
                Files.write(output.resolve("enrosed-catalog-" + stem + ".pdf"),
                        document.content());
                try (PDDocument pdf = Loader.loadPDF(document.content())) {
                    assertTrue(pdf.getNumberOfPages() >= 1);
                    String extracted = new PDFTextStripper().getText(pdf);
                    assertTrue(extracted.contains("B × D × H"), stem);
                    assertTrue(extracted.contains(localizedFamilyName(language)), stem);
                    assertFalse(extracted.toLowerCase().contains("dashboard"), stem);
                    assertFalse(extracted.toLowerCase().contains("canonical"), stem);
                    assertFalse(extracted.toLowerCase().contains("provenance"), stem);
                    assertFalse(extracted.toLowerCase().contains("candidate"), stem);
                }
                written++;
            }
        }
        assertEquals(16, written);
        try (var files = Files.list(output)) {
            assertEquals(16, files.filter(path -> path.getFileName().toString()
                    .startsWith("enrosed-catalog-")).count());
        }
    }

    @Test
    void strictExportRejectsBlankExactOptionalCopyWhenAnotherLocaleUsesTheField() {
        Photo photo = new Photo(91L, "unused.webp", "unused.webp", "image/webp",
                1L, 1, 1, 0);
        CatalogExportService.Model complete = localizedQaModel(
                Language.PT, CatalogExportService.Layout.BROCHURE, photo);
        CatalogExportService.FamilyGroup originalGroup = complete.families().getFirst();
        CatalogFamilyReader.Family originalFamily = originalGroup.content();
        List<CatalogFamilyReader.Text> familyTexts = originalFamily.texts().stream()
                .map(text -> text.language() == Language.PT
                        ? new CatalogFamilyReader.Text(Language.PT, text.name(), text.summary(),
                                text.description(), "", List.of())
                        : text)
                .toList();
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                originalFamily.id(), originalFamily.familyKey(), originalFamily.publicHandle(),
                originalFamily.categoryId(), originalFamily.categoryKey(),
                originalFamily.categoryName(), originalFamily.categoryPosition(),
                originalFamily.productPosition(), originalFamily.name(), originalFamily.summary(),
                originalFamily.description(), originalFamily.format(), originalFamily.highlights(),
                originalFamily.dimensions(), familyTexts, originalFamily.packages(),
                originalFamily.photos());

        Product originalProduct = complete.products().getFirst();
        List<ProductText> productTexts = originalProduct.texts().stream()
                .map(text -> text.language() == Language.PT
                        ? new ProductText(Language.PT, text.name(), text.description(), "", "") : text)
                .toList();
        Product product = new Product(
                originalProduct.id(), originalProduct.sku(), originalProduct.name(),
                originalProduct.dimensions(), null, originalProduct.variantSize(),
                originalProduct.colourHex(), originalProduct.description(), null,
                originalProduct.supplierId(), originalProduct.active(), originalProduct.familyId(),
                originalProduct.canonicalVariantKey(), originalProduct.canonicalBarcode(),
                originalProduct.variantPosition(), originalProduct.inventoryKnown(),
                originalProduct.familyKey(), originalProduct.publicHandle(),
                originalProduct.websiteStatus(), originalProduct.orderAppStatus(),
                originalProduct.barcodes(), originalProduct.hsCode(), originalProduct.carton(),
                originalProduct.exwPrice(), originalProduct.exwCurrency(),
                originalProduct.extraUnitCost(), originalProduct.landedCostEur(),
                originalProduct.landedCostSource(), originalProduct.markupPct(),
                originalProduct.fixedSalesPriceEur(), originalProduct.stockQuantity(),
                originalProduct.photos(), productTexts);

        Category originalCategory = originalGroup.category();
        List<CategoryText> categoryTexts = originalCategory.texts().stream()
                .map(text -> text.language() == Language.PT
                        ? new CategoryText(Language.PT, "", text.description(), text.eyebrow(),
                                text.mobileName(), text.navigationName(), text.footerName())
                        : text)
                .toList();
        Category category = new Category(originalCategory.id(), originalCategory.code(),
                originalCategory.name(), originalCategory.description(), originalCategory.eyebrow(),
                originalCategory.position(), originalCategory.mobileName(),
                originalCategory.navigationName(), originalCategory.footerName(),
                originalCategory.featuredProductId(), categoryTexts, originalCategory.revision());
        CatalogExportService.Model incomplete = new CatalogExportService.Model(
                List.of(product), Map.of(category.id(), category),
                List.of(new CatalogExportService.FamilyGroup(
                        family, List.of(product), category, false)), complete.request());

        LocalizationIncompleteException failure = assertThrows(
                LocalizationIncompleteException.class, () -> renderer.renderHtml(incomplete));
        assertEquals(List.of(
                "categories.counter.name",
                "families.qa-family.format",
                "families.qa-family.highlights",
                "products.1.color",
                "products.1.size"), failure.missingPaths());
    }

    @Test
    void strictSimpleIgnoresMissingFamilyCopyThatTheSimpleTemplateNeverRenders() {
        Photo photo = new Photo(92L, "unused.jpg", "unused.jpg", "image/jpeg",
                1L, 1, 1, 0);
        CatalogExportService.Model complete = localizedQaModel(
                Language.FR, CatalogExportService.Layout.SIMPLE, photo);
        CatalogExportService.FamilyGroup original = complete.families().getFirst();
        CatalogFamilyReader.Family source = original.content();
        List<CatalogFamilyReader.Text> texts = source.texts().stream()
                .map(text -> text.language() == Language.FR
                        ? new CatalogFamilyReader.Text(Language.FR, text.name(), null,
                                null, null, List.of()) : text)
                .toList();
        CatalogFamilyReader.Family incompleteFamily = new CatalogFamilyReader.Family(
                source.id(), source.familyKey(), source.publicHandle(), source.categoryId(),
                source.categoryKey(), source.categoryName(), source.categoryPosition(),
                source.productPosition(), source.name(), source.summary(), source.description(),
                source.format(), source.highlights(), source.dimensions(), texts,
                source.packages(), source.photos());
        CatalogExportService.Model incomplete = new CatalogExportService.Model(
                complete.products(), complete.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        incompleteFamily, original.variants(), original.category(), false)),
                complete.request());

        String html = renderer.renderHtml(incomplete);
        assertTrue(html.contains(localizedFamilyName(Language.FR)));
        assertFalse(html.contains("Description approuvée"));
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
        return withPhotos(model, List.of(photo));
    }

    private static CatalogExportService.Model withFamilyKey(
            CatalogExportService.Model model, String familyKey) {
        CatalogExportService.FamilyGroup oldGroup = model.families().getFirst();
        CatalogFamilyReader.Family oldFamily = oldGroup.content();
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                oldFamily.id(), familyKey, oldFamily.publicHandle(), oldFamily.categoryId(),
                oldFamily.categoryKey(), oldFamily.categoryName(), oldFamily.categoryPosition(),
                oldFamily.productPosition(), oldFamily.name(), oldFamily.summary(),
                oldFamily.description(), oldFamily.format(), oldFamily.highlights(),
                oldFamily.dimensions(), oldFamily.texts(), oldFamily.packages(), oldFamily.photos());
        CatalogExportService.FamilyGroup group = new CatalogExportService.FamilyGroup(
                family, oldGroup.variants(), oldGroup.category(), oldGroup.synthetic());
        return new CatalogExportService.Model(
                model.products(), model.categoriesById(), List.of(group), model.request());
    }

    private static CatalogExportService.Model withPhotoBudget(
            CatalogExportService.Model model, boolean includePhotos, int photosPerProduct) {
        CatalogExportService.Request oldRequest = model.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), oldRequest.includePrices(), includePhotos, photosPerProduct,
                oldRequest.title(), oldRequest.intro(), oldRequest.language(), oldRequest.layout(),
                oldRequest.brochure(), oldRequest.strictLanguage());
        return new CatalogExportService.Model(
                model.products(), model.categoriesById(), model.families(), request);
    }

    private static CatalogExportService.Model withPhotos(
            CatalogExportService.Model model, List<Photo> photos) {
        Product pictured = model.products().getFirst().withPhotos(photos);
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

    private byte[] photoBytes(Photo photo) throws Exception {
        try (var input = photoStorage.read(photo.storageKey())) {
            return input.readAllBytes();
        }
    }

    private static Product withoutReferencePrice(Product base) {
        return new Product(
                base.id(), base.sku(), base.name(), base.dimensions(), base.packaging(),
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.active(), base.familyId(),
                base.canonicalVariantKey(), base.canonicalBarcode(), base.variantPosition(),
                base.inventoryKnown(), base.familyKey(), base.publicHandle(),
                base.websiteStatus(), base.orderAppStatus(), base.barcodes(), base.hsCode(),
                base.carton(), base.exwPrice(), base.exwCurrency(), base.extraUnitCost(),
                BigDecimal.ZERO, "test", BigDecimal.ZERO, BigDecimal.ZERO,
                base.stockQuantity(), base.photos(), base.texts());
    }

    private static int occurrences(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static double maxTextBottom(PDDocument pdf, int oneBasedPage) throws IOException {
        TextBoundsStripper stripper = new TextBoundsStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        stripper.getText(pdf);
        return stripper.maxBottom;
    }

    private static final class TextBoundsStripper extends PDFTextStripper {
        private double maxBottom;

        private TextBoundsStripper() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            maxBottom = Math.max(maxBottom, text.getYDirAdj() + text.getHeightDir());
            super.processTextPosition(text);
        }
    }

    private static BufferedImage dataImage(String dataUri) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static List<String> overviewPageFragments(String html) {
        List<String> pages = new ArrayList<>();
        String marker = "<section class=\"page overview-page\">";
        int from = 0;
        while ((from = html.indexOf(marker, from)) >= 0) {
            int to = html.indexOf("</section>", from);
            pages.add(html.substring(from, to < 0 ? html.length() : to));
            from += marker.length();
        }
        return pages;
    }

    static CatalogExportService.Model localizedQaModel(
            Language requested, CatalogExportService.Layout layout, Photo photo) {
        List<CategoryText> categoryTexts = java.util.Arrays.stream(Language.values())
                .map(language -> new CategoryText(language, localizedCategoryName(language),
                        localizedCategoryDescription(language), null, null, null, null))
                .toList();
        Category category = new Category(
                1L, "counter", "Counter Displays", "Retail-ready products", null, 0,
                null, null, null, null, categoryTexts);

        Product base = product(1L, "SKU-QA-01", 100L, 1L, 0)
                .withVariantAttributes("Red", "Medium", "#9D263A");
        List<ProductText> productTexts = java.util.Arrays.stream(Language.values())
                .map(language -> new ProductText(language, localizedFamilyName(language),
                        localizedDescription(language), localizedColour(language),
                        localizedSize(language)))
                .toList();
        Product item = new Product(
                base.id(), base.sku(), base.name(), base.dimensions(), base.colour(),
                base.variantSize(), base.colourHex(), base.description(), base.categoryId(),
                base.supplierId(), base.active(), base.familyId(), base.canonicalVariantKey(),
                base.canonicalBarcode(), base.variantPosition(), base.inventoryKnown(),
                base.familyKey(), base.publicHandle(), base.websiteStatus(), base.orderAppStatus(),
                base.barcodes(), base.hsCode(), base.carton(), base.exwPrice(), base.exwCurrency(),
                base.extraUnitCost(), base.landedCostEur(), base.landedCostSource(),
                base.markupPct(), base.fixedSalesPriceEur(), base.stockQuantity(),
                List.of(photo), productTexts);

        List<CatalogFamilyReader.Text> familyTexts = java.util.Arrays.stream(Language.values())
                .map(language -> new CatalogFamilyReader.Text(
                        language, localizedFamilyName(language), localizedSummary(language),
                        localizedDescription(language), localizedFormat(language),
                        List.of(localizedHighlight(language), localizedCare(language))))
                .toList();
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                100L, "qa-family", "qa-family", 1L, "counter", "Counter Displays",
                0, 0, "Preserved Rose", "Gift-ready presentation",
                "A lasting preserved rose presentation.", "Counter display",
                List.of("Gift-ready", "No daily water"), null, familyTexts, List.of(),
                List.of(new CatalogFamilyReader.GalleryPhoto(
                        photo.id(), photo.storageKey(), photo.contentType(), 0, item.id())));
        CatalogExportService.BrochureOptions options =
                CatalogExportService.BrochureOptions.defaults();
        CatalogExportService.Request request = new CatalogExportService.Request(
                List.of(item.id()), true, true, 2, null, null, requested.code(), layout,
                options, true);
        return new CatalogExportService.Model(
                List.of(item), Map.of(category.id(), category),
                List.of(new CatalogExportService.FamilyGroup(
                        family, List.of(item), category, false)), request);
    }

    static String localizedFamilyName(Language language) {
        return switch (language) {
            case NL -> "Gepreserveerde roos";
            case FR -> "Rose stabilisée";
            case EN -> "Preserved Rose";
            case DE -> "Konservierte Rose";
            case ES -> "Rosa preservada";
            case PL -> "Róża stabilizowana";
            case PT -> "Rosa preservada";
            case TR -> "Korunmuş gül";
        };
    }

    private static String localizedSummary(Language language) {
        return localizedFamilyName(language) + " · " + localizedHighlight(language) + ".";
    }

    private static String localizedDescription(Language language) {
        return localizedFamilyName(language) + " — " + localizedCare(language) + ".";
    }

    private static String localizedFormat(Language language) {
        return localizedCategoryName(language) + " · 12";
    }

    private static String localizedCategoryName(Language language) {
        return switch (language) {
            case NL -> "Toonbankdisplays";
            case FR -> "Présentoirs de comptoir";
            case EN -> "Counter Displays";
            case DE -> "Thekendisplays";
            case ES -> "Expositores de mostrador";
            case PL -> "Ekspozytory na ladę";
            case PT -> "Expositores de balcão";
            case TR -> "Tezgâh teşhirleri";
        };
    }

    private static String localizedCategoryDescription(Language language) {
        return localizedCategoryName(language) + " — " + localizedHighlight(language) + ".";
    }

    private static String localizedColour(Language language) {
        return switch (language) {
            case NL -> "Rood";
            case FR -> "Rouge";
            case EN -> "Red";
            case DE -> "Rot";
            case ES -> "Rojo";
            case PL -> "Czerwony";
            case PT -> "Vermelho";
            case TR -> "Kırmızı";
        };
    }

    private static String localizedSize(Language language) {
        return be.enrosed.shared.VariantSizes.translate("Medium", language);
    }

    private static String localizedHighlight(Language language) {
        return switch (language) {
            case NL -> "Cadeauklaar";
            case FR -> "Prête à offrir";
            case EN -> "Gift-ready";
            case DE -> "Geschenkfertig";
            case ES -> "Lista para regalar";
            case PL -> "Gotowa na prezent";
            case PT -> "Pronta a oferecer";
            case TR -> "Hediyeye hazır";
        };
    }

    private static String localizedCare(Language language) {
        return switch (language) {
            case NL -> "geen dagelijks water nodig";
            case FR -> "sans arrosage quotidien";
            case EN -> "no daily water required";
            case DE -> "kein tägliches Wasser nötig";
            case ES -> "sin riego diario";
            case PL -> "bez codziennego podlewania";
            case PT -> "sem rega diária";
            case TR -> "günlük sulama gerektirmez";
        };
    }

    private static CatalogExportService.Model comparisonQaModel(
            Photo counterPhoto, Photo preservedPhoto, Photo decorativePhoto) {
        Category counter = new Category(
                1L, "display-roses", "Counter Displays", "Retail-ready products", 0);
        Category preserved = new Category(
                2L, "divers", "Preserved Roses & Flowerboxes",
                "Preserved formats for florists and retail buyers", 1);
        Category decorative = new Category(
                3L, "rose-bears", "Soap & Foam Roses",
                "Decorative rose formats for year-round retail", 2);
        String[] counterNames = {
                "Bowl Display", "Steel Rose Display", "Diamond Display", "Single Rose Display"
        };
        String[] preservedNames = {
                "Glass Flowerbox", "Heart Flowerbox", "9 Rose Flowerbox",
                "16 Rose Flowerbox", "Acrylic Flowerbox", "Rose Dome Elite",
                "Rose Dome XL", "Preserved Rose Dome", "Square Rose Box",
                "Mini Rose Box", "Cobalt Rose Dome", "Preserved Single Rose"
        };
        String[] decorativeNames = {
                "Soap Rose Window Box", "Soap Rose Gift Box", "Half Heart Foam Rose"
        };
        List<Product> products = new ArrayList<>();
        List<CatalogExportService.FamilyGroup> groups = new ArrayList<>();
        long productId = 1;
        int position = 0;
        for (String name : counterNames) {
            long familyId = 300L + position;
            Product item = product(productId, "CD-" + (position + 1), familyId, 1L, 0)
                    .withPhotos(List.of(counterPhoto));
            CatalogFamilyReader.Family family = qaFamily(
                    familyId, "counter-" + position, 1L, "display-roses",
                    "Counter Displays", position, name, "Counter display");
            products.add(item);
            groups.add(new CatalogExportService.FamilyGroup(
                    family, List.of(item), counter, false));
            productId++;
            position++;
        }
        for (int index = 0; index < preservedNames.length; index++) {
            long familyId = 400L + index;
            Product item = product(productId, "PR-" + (index + 1), familyId, 2L, 0)
                    .withPhotos(List.of(preservedPhoto));
            CatalogFamilyReader.Family family = qaFamily(
                    familyId, "preserved-" + index, 2L, "divers",
                    "Preserved Roses & Flowerboxes", index, preservedNames[index],
                    "Preserved retail format");
            products.add(item);
            groups.add(new CatalogExportService.FamilyGroup(
                    family, List.of(item), preserved, false));
            productId++;
        }
        for (int index = 0; index < decorativeNames.length; index++) {
            long familyId = 500L + index;
            Product item = product(productId, "SR-" + (index + 1), familyId, 3L, 0)
                    .withPhotos(List.of(decorativePhoto));
            CatalogFamilyReader.Family family = qaFamily(
                    familyId, "decorative-" + index, 3L, "rose-bears",
                    "Soap & Foam Roses", index, decorativeNames[index],
                    "Decorative rose gift");
            products.add(item);
            groups.add(new CatalogExportService.FamilyGroup(
                    family, List.of(item), decorative, false));
            productId++;
        }
        Map<Long, Category> categories = new LinkedHashMap<>();
        categories.put(counter.id(), counter);
        categories.put(preserved.id(), preserved);
        categories.put(decorative.id(), decorative);
        CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                true, false, false, true, false,
                "A lasting collection", "Ready for retail.");
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, true, true, 2, "ENROSED Wholesale", null, "en",
                CatalogExportService.Layout.BROCHURE, options);
        return new CatalogExportService.Model(
                List.copyOf(products), categories, List.copyOf(groups), request);
    }

    private static CatalogFamilyReader.Family qaFamily(
            long id, String key, Long categoryId, String categoryKey,
            String categoryName, int position, String name, String format) {
        return new CatalogFamilyReader.Family(
                id, key, key, categoryId, categoryKey, categoryName, position, position,
                name, "A lasting, gift-ready format for retail.",
                "Designed for clear presentation and straightforward wholesale ordering.",
                format, List.of("No daily water", "Gift-ready presentation"),
                null, List.of(), List.of(), List.of());
    }

    private Photo storedPhoto(long id, String resource, String filename, String contentType)
            throws Exception {
        byte[] bytes = java.util.Objects.requireNonNull(
                getClass().getResourceAsStream(resource)).readAllBytes();
        String extension = filename.substring(filename.lastIndexOf('.'));
        String key = "sha256-" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)) + extension;
        PhotoStorage.Stored stored = photoStorage.storeKnown(
                key, filename, contentType, bytes);
        return new Photo(id, key, filename, contentType, stored.sizeBytes(),
                stored.widthPx(), stored.heightPx(), 0);
    }

    private static void assertPdf(CatalogDocumentRenderer.Document document) {
        assertEquals("application/pdf", document.contentType());
        assertTrue(document.content().length > 1_000);
        assertTrue(new String(document.content(), 0, 4, StandardCharsets.US_ASCII)
                .equals("%PDF"));
    }
}
