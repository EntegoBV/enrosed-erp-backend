package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.PublicContentSeedLoader;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.CategoryText;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
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
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
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

        assertTrue(simpleHtml.contains("#552137"));
        assertTrue(simpleHtml.contains("SKU-1"));
        assertTrue(simpleHtml.contains("data:image/jpeg;base64,"));
        assertFalse(simpleHtml.contains("Beschrijving"));
        assertTrue(brochureHtml.contains("A lasting collection"));
        assertTrue(brochureHtml.contains("Specifications"));
        assertTrue(brochureHtml.contains("B × D × H"));
        assertTrue(brochureHtml.contains("The complete range at a glance."));
        assertFalse(brochureHtml.contains("Select a product to open its detail page."));
        assertTrue(brochureHtml.contains("Reference prices per piece in EUR"));
        assertFalse(brochureHtml.contains("View product detail"));
        assertTrue(brochureHtml.contains("href=\"#family-01\""));
        assertTrue(brochureHtml.contains("id=\"family-01\""));
        assertFalse(brochureHtml.contains("€0.00"));
        assertFalse(brochureHtml.contains("ENROSED atelier"));
        assertFalse(brochureHtml.toLowerCase().contains("candidate"));
        assertFalse(brochureHtml.toLowerCase().contains("provenance"));
        assertFalse(brochureHtml.toLowerCase().contains("confidence"));
        assertFalse(brochureHtml.toLowerCase().contains("dashboard"));
        assertFalse(brochureHtml.toLowerCase().contains("canonical"));
        assertFalse(sectionFragment(brochureHtml, "<section class=\"page cover\">")
                .contains("1 FAMILY · 1 VARIANT"));
        assertTrue(brochureHtml.contains("Private label is available from a minimum order value of €10,000."));
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
            assertEquals(23, pdf.getNumberOfPages(), "range pages reserve room for long SKUs and logistics");
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                        > pdf.getPage(page).getMediaBox().getWidth());
            }
        }
        Files.write(qa.resolve("brochure.pdf"), qaBrochure.content());
    }

    @Test
    void bowlProductsPriceAndCountInBowlsWhileDefaultProductsKeepPerPiece() {
        be.enrosed.catalog.domain.Packaging bowl = new be.enrosed.catalog.domain.Packaging(
                be.enrosed.catalog.domain.PackagingKind.NONE, Dimensions.empty(), null, null,
                be.enrosed.catalog.domain.SalesUnit.PIECE, "bowl");
        List<Product> bowls = List.of(
                withPackaging(product(1L, "BOWL-RD", 100L, 1L, 0), bowl),
                withPackaging(product(2L, "BOWL-WT", 100L, 1L, 1), bowl));

        String simple = normalizeWhitespace(renderer.renderHtml(
                unitModel(bowls, CatalogExportService.Layout.SIMPLE, "nl")));
        assertTrue(simple.contains("<small>per bowl</small>"), simple);
        assertTrue(simple.contains("<strong>6 bowls</strong>"), "carton contents in the unit: " + simple);
        assertFalse(simple.contains("per stuk"), simple);

        String brochure = normalizeWhitespace(renderer.renderHtml(
                unitModel(bowls, CatalogExportService.Layout.BROCHURE, "nl")));
        assertTrue(brochure.contains("<small>per bowl</small>"), "variant rows name the unit");
        assertTrue(brochure.contains("<span class=\"display-price\">per bowl</span>"),
                "the family shares the unit, so its reference price names it");
        assertTrue(brochure.contains("6 bowls"), "carton contents in the unit");
        assertTrue(brochure.contains("Inhoud per omdoos"), "the label no longer says pieces");
        assertTrue(brochure.contains("per vermelde eenheid"), "the footnote no longer promises per-piece prices");
        assertFalse(brochure.contains("per stuk"), brochure);

        /* One colour sold per bowl, one per piece: no family-wide unit to claim. */
        List<Product> mixed = List.of(bowls.getFirst(), product(3L, "BOWL-PK", 100L, 1L, 2));
        String mixedHtml = normalizeWhitespace(renderer.renderHtml(
                unitModel(mixed, CatalogExportService.Layout.BROCHURE, "nl")));
        assertFalse(mixedHtml.contains("<span class=\"display-price\">per bowl</span>"), mixedHtml);
        assertTrue(mixedHtml.contains("<small>per bowl</small>") && mixedHtml.contains("<small>per stuk</small>"));

        /* Sold per display of eight bowls: the set leads and counts its bowls. */
        be.enrosed.catalog.domain.Packaging bowlSet = new be.enrosed.catalog.domain.Packaging(
                be.enrosed.catalog.domain.PackagingKind.DISPLAY, Dimensions.empty(), null, 8,
                be.enrosed.catalog.domain.SalesUnit.DISPLAY, "bowl");
        String sets = normalizeWhitespace(renderer.renderHtml(unitModel(List.of(
                withPackaging(product(6L, "BOWL-SET", 100L, 1L, 0), bowlSet)),
                CatalogExportService.Layout.SIMPLE, "en")));
        assertTrue(sets.contains("Set (8 bowls): "), sets);
        assertTrue(sets.contains("6 displays (48 bowls)"), sets);
        assertTrue(sets.contains("· per bowl</small>"), sets);

        String plain = normalizeWhitespace(renderer.renderHtml(unitModel(
                List.of(product(4L, "ROSE-RD", 100L, 1L, 0), product(5L, "ROSE-WT", 100L, 1L, 1)),
                CatalogExportService.Layout.BROCHURE, "nl")));
        assertTrue(plain.contains("<span class=\"display-price\">per stuk</span>"), plain);
        assertTrue(plain.contains("Prijs / st.") && plain.contains("Referentieprijzen per stuk"), plain);
        assertFalse(plain.contains("per vermelde eenheid"), plain);
    }

    private static CatalogExportService.Model unitModel(
            List<Product> variants, CatalogExportService.Layout layout, String language) {
        Category category = new Category(1L, "counter", "Counter Displays", "Retail-ready products", 0);
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                100L, "bowl-family", "bowl-family", 1L, "counter", "Counter Displays", 0, 0,
                "Bowl roses", "A lasting collection for gift-ready retail.",
                "A refined presentation with selected colour variants.",
                "Counter display", List.of("No daily water"), null, List.of(), List.of(), List.of());
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, true, false, 0, "ENROSED Wholesale", null, language, layout,
                new CatalogExportService.BrochureOptions(true, false, false, false, false,
                        "A lasting collection", "Ready for retail."));
        return new CatalogExportService.Model(variants, Map.of(category.id(), category),
                List.of(new CatalogExportService.FamilyGroup(family, variants, category, false)), request);
    }

    private static Product withPackaging(Product base, be.enrosed.catalog.domain.Packaging packaging) {
        return new Product(base.id(), base.sku(), base.name(), base.dimensions(), packaging,
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.supplierNote(), base.active(), base.familyId(),
                base.canonicalVariantKey(), base.canonicalBarcode(), base.variantPosition(),
                base.inventoryKnown(), base.familyKey(), base.publicHandle(),
                base.websiteStatus(), base.orderAppStatus(), base.barcodes(), base.hsCode(),
                base.carton(), base.exwPrice(), base.exwCurrency(), base.extraUnitCost(),
                base.landedCostEur(), base.landedCostSource(), base.markupPct(), base.fixedSalesPriceEur(),
                base.stockQuantity(), base.photos(), base.texts(), base.demo());
    }

    @Test
    void overviewListsEverySelectedFamilyAsARowOfTheRangeTable() {
        String html = renderer.renderHtml(model(57, CatalogExportService.Layout.BROCHURE));
        assertEquals(19, occurrences(html, "class=\"range-row range-row--"), "one line per family");
        List<String> overviewPages = overviewPageFragments(html);
        assertTrue(overviewPages.size() >= 1);
        for (String overviewPage : overviewPages) {
            int lines = occurrences(overviewPage, "class=\"range-row range-row--")
                    + occurrences(overviewPage, "class=\"range-group range-group--");
            assertTrue(lines <= 12, "an A4 range page reserves room for wrapped commercial details");
            assertFalse(overviewPage.trim().endsWith("range-group"), "a chapter heading never ends a page");
        }
        assertTrue(html.contains("class=\"range-group range-group--tone-1\""), "the first chapter is bordeaux");
        assertTrue(html.contains("href=\"#family-19\""));
        assertTrue(html.contains("id=\"family-19\""));
        assertFalse(html.contains("family-20"));
        assertTrue(html.indexOf("class=\"page overview-page\"")
                < html.indexOf("id=\"family-01\""),
                "the complete glance must precede every family detail page");
    }

    @Test
    void brochureResolvesNavySwatchesBeforeTranslatingColourLabels() {
        String html = renderer.renderHtml(colourSwatchModel("Navy", "Marineblauw", null));

        assertBrochureColourSwatch(html, "Marineblauw", "#243253");
        assertFalse(html.contains("class=\"dot dot--none\""),
                "a known canonical colour must not become an unspecified dot after translation");
    }

    @Test
    void brochureKeepsAnExplicitSwatchInsteadOfTheStandardColourDefault() {
        String html = renderer.renderHtml(colourSwatchModel("Navy", "Marineblauw", "#123456"));

        assertBrochureColourSwatch(html, "Marineblauw", "#123456");
        assertFalse(html.contains("style=\"background:#243253\""),
                "a seller's exact colour sample wins over the standard navy shade");
    }

    @Test
    void brochureLeavesUnknownColoursUnspecifiedRatherThanInventingASwatch() {
        String html = renderer.renderHtml(colourSwatchModel("Custom finish", "Eigen afwerking", null));
        String overview = overviewPageFragments(html).getFirst();
        String detail = sectionFragment(html, "<section id=\"family-01\"");

        assertEquals(1, occurrences(overview, "class=\"dot dot--none\""));
        assertTrue(overview.contains("Eigen afwerking"));
        assertTrue(detail.contains("<span class=\"dot dot--none\"></span><span class=\"name\">Eigen afwerking</span>"));
        assertTrue(detail.contains("<td>Eigen afwerking · Small</td>"),
                "an unknown variant still has its name but no fabricated colour sample");
        assertFalse(html.contains("style=\"background:#243253\""));
    }

    private static void assertBrochureColourSwatch(String html, String label, String hex) {
        String overview = overviewPageFragments(html).getFirst();
        String detail = sectionFragment(html, "<section id=\"family-01\"");
        String dot = "<span class=\"dot\" style=\"background:" + hex + "\"></span>";

        assertTrue(overview.contains(dot), "the range overview shows the resolved colour dot");
        assertTrue(overview.contains(label), "the overview retains the translated colour label");
        assertTrue(detail.contains(dot + "<span class=\"name\">" + label + "</span>"),
                "the family header pairs the resolved dot with its translated label");
        assertTrue(detail.contains("<span class=\"swatch\" style=\"background:" + hex + "\"></span>" + label),
                "the variant table shows the same resolved colour as the overview and header");
    }

    private static CatalogExportService.Model colourSwatchModel(
            String canonicalColour, String dutchColour, String colourHex) {
        CatalogExportService.Model source = model(2, CatalogExportService.Layout.BROCHURE);
        CatalogExportService.FamilyGroup family = source.families().getFirst();
        Product first = family.variants().getFirst()
                .withVariantAttributes(canonicalColour, "Small", colourHex)
                .withTexts(List.of(new ProductText(Language.NL, null, null, dutchColour)));
        List<Product> variants = List.of(first, family.variants().get(1));
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, false, false, 0, null, null, "nl", source.request().layout(), source.request().brochure());
        return new CatalogExportService.Model(variants, source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        family.content(), variants, family.category(), family.synthetic())), request);
    }

    @Test
    void rangePagesNeverStrandAChapterHeadingAtTheirFoot() {
        List<List<int[]>> pages = PdfCatalogRenderer.overviewSlots(List.of(17, 1, 3, 20));
        assertEquals(List.of(12, 12, 12, 9), pages.stream().map(List::size).toList());
        for (List<int[]> page : pages) {
            assertTrue(page.getLast()[1] != -1, "a chapter heading never ends a page alone");
        }
        assertTrue(PdfCatalogRenderer.overviewSlots(List.of()).isEmpty());
        List<List<int[]>> productionShape = PdfCatalogRenderer.overviewSlots(List.of(5, 7, 7, 6, 3));
        assertEquals(3, productionShape.size());
        assertTrue(productionShape.getLast().size() > 2, "the last page must not strand a single family");
        assertTrue(productionShape.stream().allMatch(page -> page.size() <= 12));
    }

    @Test
    void domeOverviewAndPhysicalFamilyPagesUseHeightBeforeDiameterAndKeepOtherManualOrder()
            throws Exception {
        Category displays = new Category(1L, "counter", "Displays", null, 0);
        Category domes = new Category(9L, "domes", "Domes", null, 1);
        List<CatalogExportService.FamilyGroup> groups = List.of(
                orderingFamily(200L, "display-large", "Display large", "Manual first", displays, 40, 80),
                orderingFamily(201L, "display-small", "Display small", "Manual second", displays, 4, 5),
                orderingFamily(15L, "cobalt-blue-roos-in-glazen-stolp", "Rose In Dome - 12*25", "Lumi 25", domes, 12, 25),
                orderingFamily(42L, "model-123-124", "Rose In Dome - 7*23", "Slim 23", domes, 7, 23),
                orderingFamily(20L, "rose-in-dome-elite", "Rose In Dome - 15x30 Tripple", "Trio 30", domes, 15, 30),
                orderingFamily(25L, "rose-in-dome-xl", "Rose In Dome - 12*20", "Petite 20", domes, 12, 20),
                orderingFamily(31L, "odoo-dome-15x30-single-review", "Rose In Dome - 15x30 Single", "Grande 30", domes, 15, 30),
                orderingFamily(22L, "acrylic-flowerbox", "Acrylic Flowerbox", "Flower Cube 21", domes, 12, 21),
                orderingFamily(41L, "model-119-120", "Rose In Dome - 12*25 NO GIFTBOX", "Classic 25", domes, 12, 25));
        List<Long> expectedOrder = List.of(200L, 201L, 25L, 42L, 15L, 41L, 20L, 31L, 22L);
        CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                true, false, false, false, false, "A lasting collection", "Ready for retail");
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, false, false, 0, null, null, "en", CatalogExportService.Layout.BROCHURE, options);
        CatalogExportService.Model catalog = new CatalogExportService.Model(
                groups.stream().flatMap(group -> group.variants().stream()).toList(),
                Map.of(displays.id(), displays, domes.id(), domes), groups, request);
        Map<Long, CatalogExportService.FamilyGroup> byId = new LinkedHashMap<>();
        groups.forEach(group -> byId.put(group.content().id(), group));

        String html = renderer.renderHtml(catalog);
        List<String> overview = overviewPageFragments(html);
        assertEquals(1, overview.size());
        int previousRow = -1;
        try (PDDocument pdf = Loader.loadPDF(renderer.render(catalog).content())) {
            assertEquals(11, pdf.getNumberOfPages(), "cover, one overview and nine family sheets");
            for (int index = 0; index < expectedOrder.size(); index++) {
                CatalogExportService.FamilyGroup group = byId.get(expectedOrder.get(index));
                String number = String.format(java.util.Locale.ROOT, "%02d", index + 1);
                String link = "href=\"#family-" + number + "\">" + group.content().name() + "</a>";
                int row = overview.getFirst().indexOf(link);
                assertTrue(row > previousRow, "the overview must place " + group.content().name() + " in reading order");
                previousRow = row;
                String detail = sectionFragment(html, "<section id=\"family-" + number + "\"");
                assertTrue(detail.contains("<h2>" + group.content().name() + "</h2>"));
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(index + 3);
                stripper.setEndPage(index + 3);
                String sheet = normalizeWhitespace(stripper.getText(pdf));
                assertTrue(sheet.contains(group.content().name()), "the linked physical sheet has the right family");
                assertTrue(sheet.replaceAll("\\s+", "").contains(group.variants().getFirst().sku()));
            }
        }
    }

    @Test
    void domeSortingKeepsEqualSizesStableAndLeavesUnknownSizesAndNonDomesAfterKnownDomes() {
        Category domes = new Category(9L, "domes", "Domes", null, 1);
        Category other = new Category(1L, "counter", "Displays", null, 0);
        List<CatalogExportService.FamilyGroup> input = List.of(
                orderingFamily(301L, "acrylic-flowerbox", "Acrylic Flowerbox", "Cube", domes, 4, 5),
                orderingFamily(302L, "rose-in-dome-wide", "Source model", "Wide", domes, 15, 25),
                orderingFamily(303L, "rose-in-dome-other-category", "Source model", "Keep this slot", other, 1, 1),
                orderingFamily(304L, "rose-in-dome-narrow", "Source model", "First equal size", domes, 12, 25),
                orderingFamily(305L, "rose-in-dome-narrow-two", "Source model", "Second equal size", domes, 12, 25),
                orderingFamily(306L, "rose-in-dome-unmeasured", "Source model", "Unknown height", domes, 12, 0),
                orderingFamily(307L, "rectangular-flowerbox", "Flowerbox", "Second box", domes, 2, 3));

        List<CatalogExportService.FamilyGroup> ordered = PdfCatalogRenderer.brochureFamilyOrder(input);

        assertEquals(List.of(304L, 305L, 303L, 302L, 306L, 301L, 307L),
                ordered.stream().map(group -> group.content().id()).toList());
        assertEquals(List.of(301L, 302L, 303L, 304L, 305L, 306L, 307L),
                input.stream().map(group -> group.content().id()).toList(), "source/manual order is not mutated");
    }

    @Test
    void domeSortingRecognizesFinalizedAndDutchCompoundIdentitiesWithoutUsingDisplayCopy() {
        Category domes = new Category(9L, "domes", "Domes", null, 1);
        List<CatalogExportService.FamilyGroup> input = List.of(
                orderingFamily(301L, "acrylic-flowerbox", "Acrylic Flowerbox", "Rozenstolp display", domes, 1, 1),
                orderingFamily(302L, "model-119-120", "Rozenstolp Classic 25 cm", "Classic", domes, 12, 25),
                orderingFamily(303L, "rose-in-dome-7x23", "Source model", "Slim", domes, 7, 23),
                orderingFamily(304L, "rozenstolp-petite", "Source model", "Petite", domes, 12, 20));

        assertEquals(List.of(304L, 303L, 302L, 301L),
                PdfCatalogRenderer.brochureFamilyOrder(input).stream()
                        .map(group -> group.content().id()).toList());
    }

    @Test
    void aSingleFamilyStillGetsItsChapterLineAndPageReference() {
        String html = renderer.renderHtml(model(1, CatalogExportService.Layout.BROCHURE));

        assertEquals(1, occurrences(html, "class=\"range-row range-row--"));
        assertEquals(1, occurrences(html, "class=\"range-group range-group--"));
        assertTrue(html.contains("<span class=\"ref\">01</span>"), "the row carries its number and page");
        assertFalse(html.contains("overview-summary"), "the range table carries facts, not prose");
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
        String expectedHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(owned), 16, 9, 2_400, Color.WHITE);
        assertTrue(html.contains(expectedHero));
    }

    @Test
    void actualSelectedPhotoIsNeverReplacedByACuratedFamilyOverride()
            throws Exception {
        Photo actual = storedPhoto(941L, "/images/soap-roos-in-box-480.webp",
                "soap-led-actual.webp", "image/webp");
        CatalogExportService.Model catalog = withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led");

        String html = renderer.renderHtml(catalog);
        String curatedHero = editorialAssets.contained(
                "families/soap-rose-box-led.png", 1_600, 900);
        String actualHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(actual), 16, 9, 2_400, Color.WHITE);

        assertFalse(curatedHero.isBlank());
        assertFalse(html.contains(curatedHero));
        assertTrue(html.contains(actualHero));
        assertTrue(html.contains("class=\"family-media family-media--one\""));
    }

    @Test
    void selectedPhotosStayHiddenEverywhereWhenProductPhotosAreDisabled() throws Exception {
        Photo actual = storedPhoto(942L, "/images/soap-roos-in-box-480.webp",
                "soap-led-disabled.webp", "image/webp");
        CatalogExportService.Model catalog = withPhotoBudget(withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led"), false, 0);

        String html = renderer.renderHtml(catalog);
        String actualHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(actual), 16, 9, 2_400, Color.WHITE);

        assertFalse(html.contains(actualHero));
        assertFalse(html.contains("editorial-grid--one"));
        assertTrue(html.contains("class=\"image-placeholder\""));
    }

    @Test
    void onePhotoBudgetUsesOneActualPhotoWithoutDuplicatingItIntoAnotherTile()
            throws Exception {
        Photo actual = storedPhoto(943L, "/images/soap-roos-in-box-480.webp",
                "soap-led-single.webp", "image/webp");
        CatalogExportService.Model catalog = withPhotoBudget(withFamilyKey(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), actual),
                "soap-rose-box-led"), true, 1);

        String html = renderer.renderHtml(catalog);
        String actualHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(actual), 16, 9, 2_400, Color.WHITE);

        assertTrue(html.contains(actualHero));
        assertEquals(1, occurrences(html, actualHero));
        assertTrue(html.contains("class=\"family-media family-media--one\""));
        assertFalse(html.contains("class=\"tile--half\""));
    }

    @Test
    void uniquePhotosUsePurposeBuiltOneTwoThreeAndFourPlusCompositions() throws Exception {
        Photo one = storedPhoto(951L, "/catalog-assets/counter-bowl-retail.jpg",
                "layout-one.jpg", "image/jpeg");
        Photo two = storedPhoto(952L, "/catalog-assets/preserved-roses.jpg",
                "layout-two.jpg", "image/jpeg");
        Photo three = storedPhoto(953L, "/catalog-assets/soap-roses.jpg",
                "layout-three.jpg", "image/jpeg");
        Photo four = storedPhoto(954L, "/catalog-assets/atelier.jpg",
                "layout-four.jpg", "image/jpeg");
        Photo five = storedPhoto(955L, "/catalog-assets/hero-open-desktop.jpg",
                "layout-five.jpg", "image/jpeg");
        CatalogExportService.Model pictured = withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE),
                List.of(one, two, one, three, four, five));

        String single = renderer.renderHtml(withPhotoBudget(pictured, true, 1));
        String duo = renderer.renderHtml(withPhotoBudget(pictured, true, 2));
        String trio = renderer.renderHtml(withPhotoBudget(pictured, true, 3));
        String mosaic = renderer.renderHtml(withPhotoBudget(pictured, true, 6));

        assertTrue(single.contains("class=\"family-media family-media--one\""));
        assertTrue(duo.contains("class=\"family-media family-media--two\""));
        assertEquals(2, occurrences(familyMediaFragment(duo), "class=\"tile--half\""));
        assertTrue(trio.contains("class=\"family-media family-media--three\""));
        assertEquals(1, occurrences(familyMediaFragment(trio), "rowspan=\"2\""));
        assertTrue(mosaic.contains("class=\"family-media family-media--four-plus\""));
        assertEquals(4, occurrences(familyMediaFragment(mosaic), "class=\"tile--quarter\""));
        assertEquals(1, occurrences(mosaic, "<div class=\"gallery-strip\"><img"),
                "the duplicate source key must not consume a tile or create a second extra image");
    }

    @Test
    void internalInheritedPhotoNeverLeaksEvenIfItHasAnOldCatalogueLeadFlag() throws Exception {
        Photo inheritedSource = storedPhoto(95L, "/catalog-assets/counter-bowl-retail.jpg",
                "internal-family-photo.jpg", "image/jpeg");
        Photo inherited = new Photo(
                inheritedSource.id(), inheritedSource.storageKey(), inheritedSource.originalFilename(),
                inheritedSource.contentType(), inheritedSource.sizeBytes(), inheritedSource.widthPx(),
                inheritedSource.heightPx(), 0, 501L,
                java.util.Set.of(be.enrosed.catalog.domain.PhotoRole.CATALOGUE));
        Photo owned = storedPhoto(96L, "/images/soap-roos-in-box-480.webp",
                "owned-product-photo.webp", "image/webp");
        List<Photo> photos = List.of(inherited, owned);
        CatalogExportService.Model simple = withPhotos(
                model(1, CatalogExportService.Layout.SIMPLE), photos);
        CatalogExportService.Model brochure = withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE), photos);

        String simpleHtml = renderer.renderHtml(simple);
        String brochureHtml = renderer.renderHtml(brochure);
        String inheritedSimple = imageEncoder.encodeContainedTrimmed(
                photoBytes(inherited), 4, 3, 1_000, Color.WHITE);
        String ownedSimple = imageEncoder.encodeContainedTrimmed(
                photoBytes(owned), 4, 3, 1_000, Color.WHITE);
        String inheritedHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(inherited), 16, 9, 2_400, Color.WHITE);
        String ownedHero = imageEncoder.encodeContainedTrimmed(
                photoBytes(owned), 16, 9, 2_400, Color.WHITE);

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

        assertTrue(html.contains(imageEncoder.encodeContainedTrimmed(
                        photoBytes(inherited), 4, 3, 1_000, Color.WHITE)),
                "a CATALOGUE-published inherited family photo must remain usable");
    }

    @Test
    void explicitCatalogueLeadWinsOverBiggerPhotosOnFamilyAndSimpleLayouts() throws Exception {
        Photo large = storedPhoto(976L, "/catalog-assets/preserved-roses.jpg",
                "automatic-large.jpg", "image/jpeg");
        Photo other = storedPhoto(977L, "/catalog-assets/counter-bowl-retail.jpg",
                "automatic-counter.jpg", "image/jpeg");
        Photo chosen = storedPhoto(978L, "/images/soap-roos-in-box-480.webp",
                "chosen-studio.webp", "image/webp")
                .withLeadFor(java.util.Set.of(be.enrosed.catalog.domain.PhotoRole.CATALOGUE));
        CatalogExportService.Model source = withPhotoBudget(withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE), List.of(large, other, chosen)), true, 3);
        CatalogExportService.FamilyGroup oldGroup = source.families().getFirst();
        Category category = oldGroup.category().withPhotos(List.of(large));
        CatalogExportService.Model selected = new CatalogExportService.Model(source.products(),
                Map.of(category.id(), category), List.of(new CatalogExportService.FamilyGroup(
                        oldGroup.content(), oldGroup.variants(), category, false)), source.request());

        String html = renderer.renderHtml(selected);
        String cover = sectionFragment(html, "<section class=\"page cover\">");
        assertFalse(cover.contains("editorial-grid"));
        String familyMedia = familyMediaFragment(html);
        String chosenDetail = imageEncoder.encodeContainedTrimmed(
                photoBytes(chosen), 7, 6, 1_900, Color.WHITE);
        assertTrue(familyMedia.substring(familyMedia.indexOf("src=\"") + 5).startsWith(chosenDetail),
                "an explicit print lead stays in the large tile even when another photo is larger");

        String simple = renderer.renderHtml(withPhotoBudget(withPhotos(
                model(1, CatalogExportService.Layout.SIMPLE), List.of(large, other, chosen)), true, 1));
        assertTrue(simple.contains(imageEncoder.encodeContainedTrimmed(
                photoBytes(chosen), 4, 3, 1_000, Color.WHITE)));
        String bounded = renderer.renderHtml(withPhotoBudget(withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE), List.of(chosen,
                        large.withLeadFor(chosen.leadFor()), other.withLeadFor(chosen.leadFor()))), true, 2));
        assertTrue(bounded.contains("class=\"family-media family-media--two\""),
                "multiple explicit leads cannot overrun the requested photo budget");
        assertFalse(bounded.contains("class=\"family-media family-media--three\""));
    }

    @Test
    void categoryUsesSelectedPhotosWhileBothCoversKeepTheBrandComposition()
            throws Exception {
        List<Photo> selected = List.of(
                storedPhoto(981L, "/catalog-assets/counter-bowl-retail.jpg",
                        "selected-counter.jpg", "image/jpeg"),
                storedPhoto(982L, "/catalog-assets/preserved-roses.jpg",
                        "selected-preserved.jpg", "image/jpeg"),
                storedPhoto(983L, "/catalog-assets/soap-roses.jpg",
                        "selected-soap.jpg", "image/jpeg"),
                storedPhoto(984L, "/catalog-assets/atelier.jpg",
                        "selected-atelier.jpg", "image/jpeg"));
        CatalogExportService.Model catalog = withPhotoBudget(withPhotos(
                model(1, CatalogExportService.Layout.BROCHURE), selected), true, 4);
        String oldFront = editorialAssets.image("catalog-cover-v3.png");
        String oldBack = editorialAssets.image("catalog-back-cover-v1.png");

        String html = renderer.renderHtml(catalog);

        assertFalse(html.contains(oldFront));
        assertFalse(html.contains(oldBack));
        assertEquals(1, occurrences(html, "editorial-grid--four-plus"),
                "the category chapter opens with a mosaic of all four selected photos");
        assertEquals(0, occurrences(html, "editorial-grid--three"),
                "the back cover must contain no product mosaic");
        String cover = sectionFragment(html, "<section class=\"page cover\">");
        String back = sectionFragment(html, "<section class=\"page back\">");
        String backLead = imageEncoder.encodeContainedTrimmed(
                photoBytes(selected.get(2)), 184, 107, 2_200, Color.WHITE);
        assertFalse(cover.contains("editorial-grid"));
        assertEquals(1, occurrences(cover, "<img "), "the brand logo is the only cover image");
        assertFalse(back.contains(backLead));
        assertFalse(back.contains("editorial-grid"));
        assertEquals(2, occurrences(back, "<img "), "the back cover displays the brand logo and quote QR");
        assertTrue(back.contains("class=\"back-quote\""));
        assertTrue(back.contains("Start your quote request"));
        assertTrue(back.contains("href=\"https://enrosed.com/quote/\""));
        assertFalse(cover.contains(backLead));
        assertFalse(cover.contains("class=\"cover-toc\""));
        assertTrue(html.indexOf("class=\"page cover\"")
                < html.indexOf("class=\"page overview-page\""));
        assertTrue(html.contains("class=\"page back\""));
    }

    @Test
    void coverUsesLargeGoldBrandingAndReadableBordeauxCopy() throws Exception {
        Photo selected = storedPhoto(985L, "/catalog-assets/preserved-roses.jpg",
                "selected-cover.jpg", "image/jpeg");
        String html = renderer.renderHtml(withPhoto(
                model(1, CatalogExportService.Layout.BROCHURE), selected));

        String cover = sectionFragment(html, "<section class=\"page cover\">");
        String back = sectionFragment(html, "<section class=\"page back\">");
        String printLogo = editorialAssets.image("logo-gold-print.png");
        assertFalse(printLogo.isBlank(), "the high-resolution gold print logo must be bundled");
        assertTrue(cover.contains("class=\"cover-brand\""));
        assertTrue(cover.contains("class=\"cover-logo\""));
        assertTrue(cover.contains("src=\"" + printLogo + "\""));
        assertTrue(back.contains("src=\"" + printLogo + "\""));
        String simpleLogo = editorialAssets.image("logo-gold.png");
        assertFalse(simpleLogo.isBlank(), "the SIMPLE layout keeps its existing bundled logo");
        String simple = renderer.renderHtml(model(1, CatalogExportService.Layout.SIMPLE));
        assertTrue(simple.contains("src=\"" + simpleLogo + "\""));
        assertFalse(simple.contains(printLogo));
        assertTrue(html.contains("background: #651629"));
        assertFalse(cover.contains("cover-media"));
        assertTrue(cover.contains("class=\"cover-copy\""));
    }

    @Test
    void blankBrochureIntroLeavesTheCoverCleanWhileCustomIntroRemainsVisible() throws Exception {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        for (String intro : new String[] {null, "", "   "}) {
            String cover = sectionFragment(renderer.renderHtml(
                    withIntroAndLanguage(source, intro, "en")), "<section class=\"page cover\">");
            assertFalse(cover.isBlank());
            assertFalse(cover.contains("cover-facts"), "selection totals do not belong on the cover");
            assertFalse(cover.contains("1 FAMILY"));
            assertFalse(cover.contains("1 VARIANT"));
            assertFalse(cover.contains("class=\"cover-intro\""),
                    "a missing or blank request intro must not insert automatic copy");
        }

        String customIntro = "Prepared for our autumn trade customers.";
        CatalogExportService.Model custom = withIntroAndLanguage(source, "  " + customIntro + "  ", "en");
        String cover = sectionFragment(renderer.renderHtml(custom), "<section class=\"page cover\">");
        assertTrue(cover.contains("<p class=\"cover-intro\">" + customIntro + "</p>"));
        assertFalse(cover.contains("cover-facts"));
        try (PDDocument pdf = Loader.loadPDF(renderer.render(custom).content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String coverText = normalizeWhitespace(stripper.getText(pdf));
            assertTrue(coverText.contains(customIntro), "the custom intro stays on the physical cover");
            assertFalse(coverText.contains("1 FAMILY"));
            assertFalse(coverText.contains("1 VARIANT"));
        }
    }

    @Test
    void utilityPagesUseDedicatedEditorialAssetsWhileRespectingThePhotoSetting() throws Exception {
        String atelierImage = editorialAssets.image("private-label-editorial-v2.png");
        String orderingImage = editorialAssets.image("ordering-editorial-v2.png");
        assertFalse(atelierImage.isBlank(), "the private-label editorial asset must be bundled");
        assertFalse(orderingImage.isBlank(), "the ordering editorial asset must be bundled");
        assertFalse(atelierImage.equals(orderingImage), "each utility page has its own editorial image");
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        Photo soap = storedPhoto(987L, "/images/soap-roos-in-box-480.webp",
                "utility-selection-soap.webp", "image/webp");
        Photo bowl = storedPhoto(988L, "/catalog-assets/counter-bowl-retail.jpg",
                "utility-selection-bowl.jpg", "image/jpeg");
        String atelierMarker = "<section class=\"page ivory utility\">";
        String orderingMarker = "<section class=\"page utility\">";
        String expectedAtelier = null;
        String expectedOrdering = null;
        for (CatalogExportService.Model selection : List.of(
                withPhoto(source, soap), withPhoto(source, bowl))) {
            String html = renderer.renderHtml(selection);
            assertEquals(1, occurrences(html, atelierMarker));
            assertEquals(1, occurrences(html, orderingMarker));
            String atelier = sectionFragment(html, atelierMarker);
            String ordering = sectionFragment(html, orderingMarker);
            assertTrue(atelier.contains("class=\"utility-image\" src=\"" + atelierImage + "\""));
            assertTrue(ordering.contains("class=\"utility-image\" src=\"" + orderingImage + "\""));
            assertFalse(atelier.contains(orderingImage));
            assertFalse(ordering.contains(atelierImage));
            assertTrue(atelier.contains("Your brand. Our roses."));
            assertTrue(atelier.contains("Packaging concept for inspiration."));
            assertTrue(ordering.contains("Your next collection starts here."));
            if (expectedAtelier != null) {
                assertEquals(expectedAtelier, atelier,
                        "a different selected product photo must not change the atelier page");
                assertEquals(expectedOrdering, ordering,
                        "a different selected product photo must not change the ordering page");
            }
            expectedAtelier = atelier;
            expectedOrdering = ordering;
        }
        String withoutPhotos = renderer.renderHtml(source);
        String unpicturedAtelier = sectionFragment(withoutPhotos, atelierMarker);
        String unpicturedOrdering = sectionFragment(withoutPhotos, orderingMarker);
        assertTrue(unpicturedAtelier.contains("Your brand. Our roses."));
        assertTrue(unpicturedOrdering.contains("Your next collection starts here."));
        assertFalse(unpicturedAtelier.contains("class=\"utility-image\""));
        assertFalse(unpicturedOrdering.contains("class=\"utility-image\""));
        assertFalse(unpicturedAtelier.contains(atelierImage));
        assertFalse(unpicturedOrdering.contains(orderingImage));
        assertTrue(unpicturedOrdering.contains("class=\"quote-qr\""),
                "the ordering QR remains functional when product photos are disabled");
    }

    @Test
    void roseHeadPaletteKeepsTwentyNamedColoursAndMaterialCopyOnOnePageInEveryLocale()
            throws Exception {
        String plate = editorialAssets.image("rose-head-colour-palette-v1.png");
        assertFalse(plate.isBlank(), "the reviewed palette plate must be bundled");
        Photo fixture = storedPhoto(989L, "/images/soap-roos-in-box-480.webp",
                "palette-selection.webp", "image/webp");
        List<String> colourKeys = List.of("white", "ivory", "champagne", "peach", "yellow",
                "blushPink", "rosePink", "fuchsia", "cherryPink", "orange",
                "red", "bordeaux", "lilac", "purple", "black",
                "lightBlue", "blue", "navy", "mint", "emerald");
        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        for (Language language : Language.values()) {
            CatalogExportService.Model selected = localizedQaModel(
                    language, CatalogExportService.Layout.BROCHURE, fixture);
            String html = renderer.renderHtml(selected);
            String palette = sectionFragment(html, "<section class=\"page utility palette-page\"");
            Map<String, String> copy = PublicContentSeedLoader.catalogSeedValues(language);
            assertEquals(1, occurrences(html, "class=\"page utility palette-page\""));
            assertTrue(palette.contains("data-page=\"5\""));
            assertTrue(html.contains("<body data-page-count=\"8\" class=\"lang-"
                    + language.code() + "\">"));
            assertEquals(20, occurrences(palette, "class=\"palette-plate\""));
            assertEquals(20, occurrences(palette, "class=\"palette-label\""));
            assertTrue(html.indexOf(palette) > html.indexOf("id=\"family-01\""));
            assertTrue(html.indexOf(palette) < html.indexOf("<section class=\"page ivory utility\">"));
            int previousLabel = -1;
            for (String key : colourKeys) {
                String label = copy.get("catalog.brochure.palette.colour." + key);
                int currentLabel = palette.indexOf("class=\"palette-label\">" + label + "</div>");
                assertTrue(currentLabel > previousLabel, language + " ordered colour " + key);
                previousLabel = currentLabel;
            }
            CatalogDocumentRenderer.Document document = renderer.render(selected);
            Files.write(qa.resolve("palette-" + language.code() + ".pdf"), document.content());
            try (PDDocument pdf = Loader.loadPDF(document.content())) {
                assertEquals(8, pdf.getNumberOfPages(), language + " palette stays on one physical page");
                MatchingTextBoundsStripper text = new MatchingTextBoundsStripper();
                text.setStartPage(5);
                text.setEndPage(5);
                text.getText(pdf);
                for (Map.Entry<String, String> entry : copy.entrySet()) {
                    if (!entry.getKey().startsWith("catalog.brochure.palette.")
                            || entry.getKey().endsWith(".imagealt") || entry.getKey().endsWith(".kicker")) continue;
                    assertTrue(text.occurrencesOf(entry.getValue()) >= 1,
                            language + " complete vector text: " + entry.getKey());
                    assertTrue(text.bottomOf(entry.getValue()) < 790,
                            language + " above the footer: " + entry.getKey());
                    if (entry.getKey().endsWith(".title")) {
                        assertTrue(text.widthOf(entry.getValue()) < 148,
                                language + " material heading stays within its column: " + entry.getKey());
                    }
                }
            }
        }
    }

    @Test
    void paletteUsesTheExistingCustomisationSwitchAndRespectsPhotoAndBackCoverSettings()
            throws Exception {
        CatalogExportService.Model source = model(1, CatalogExportService.Layout.BROCHURE);
        String unpictured = renderer.renderHtml(source);
        String palette = sectionFragment(unpictured, "<section class=\"page utility palette-page\"");
        assertEquals(20, occurrences(palette, "class=\"palette-label\""));
        assertFalse(palette.contains("class=\"palette-plate\""), "the no-photo export omits the plate");
        assertFalse(renderer.renderHtml(model(1, CatalogExportService.Layout.SIMPLE))
                .contains("Your colour. Your collection."));
        for (boolean customisation : List.of(false, true)) {
            for (boolean backCover : List.of(false, true)) {
                CatalogExportService.Request old = source.request();
                CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                        true, true, customisation, true, backCover, null, null);
                CatalogExportService.Request request = new CatalogExportService.Request(
                        old.productIds(), old.includePrices(), old.includePhotos(), old.photosPerProduct(),
                        old.title(), old.intro(), old.language(), old.layout(), options, old.strictLanguage());
                CatalogExportService.Model selected = new CatalogExportService.Model(
                        source.products(), source.categoriesById(), source.families(), request);
                String html = renderer.renderHtml(selected);
                int expectedPages = 5 + (customisation ? 2 : 0) + (backCover ? 1 : 0);
                assertTrue(html.contains("<body data-page-count=\"" + expectedPages
                        + "\" class=\"lang-" + source.request().language() + "\">"));
                assertEquals(customisation, html.contains("class=\"page utility palette-page\""));
                assertEquals(customisation, html.contains("class=\"page ivory utility\""));
                assertEquals(backCover, html.contains("class=\"page back\""));
                try (PDDocument pdf = Loader.loadPDF(renderer.render(selected).content())) {
                    assertEquals(expectedPages, pdf.getNumberOfPages());
                }
            }
        }
    }

    @Test
    void familyPhotoOverrideAddsThreeAnglesWithoutExpandingOtherFamiliesOrLosingColourPhotos()
            throws Exception {
        List<Photo> angles = List.of(
                storedPhoto(991L, "/catalog-assets/counter-bowl-retail.jpg", "angle-one.jpg", "image/jpeg"),
                storedPhoto(992L, "/catalog-assets/preserved-roses.jpg", "angle-two.jpg", "image/jpeg"),
                storedPhoto(993L, "/catalog-assets/soap-roses.jpg", "angle-three.jpg", "image/jpeg"));
        for (CatalogExportService.Layout layout : CatalogExportService.Layout.values()) {
            CatalogExportService.Model source = model(3, layout);
            CatalogFamilyReader.Family base = source.families().getFirst().content();
            List<Product> products = List.of(
                    source.products().get(0).withCanonicalIdentity(16L, "mirror", null, 0, true)
                            .withPhotos(angles),
                    source.products().get(1).withCanonicalIdentity(17L, "other-red", null, 0, true)
                            .withPhotos(angles),
                    source.products().get(2).withCanonicalIdentity(17L, "other-white", null, 1, true)
                            .withPhotos(angles));
            List<CatalogExportService.FamilyGroup> groups = List.of(
                    new CatalogExportService.FamilyGroup(photoLimitFamily(base, 16L, "Mirror Rose"),
                            List.of(products.get(0)), source.families().getFirst().category(), false),
                    new CatalogExportService.FamilyGroup(photoLimitFamily(base, 17L, "Other Roses"),
                            products.subList(1, 3), source.families().getFirst().category(), false));
            CatalogExportService.Request request = new CatalogExportService.Request(
                    null, false, true, 1, "Photo selection", null, "en", layout,
                    source.request().brochure(), null, Map.of(16L, 3));
            CatalogExportService.Model selected = new CatalogExportService.Model(
                    products, source.categoriesById(), groups, request);
            String html = renderer.renderHtml(selected);
            if (layout == CatalogExportService.Layout.BROCHURE) {
                String mirror = sectionFragment(html, "<section id=\"family-01\"");
                String other = sectionFragment(html, "<section id=\"family-02\"");
                assertEquals(3, occurrences(familyMediaFragment(mirror), "<img "));
                assertEquals(1, occurrences(familyMediaFragment(other), "<img "),
                        "the unconfigured family keeps its one-photo main budget");
                assertEquals(2, occurrences(other, "data-sku="),
                        "both colour photos remain in addition to the single main photo");
            } else {
                assertEquals(1, occurrences(html, "class=\"media media--three\""));
                assertEquals(2, occurrences(html, "class=\"media media--one\""),
                        "both variants of the other family keep the global one-photo budget");
            }

            CatalogExportService.Request noPhotos = new CatalogExportService.Request(
                    null, false, false, 1, "Photo selection", null, "en", layout,
                    source.request().brochure(), null, Map.of(16L, 3));
            String unpictured = renderer.renderHtml(new CatalogExportService.Model(
                    products, source.categoriesById(), groups, noPhotos));
            if (layout == CatalogExportService.Layout.BROCHURE) {
                for (String anchor : List.of("family-01", "family-02")) {
                    String family = sectionFragment(unpictured, "<section id=\"" + anchor + "\"");
                    assertEquals(0, occurrences(familyMediaFragment(family), "<img "));
                    assertFalse(family.contains("data-sku="), "noPhotos also suppresses the colour strip");
                }
            } else {
                assertEquals(1, occurrences(unpictured, "<img "), "SIMPLE retains only its brand logo");
            }
        }
    }

    @Test
    void orderingQrAndClickableQuoteLinksFollowTheDutchAndEnglishCatalogueLanguage() throws Exception {
        for (String language : List.of("nl", "en")) {
            String quoteUrl = language.equals("nl")
                    ? "https://enrosed.com/nl/quote/" : "https://enrosed.com/quote/";
            String qr = editorialAssets.image("quote-qr-" + language + ".png");
            assertFalse(qr.isBlank(), "the " + language + " quote QR asset must be bundled");
            CatalogExportService.Model catalogue = withIntroAndLanguage(
                    model(1, CatalogExportService.Layout.BROCHURE), null, language);
            String ordering = sectionFragment(renderer.renderHtml(catalogue),
                    "<section class=\"page utility\">");
            assertEquals(2, occurrences(ordering, "href=\"" + quoteUrl + "\""),
                    "the text call to action and QR image link to the same localized quote page");
            assertTrue(ordering.contains("class=\"quote-qr\" src=\"" + qr + "\""));
            String atelierTitle = language.equals("nl") ? "Uw merk. Onze rozen." : "Your brand. Our roses.";
            String orderingTitle = language.equals("nl")
                    ? "Uw volgende collectie begint hier." : "Your next collection starts here.";
            try (PDDocument pdf = Loader.loadPDF(renderer.render(catalogue).content())) {
                assertEquals(8, pdf.getNumberOfPages(), "each utility page occupies one physical A4 page");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(6);
                stripper.setEndPage(6);
                assertTrue(normalizeWhitespace(stripper.getText(pdf)).contains(atelierTitle));
                stripper.setStartPage(7);
                stripper.setEndPage(7);
                assertTrue(normalizeWhitespace(stripper.getText(pdf)).contains(orderingTitle));
                long quoteLinks = pdf.getPage(6).getAnnotations().stream()
                        .filter(PDAnnotationLink.class::isInstance)
                        .map(PDAnnotationLink.class::cast)
                        .map(PDAnnotationLink::getAction)
                        .filter(PDActionURI.class::isInstance)
                        .map(PDActionURI.class::cast)
                        .filter(action -> quoteUrl.equals(action.getURI()))
                        .count();
                assertTrue(quoteLinks >= 2, "both quote actions remain clickable on the ordering PDF page");
            }
        }
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
        String familyOpeningTag = sectionFragment(html, "<section id=\"family-01\"")
                .split(">", 2)[0];
        assertTrue(familyOpeningTag.contains("family-page--compact"));
        CatalogDocumentRenderer.Document document = renderer.render(dense);
        Path qa = Path.of("target", "catalog-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("dense-family.pdf"), document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(8, pdf.getNumberOfPages());
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                assertTrue(pdf.getPage(page).getMediaBox().getHeight()
                        > pdf.getPage(page).getMediaBox().getWidth());
            }
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.replaceAll("\\s+", "").contains(finalMarker),
                    "the final copy marker may wrap but every character must remain in the PDF");
            assertTrue(text.contains("SKU-4"));
            assertTrue(maxTextBottom(pdf, 4) < pdf.getPage(3).getMediaBox().getHeight() - 4,
                    "dense family text must remain inside the visible A4 sheet");
        }
    }

    @Test
    void threePicturedVariantsWithModerateEnglishCopyKeepEverySkuOnFamilyDetailPage()
            throws Exception {
        CatalogExportService.Model source = model(3, CatalogExportService.Layout.BROCHURE);
        CatalogExportService.FamilyGroup originalGroup = source.families().getFirst();
        CatalogFamilyReader.Family original = originalGroup.content();
        Photo fixture = storedPhoto(986L, "/images/soap-roos-in-box-480.webp",
                "three-colour-detail.webp", "image/webp");
        List<String> skus = List.of("ENR-SOAP-ROSE-BOX-LED-RED",
                "ENR-SOAP-ROSE-BOX-LED-WHITE", "ENR-SOAP-ROSE-BOX-LED-CHERRY-PINK");
        List<String> colours = List.of("Red", "White", "Cherry Pink");
        List<String> colourHexes = List.of("#A91F32", "#EEE8DD", "#D9577E");
        List<Product> variants = new ArrayList<>();
        for (int index = 0; index < skus.size(); index++) {
            variants.add(source.products().get(index).withSku(skus.get(index))
                    .withVariantAttributes(colours.get(index), null, colourHexes.get(index))
                    .withPhotos(List.of(fixture)));
        }
        String summary = "A long presentation box of decorative soap roses with integrated LED light, "
                + "combining colour, fragrance and atmosphere in one gift format.";
        String description = summary + " The elongated form presents the roses as one continuous "
                + "composition, and the lighting adds a clearly visible atmospheric element.";
        List<String> highlights = List.of("10 × 50 cm presentation", "Three colour directions",
                "Fragrant decorative roses");
        assertEquals(477, summary.length() + description.length()
                + highlights.stream().mapToInt(String::length).sum(),
                "moderate translated copy must not rely on the existing long-copy layout trigger");
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(),
                original.categoryId(), original.categoryKey(), original.categoryName(),
                original.categoryPosition(), original.productPosition(), "Soap Roses with LED",
                summary, description, "Soap rose arrangement with integrated LED light", highlights,
                original.dimensions(), original.texts(), original.packages(), original.photos());
        CatalogExportService.Request oldRequest = source.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), false, true, 1, oldRequest.title(), oldRequest.intro(),
                "en", oldRequest.layout(), oldRequest.brochure());
        CatalogExportService.Model pictured = new CatalogExportService.Model(
                List.copyOf(variants), source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(
                        family, List.copyOf(variants), originalGroup.category(), false)), request);

        String html = renderer.renderHtml(pictured);
        assertEquals(3, occurrences(sectionFragment(html, "<section id=\"family-01\""), "data-sku="),
                "the colour-photo strip must remain present alongside all three product rows");
        CatalogDocumentRenderer.Document document = renderer.render(pictured);
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(8, pdf.getNumberOfPages(), "one family keeps its assigned A4 detail sheet");
            PDFTextStripper detail = new PDFTextStripper();
            detail.setStartPage(4);
            detail.setEndPage(4);
            String detailText = detail.getText(pdf);
            assertTrue(normalizeWhitespace(detailText).contains("Soap Roses with LED"));
            String unwrappedDetail = detailText.replaceAll("\\s+", "");
            for (String sku : skus) {
                assertTrue(unwrappedDetail.contains(sku),
                        () -> "Family detail page 4 must contain " + sku
                                + "; an occurrence in the assortment overview is insufficient");
            }
        }
    }

    @Test
    void twoPicturedPricedVariantsWithDutchCopyAndLogisticsStayAboveTheFooter() throws Exception {
        CatalogExportService.Model source = model(2, CatalogExportService.Layout.BROCHURE);
        CatalogFamilyReader.Family original = source.families().getFirst().content();
        String copy = "Een verkoopklaar cadeauarrangement met decoratieve foamrozen, langdurige kleur "
                + "en een verfijnde retailpresentatie.";
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(), original.categoryId(),
                original.categoryKey(), original.categoryName(), 0, 0,
                "Halve hartvorm met foamrozen 25 cm", copy, copy,
                "Verkoopklaar foamrozen-arrangement",
                List.of("Lang houdbare decoratieve foamrozen", "Consistente kleur en afwerking",
                        "Verkoopklaar cadeauarrangement"),
                null, List.of(), List.of(), List.of());
        List<String> skus = List.of("ENR-ODOO-HALF-HEART-FOAM-25-PINK", "ENR-ODOO-HALF-HEART-FOAM-25-RED");
        List<Product> variants = new ArrayList<>();
        for (int index = 0; index < skus.size(); index++) {
            Product variant = source.products().get(index).withSku(skus.get(index))
                    .withVariantAttributes(index == 0 ? "Roze" : "Rood", "25 cm",
                            index == 0 ? "#D889A2" : "#A91F32");
            variants.add(withPriceLayoutDetails(variant,
                    new Dimensions(new BigDecimal("24.5"), new BigDecimal("24.5"), new BigDecimal("12")),
                    new Carton(new Dimensions(new BigDecimal("55"), new BigDecimal("28"),
                            new BigDecimal("63.5")), 10, null),
                    "6702 10 00 00", new BigDecimal("4.95")));
        }
        Photo fixture = storedPhoto(989L, "/images/soap-roos-in-box-480.webp",
                "two-variant-price-layout.webp", "image/webp");
        assertPricedFamilyRowsStayAboveFooter(
                pricedPicturedFamily(source, family, variants, fixture), skus, "€ 4,95");
    }

    @Test
    void fourPicturedLongSkusWithDutchCopyAndRequestPricesStayAboveTheFooter() throws Exception {
        CatalogExportService.Model source = model(4, CatalogExportService.Layout.BROCHURE);
        CatalogFamilyReader.Family original = source.families().getFirst().content();
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                original.id(), original.familyKey(), original.publicHandle(), original.categoryId(),
                original.categoryKey(), original.categoryName(), 0, 0, "12 Steelrozen met display",
                "Afzonderlijk gepresenteerde gepreserveerde rozen met lange steel in transparante hoezen "
                        + "en zwarte houders, gegroepeerd in een display dat rechtstreeks van doos naar toonbank gaat.",
                "Deze gepreserveerde rozen worden per stuk geleverd in een stijlvolle zwarte koker met "
                        + "transparante top, gepresenteerd in een stevige toonbankdisplay. Ideaal als impulsproduct "
                        + "aan de kassa : premium uitstraling, direct verkoopklaar en perfect voor last-minute "
                        + "cadeaus. De rozen behouden langdurig hun mooie vorm en kleur, zonder water of onderhoud"
                        + "—waardoor ze zeer geschikt zijn voor retail, bloemisten en cadeauwinkels, vooral tijdens "
                        + "Valentijn, Moederdag en feestdagen.",
                "12 afzonderlijk gepresenteerde steelrozen",
                List.of("Sterk toonbankproduct : valt meteen op bij de kassa",
                        "Hoge cadeauwaarde : luxe verpakking verhoogt gemiddelde bon",
                        "Onderhoudsvrij : geen water, geen verwelking, geen verlies door derving",
                        "Snel cadeau : ideaal voor “ik heb nog iets nodig”-momenten",
                        "Klaar voor presentatie: netjes presenteren, snel aanvullen"),
                null, List.of(), List.of(), List.of());
        List<String> skus = List.of("ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-RED",
                "ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-PINK", "ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-BLUE",
                "ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-WHITE");
        List<String> colours = List.of("Rood", "Roze", "Blauw", "Wit");
        List<String> hexes = List.of("#A91F32", "#D889A2", "#6C8FC4", "#EEE8DD");
        List<Product> variants = new ArrayList<>();
        for (int index = 0; index < skus.size(); index++) {
            Product variant = source.products().get(index).withSku(skus.get(index))
                    .withVariantAttributes(colours.get(index), null, hexes.get(index));
            variants.add(withPriceLayoutDetails(variant, Dimensions.empty(), Carton.empty(),
                    null, BigDecimal.ZERO));
        }
        Photo fixture = storedPhoto(990L, "/images/soap-roos-in-box-480.webp",
                "four-variant-price-layout.webp", "image/webp");
        assertPricedFamilyRowsStayAboveFooter(
                pricedPicturedFamily(source, family, variants, fixture), skus, "Prijs op aanvraag");
    }

    private void assertPricedFamilyRowsStayAboveFooter(
            CatalogExportService.Model model, List<String> skus, String expectedPrice) throws Exception {
        String family = sectionFragment(renderer.renderHtml(model), "<section id=\"family-01\"");
        assertEquals(skus.size(), occurrences(family, "data-sku="),
                "the regression must retain a photo for every selected colour");
        assertEquals(skus.size(), occurrences(family, "class=\"variant-price\""),
                "every SKU has a separate price line in its order row");
        try (PDDocument pdf = Loader.loadPDF(renderer.render(model).content())) {
            assertEquals(8, pdf.getNumberOfPages(), "priced rows stay on their assigned family detail page");
            MatchingTextBoundsStripper detail = new MatchingTextBoundsStripper();
            detail.setStartPage(4);
            detail.setEndPage(4);
            detail.getText(pdf);
            for (String sku : skus) {
                double bottom = detail.bottomOf(sku);
                assertTrue(bottom > 0, "the physical family detail page must contain " + sku);
                assertTrue(bottom < 795, () -> sku + " reaches " + bottom
                        + " pt and must stay clear of the footer rule near 802 pt");
            }
            assertEquals(skus.size() + 1, detail.occurrencesOf(expectedPrice),
                    "the reference price and every variant price must remain on the detail page");
            double lastPriceBottom = detail.bottomOf(expectedPrice);
            assertTrue(lastPriceBottom < 795, () -> "The last price reaches " + lastPriceBottom
                    + " pt and must stay clear of the footer rule near 802 pt");
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
            assertEquals(8, pdf.getNumberOfPages());
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
    void overviewNamesStayLocalizedWithoutRenderingTheViewProductCta() {
        Photo photo = new Photo(93L, "unused.jpg", "unused.jpg", "image/jpeg",
                1L, 1, 1, 0);
        for (Language language : Language.values()) {
            String html = renderer.renderHtml(localizedQaModel(
                    language, CatalogExportService.Layout.BROCHURE, photo));
            assertTrue(html.contains("href=\"#family-01\""), language.code());
            assertTrue(html.contains("id=\"family-01\""), language.code());
            assertTrue(html.contains(localizedFamilyName(language)), language.code());
            assertFalse(html.contains("class=\"overview-detail\""), language.code());
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
    void identicalKnownVariantPricesRemainOneExactReferencePrice() {
        String html = renderer.renderHtml(model(3, CatalogExportService.Layout.BROCHURE));
        String family = sectionFragment(html, "<section id=\"family-01\"");
        String header = family.substring(0, family.indexOf("class=\"gold-rule\""));
        assertTrue(header.contains("€4.95"));
        assertFalse(header.contains("From €4.95"));
        assertFalse(header.contains("Price on request"));
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
    void writesEverySupportedLocaleQaCatalogWhenRequested() throws Exception {
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
                    assertTrue(extracted.replaceAll("\\s+", " ").contains(localizedFamilyName(language)), stem);
                    assertFalse(extracted.contains("###"), "all localized glyphs must render: " + stem);
                    assertFalse(extracted.toLowerCase().contains("dashboard"), stem);
                    assertFalse(extracted.toLowerCase().contains("canonical"), stem);
                    assertFalse(extracted.toLowerCase().contains("provenance"), stem);
                    assertFalse(extracted.toLowerCase().contains("candidate"), stem);
                }
                written++;
            }
        }
        assertEquals(Language.values().length * CatalogExportService.Layout.values().length, written);
        try (var files = Files.list(output)) {
            assertEquals(written, files.filter(path -> path.getFileName().toString()
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

    private static CatalogFamilyReader.Family photoLimitFamily(
            CatalogFamilyReader.Family base, Long id, String name) {
        return new CatalogFamilyReader.Family(id, "photo-limit-" + id, "photo-limit-" + id,
                base.categoryId(), base.categoryKey(), base.categoryName(), base.categoryPosition(),
                base.productPosition(), name, base.summary(), base.description(), base.format(),
                base.highlights(), base.dimensions(), base.texts(), base.packages(), base.photos());
    }

    private static CatalogExportService.FamilyGroup orderingFamily(
            Long id, String key, String rawModelName, String displayName, Category category,
            int diameter, int height) {
        Product product = withPriceLayoutDetails(
                product(id, rawModelName, id, category.id(), 0).withSku("ORDER-" + id),
                new Dimensions(BigDecimal.valueOf(diameter), BigDecimal.valueOf(diameter), BigDecimal.valueOf(height)),
                Carton.empty(), null, BigDecimal.ZERO);
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                id, key, key, category.id(), category.code(), category.name(), category.position(), 0,
                displayName, null, null, null, List.of(),
                new CatalogFamilyReader.Dimensions(BigDecimal.valueOf(diameter), BigDecimal.valueOf(height), null, "cm"),
                List.of(), List.of(), List.of());
        return new CatalogExportService.FamilyGroup(family, List.of(product), category, false);
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

    private static CatalogExportService.Model withIntroAndLanguage(
            CatalogExportService.Model model, String intro, String language) {
        CatalogExportService.Request oldRequest = model.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), oldRequest.includePrices(), oldRequest.includePhotos(),
                oldRequest.photosPerProduct(), oldRequest.title(), intro, language,
                oldRequest.layout(), oldRequest.brochure(), oldRequest.strictLanguage());
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

    private static Product withPriceLayoutDetails(
            Product base, Dimensions dimensions, Carton carton, String hsCode, BigDecimal price) {
        return new Product(
                base.id(), base.sku(), base.name(), dimensions, base.packaging(),
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.active(), base.familyId(),
                base.canonicalVariantKey(), null, base.variantPosition(), base.inventoryKnown(),
                base.familyKey(), base.publicHandle(), base.websiteStatus(), base.orderAppStatus(),
                base.barcodes(), hsCode, carton, base.exwPrice(), base.exwCurrency(), base.extraUnitCost(),
                BigDecimal.ZERO, "test", BigDecimal.ZERO, price,
                base.stockQuantity(), base.photos(), base.texts());
    }

    private static CatalogExportService.Model pricedPicturedFamily(
            CatalogExportService.Model source, CatalogFamilyReader.Family family,
            List<Product> variants, Photo photo) {
        List<Product> pictured = variants.stream().map(product -> product.withPhotos(List.of(photo))).toList();
        CatalogExportService.Request oldRequest = source.request();
        CatalogExportService.Request request = new CatalogExportService.Request(
                oldRequest.productIds(), true, true, 1, oldRequest.title(), null,
                "nl", oldRequest.layout(), oldRequest.brochure());
        return new CatalogExportService.Model(pictured, source.categoriesById(),
                List.of(new CatalogExportService.FamilyGroup(family, pictured,
                        source.families().getFirst().category(), false)), request);
    }

    private static int occurrences(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }

    private static String familyMediaFragment(String html) {
        int from = html.indexOf("<table class=\"family-media family-media--");
        if (from < 0) return "";
        int to = html.indexOf("</table>", from);
        return html.substring(from, to < 0 ? html.length() : to);
    }

    private static String sectionFragment(String html, String marker) {
        int from = html.indexOf(marker);
        if (from < 0) return "";
        int to = html.indexOf("</section>", from);
        return html.substring(from, to < 0 ? html.length() : to);
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

    /** Match complete text across wrapped PDF glyph runs, excluding unrelated footer text. */
    private static final class MatchingTextBoundsStripper extends PDFTextStripper {
        private final StringBuilder text = new StringBuilder();
        private final List<Double> bottoms = new ArrayList<>();
        private final List<Double> lefts = new ArrayList<>();
        private final List<Double> rights = new ArrayList<>();

        private MatchingTextBoundsStripper() throws IOException {}

        @Override
        protected void processTextPosition(TextPosition position) {
            String value = compact(position.getUnicode());
            for (int index = 0; index < value.length(); index++) {
                text.append(value.charAt(index));
                bottoms.add((double) position.getYDirAdj() + position.getHeightDir());
                lefts.add((double) position.getXDirAdj());
                rights.add((double) position.getXDirAdj() + position.getWidthDirAdj());
            }
            super.processTextPosition(position);
        }

        private int occurrencesOf(String value) {
            return occurrences(text.toString(), compact(value));
        }

        private double bottomOf(String value) {
            String needle = compact(value);
            double bottom = -1;
            int from = 0;
            while ((from = text.indexOf(needle, from)) >= 0) {
                for (int index = from; index < from + needle.length(); index++) {
                    bottom = Math.max(bottom, bottoms.get(index));
                }
                from += needle.length();
            }
            return bottom;
        }

        private double widthOf(String value) {
            String needle = compact(value);
            int from = text.indexOf(needle);
            if (from < 0) return Double.POSITIVE_INFINITY;
            double left = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            for (int index = from; index < from + needle.length(); index++) {
                left = Math.min(left, lefts.get(index));
                right = Math.max(right, rights.get(index));
            }
            return right - left;
        }

        private static String compact(String value) {
            return value.replaceAll("[\\s\\p{Z}]+", "");
        }
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
            case EL -> "Διατηρημένο τριαντάφυλλο";
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
            case EL -> "Σταντ πάγκου";
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
            case EL -> "Κόκκινο";
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
            case EL -> "Έτοιμο για δώρο";
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
            case EL -> "χωρίς καθημερινό πότισμα";
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
