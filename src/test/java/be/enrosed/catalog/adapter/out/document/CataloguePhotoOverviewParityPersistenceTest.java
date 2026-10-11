package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceReason;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PlaceState;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.FamilyPhotoCompatibilityService;
import be.enrosed.catalog.application.ProductPhotoOverviewService;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The photo overview and the catalogue export read the same rows through two adapters. For
 * persisted series this renders the real export the overview describes (brochure, photos on,
 * one photo per series, every active colour in variant order; then the compact layout) and
 * requires every printed picture to be the photo the overview names for that place.
 */
@QuarkusTest
class CataloguePhotoOverviewParityPersistenceTest {

    /** Every rendition the two templates can print: aspect width, aspect height, longest edge. */
    private static final int[][] RENDITIONS = {
            {4, 3, 1_200}, {4, 3, 1_000}, {16, 9, 2_400}, {80, 27, 2_400}, {5, 6, 1_600},
            {7, 6, 1_900}, {7, 6, 1_300}, {16, 9, 1_500}, {17, 9, 900}, {40, 26, 720},
            {184, 178, 2_400}, {184, 89, 2_200}, {184, 107, 2_200}, {92, 71, 1_400}, {92, 89, 1_600}};
    private static final List<Color> COLOURS = List.of(
            new Color(0xB01030), new Color(0x1050B0), new Color(0x10A040), new Color(0xE0A010),
            new Color(0x7020A0), new Color(0x10A0A0), new Color(0xE06010), new Color(0x404040));
    private static final Pattern IMAGE = Pattern.compile("<img [^>]*?src=\"([^\"]*)\"");
    private static final String CATALOGUE = "[\"CATALOGUE\"]";
    private static final String INTERNAL = "[]";

    @Inject EntityManager entityManager;
    @Inject ProductPhotoOverviewService overviews;
    @Inject CatalogExportService exports;
    @Inject PdfCatalogRenderer renderer;
    @Inject PhotoStorage photoStorage;
    @Inject PdfImageEncoder imageEncoder;
    @Inject FamilyPhotoCompatibilityService familyPhotoCompatibility;

    private final Map<String, Stored> stored = new LinkedHashMap<>();
    /** Printed data URI to the storage key of the photo it was encoded from. */
    private final Map<String, String> printed = new HashMap<>();
    private CategoryEntity category;

    private record Stored(String key, byte[] bytes) {}

    /** What one export printed, as storage keys; null where a place stayed empty. */
    private record Printed(String detail, String thumbnail, Map<String, String> strip, boolean hasStrip) {}

    /* (a) */
    @Test
    @TestTransaction
    void withoutAnyChoiceTheSeriesOrderDecides() {
        ProductFamilyEntity series = series("parity-order");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        ProductFamilyPhotoEntity all = seriesPhoto(series, "all", 0, null, CATALOGUE);
        ProductFamilyPhotoEntity redPhoto = seriesPhoto(series, "red", 1, red, CATALOGUE);
        ProductFamilyPhotoEntity pinkPhoto = seriesPhoto(series, "pink", 2, pink, CATALOGUE);
        sync(series);

        Map<Long, ProductPhotoOverviewDto> overview = assertParity(red, pink);

        assertEquals("F" + all.id, overview.get(red.id).places().catalogueDetail().shownKey());
        assertEquals("F" + redPhoto.id, overview.get(red.id).places().catalogueColour().shownKey());
        assertEquals("F" + pinkPhoto.id, overview.get(pink.id).places().catalogueCompactRow().shownKey(),
                "a colour's rows list its own series photo before the one for all colours");
    }

    /* (b) */
    @Test
    @TestTransaction
    void aColoursOwnPhotoIsPrintedAheadOfItsPublishedSeriesPhoto() {
        ProductFamilyEntity series = series("parity-own");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        seriesPhoto(series, "red", 0, red, CATALOGUE);
        seriesPhoto(series, "pink", 1, pink, CATALOGUE);
        ProductPhotoEntity own = ownPhoto(red, "red-own", 0);
        sync(series);

        Map<Long, ProductPhotoOverviewDto> overview = assertParity(red, pink);

        assertEquals("P" + own.id, overview.get(red.id).places().catalogueColour().shownKey());
        assertEquals(PlaceReason.OWN_PHOTO_FIRST, overview.get(red.id).places().catalogueColour().reason());
    }

    /* (c) and (f) */
    @Test
    @TestTransaction
    void anAllColoursPhotoNeverIllustratesAColourEvenWhenChosen() {
        ProductFamilyEntity series = series("parity-all-colours");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        seriesPhoto(series, "pink", 0, pink, CATALOGUE);
        ProductFamilyPhotoEntity all = seriesPhoto(series, "all", 1, null, CATALOGUE);
        sync(series);
        assertParity(red, pink);

        lead(red, all);
        Map<Long, ProductPhotoOverviewDto> overview = assertParity(red, pink);

        assertEquals(PlaceState.CHOSEN_NOT_SHOWN, overview.get(red.id).places().catalogueColour().state());
        assertEquals("F" + all.id, overview.get(pink.id).places().catalogueDetail().shownKey());
    }

    /* (d), (e) and (g) */
    @Test
    @TestTransaction
    void colourChoicesOpenTheSeriesInVariantOrderAndOnlyWhilePublishedForTheCatalogue() {
        ProductFamilyEntity series = series("parity-leads");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        seriesPhoto(series, "all", 0, null, CATALOGUE);
        ProductFamilyPhotoEntity redPhoto = seriesPhoto(series, "red", 1, red, CATALOGUE);
        ProductFamilyPhotoEntity pinkPhoto = seriesPhoto(series, "pink", 2, pink, CATALOGUE);
        ProductFamilyPhotoEntity pinkInternal = seriesPhoto(series, "pink-internal", 3, pink, INTERNAL);
        sync(series);

        lead(pink, pinkPhoto);
        Map<Long, ProductPhotoOverviewDto> one = assertParity(red, pink);
        assertEquals(PlaceReason.OTHER_COLOUR_LEAD_OVERRIDES, one.get(red.id).places().catalogueDetail().reason());

        lead(red, redPhoto);
        Map<Long, ProductPhotoOverviewDto> both = assertParity(red, pink);
        assertEquals("F" + redPhoto.id, both.get(pink.id).places().catalogueDetail().shownKey());

        managed(red).variantPosition = 1;
        managed(pink).variantPosition = 0;
        Map<Long, ProductPhotoOverviewDto> swapped = assertParity(pink, red);
        assertEquals("F" + pinkPhoto.id, swapped.get(red.id).places().catalogueDetail().shownKey());

        unlead(pink, pinkPhoto);
        unlead(red, redPhoto);
        lead(pink, pinkInternal);
        Map<Long, ProductPhotoOverviewDto> internal = assertParity(pink, red);
        assertEquals(PlaceReason.NOT_PUBLISHED_FOR_CATALOGUE,
                internal.get(pink.id).places().catalogueColour().reason());
        assertEquals("F" + pinkPhoto.id, internal.get(pink.id).places().catalogueColour().shownKey());
    }

    /* (h) and (i) */
    @Test
    @TestTransaction
    void seriesChoicesAreFollowedWhileAvailable() {
        ProductFamilyEntity series = series("parity-manual");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        ProductEntity white = colour(series, "WHITE", 2);
        seriesPhoto(series, "all", 0, null, CATALOGUE);
        ProductFamilyPhotoEntity redPhoto = seriesPhoto(series, "red", 1, red, CATALOGUE);
        ProductFamilyPhotoEntity whitePhoto = seriesPhoto(series, "white", 2, white, CATALOGUE);
        ProductPhotoEntity pinkOwn = ownPhoto(pink, "pink-own", 0);
        sync(series);
        series.catalogueDetailPhotoId = -pinkOwn.id;
        series.catalogueOverviewPhotoId = redPhoto.id;

        Map<Long, ProductPhotoOverviewDto> chosen = assertParity(red, pink, white);
        assertEquals(PlaceState.CHOSEN, chosen.get(red.id).places().catalogueDetail().state());
        assertEquals("P" + pinkOwn.id, chosen.get(red.id).places().catalogueDetail().shownKey());
        assertEquals(PlaceState.CHOSEN, chosen.get(white.id).places().catalogueOverview().state());
        assertEquals("F" + redPhoto.id, chosen.get(white.id).places().catalogueOverview().shownKey());
        assertEquals(List.of("P" + pinkOwn.id), chosen.get(red.id).otherPhotos().stream()
                .map(ProductPhotoOverviewDto.OtherPhotoDto::key).toList());

        /* The choice belongs to a colour that left the catalogue. */
        managed(series).catalogueDetailPhotoId = whitePhoto.id;
        managed(series).catalogueOverviewPhotoId = null;
        managed(white).active = false;
        Map<Long, ProductPhotoOverviewDto> gone = assertParity(red, pink);
        assertEquals(PlaceState.CHOSEN_NOT_SHOWN, gone.get(red.id).places().catalogueDetail().state());
        assertEquals(PlaceReason.CHOICE_NOT_AVAILABLE, gone.get(red.id).places().catalogueDetail().reason());
    }

    /* (j), an inactive colour with photos and a demo colour */
    @Test
    @TestTransaction
    void onlyActiveColoursThatAreNotDemoArePartOfTheAssumedCatalogue() {
        ProductFamilyEntity series = series("parity-single");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        ProductEntity demo = colour(series, "DEMO", 2);
        pink.active = false;
        demo.demo = true;
        seriesPhoto(series, "pink", 0, pink, CATALOGUE);
        seriesPhoto(series, "demo", 1, demo, CATALOGUE);
        ProductFamilyPhotoEntity redPhoto = seriesPhoto(series, "red", 2, red, CATALOGUE);
        ownPhoto(pink, "pink-own", 0);
        ownPhoto(demo, "demo-own", 0);
        sync(series);
        lead(demo, seriesPhotoOf(series, "demo"));

        Map<Long, ProductPhotoOverviewDto> overview = assertParity(red);

        assertEquals(List.of(red.id), overview.get(red.id).catalogueAssumption().productIds());
        assertEquals(PlaceReason.SINGLE_COLOUR_SERIES, overview.get(red.id).places().catalogueColour().reason());
        assertEquals("F" + redPhoto.id, overview.get(red.id).places().catalogueDetail().shownKey());
    }

    /* (k) */
    @Test
    @TestTransaction
    void aSeriesPhotoAndAnOwnPhotoSharingOneOriginalArePrintedOnce() {
        ProductFamilyEntity series = series("parity-shared");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        seriesPhoto(series, "red", 0, red, CATALOGUE);
        seriesPhoto(series, "pink", 1, pink, CATALOGUE);
        ownPhoto(red, "red", 0);
        sync(series);

        assertParity(red, pink);
    }

    /* (l) */
    @Test
    @TestTransaction
    void aProductWithoutASeriesPrintsItsOwnPhoto() {
        ProductEntity loose = colour(null, "LOOSE", 0);
        ProductPhotoEntity own = ownPhoto(loose, "loose-own", 0);
        ownPhoto(loose, "loose-second", 1);

        Map<Long, ProductPhotoOverviewDto> overview = assertParity(loose);

        assertEquals("P" + own.id, overview.get(loose.id).places().catalogueDetail().shownKey());
        assertEquals("P" + own.id, overview.get(loose.id).places().catalogueCompactRow().shownKey());
    }

    /* (m) */
    @Test
    @TestTransaction
    void aPhotoWithoutBytesIsSkippedByBoth() {
        ProductFamilyEntity series = series("parity-unreadable");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity pink = colour(series, "PINK", 1);
        seriesPhoto(series, "pink", 0, pink, CATALOGUE);
        ProductPhotoEntity lost = new ProductPhotoEntity();
        lost.product = red;
        lost.storageKey = "sha256-parity-lost.png";
        lost.originalFilename = "lost.png";
        lost.contentType = "image/png";
        lost.position = 0;
        lost.leadRoles = "CATALOGUE";
        entityManager.persist(lost);
        red.photos.add(lost);
        ProductPhotoEntity second = ownPhoto(red, "red-own", 1);
        sync(series);

        Map<Long, ProductPhotoOverviewDto> overview = assertParity(red, pink);

        ProductPhotoOverviewDto.PlaceDto strip = overview.get(red.id).places().catalogueColour();
        assertEquals("P" + lost.id, strip.chosenKey());
        assertEquals("P" + second.id, strip.shownKey());
        assertEquals(PlaceReason.PHOTO_UNREADABLE, strip.reason());
    }

    /* (n) no colour left: the export refuses, so the overview names no brochure photo */
    @Test
    @TestTransaction
    void aSeriesWithoutAnActiveColourPrintsNoBrochureAndIsNotOnTheWebsite() {
        ProductFamilyEntity series = series("parity-no-colour");
        ProductEntity red = colour(series, "RED", 0);
        ProductEntity demo = colour(series, "DEMO", 1);
        red.active = false;
        demo.demo = true;
        ProductFamilyPhotoEntity all = seriesPhoto(series, "all", 0, null, "[\"CATALOGUE\",\"WEBSITE\"]");
        sync(series);
        series.catalogueOverviewPhotoId = all.id;
        entityManager.flush();
        entityManager.clear();

        ProductPhotoOverviewDto overview = overviews.overview(red.id);

        List<Long> assumed = overview.catalogueAssumption().productIds();
        assertEquals(List.of(), assumed);
        assertThrows(BusinessRuleException.class,
                () -> exports.model(request(assumed, CatalogExportService.Layout.BROCHURE)));
        assertNull(overview.places().catalogueDetail().shownKey(), "large photo");
        assertEquals(PlaceReason.COLOUR_NOT_ACTIVE, overview.places().catalogueDetail().reason());
        assertNull(overview.places().catalogueOverview().shownKey(), "range thumbnail");
        assertEquals("F" + all.id, overview.places().catalogueOverview().chosenKey());
        assertEquals(PlaceState.CHOSEN_NOT_SHOWN, overview.places().catalogueOverview().state());
        assertEquals(PlaceReason.COLOUR_NOT_ACTIVE, overview.places().catalogueOverview().reason());
        assertNull(overview.places().catalogueColour().shownKey(), "colour strip");
        assertEquals("F" + all.id, overview.places().websiteOpening().shownKey(),
                "the photo is still published for the website");
        assertEquals(false, overview.seriesLiveOnWebsite(), "the website drops a series without a colour");
    }

    /* ------------------------------------------------------------- parity */

    /**
     * Renders the export the overview assumes and compares it place by place.
     *
     * @param colours the active colours in variant order
     */
    private Map<Long, ProductPhotoOverviewDto> assertParity(ProductEntity... colours) {
        entityManager.flush();
        entityManager.clear();
        List<Long> ids = List.of(colours).stream().map(colour -> colour.id).toList();
        Map<Long, ProductPhotoOverviewDto> overview = new LinkedHashMap<>();
        for (Long id : ids) overview.put(id, overviews.overview(id));
        learnPrintedPictures();

        Printed brochure = brochure(ids);
        for (ProductEntity colour : colours) {
            ProductPhotoOverviewDto own = overview.get(colour.id);
            assertEquals(ids, own.catalogueAssumption().productIds(), colour.sku + " assumed colours");
            assertEquals(brochure.detail(), storageKey(own.places().catalogueDetail().shownKey()),
                    colour.sku + " large photo");
            assertEquals(brochure.thumbnail(), storageKey(own.places().catalogueOverview().shownKey()),
                    colour.sku + " range thumbnail");
            if (brochure.hasStrip()) {
                assertEquals(brochure.strip().get(colour.sku),
                        storageKey(own.places().catalogueColour().shownKey()), colour.sku + " colour strip");
            } else {
                assertNull(own.places().catalogueColour().shownKey(), colour.sku + " has no colour strip");
            }
        }
        Map<String, String> compact = compact(ids);
        for (ProductEntity colour : colours) {
            assertEquals(compact.get(colour.sku),
                    storageKey(overview.get(colour.id).places().catalogueCompactRow().shownKey()),
                    colour.sku + " compact row");
        }
        return overview;
    }

    private Printed brochure(List<Long> ids) {
        String html = renderer.renderHtml(exports.model(request(ids, CatalogExportService.Layout.BROCHURE)));
        String cell = "<td class=\"range-photo\"><div>";
        int at = html.indexOf(cell);
        String thumbnail = null;
        if (at >= 0) {
            String rest = html.substring(at + cell.length());
            if (rest.startsWith("<img ")) thumbnail = pictures(rest.substring(0, rest.indexOf('>') + 1)).getFirst();
        }
        assertEquals(-1, at < 0 ? -1 : html.indexOf(cell, at + 1), "one series, one range row");
        String family = between(html, "<section id=\"family-", "</section>");
        String media = between(family, "<table class=\"family-media family-media--", "</table>");
        List<String> large = media.startsWith("empty") ? List.of() : pictures(media);
        assertEquals(true, large.size() <= 1, "one photo per series");
        String strip = between(family, "<table class=\"variant-photos\">", "</table>");
        Map<String, String> colours = new LinkedHashMap<>();
        if (strip != null) {
            String[] cells = strip.split("<td data-sku=\"");
            for (int index = 1; index < cells.length; index++) {
                List<String> picture = pictures(cells[index]);
                colours.put(cells[index].substring(0, cells[index].indexOf('"')),
                        picture.isEmpty() ? null : picture.getFirst());
            }
        }
        return new Printed(large.isEmpty() ? null : large.getFirst(), thumbnail, colours, strip != null);
    }

    private Map<String, String> compact(List<Long> ids) {
        String html = renderer.renderHtml(exports.model(request(ids, CatalogExportService.Layout.SIMPLE)));
        Map<String, String> rows = new LinkedHashMap<>();
        String[] blocks = html.split("<div class=\"product-block\">");
        for (int index = 1; index < blocks.length; index++) {
            String sku = between(blocks[index], "<div class=\"sku\">SKU · ", "</div>");
            String visual = between(blocks[index], "<td class=\"visual\">", "<td class=\"identity\">");
            List<String> picture = visual == null ? List.of() : pictures(visual);
            rows.put(sku, picture.isEmpty() ? null : picture.getFirst());
        }
        assertEquals(ids.size(), rows.size(), "one compact row per colour");
        return rows;
    }

    /** The export the overview describes: photos on, one per series, the given order kept. */
    private static CatalogExportService.Request request(List<Long> ids, CatalogExportService.Layout layout) {
        return new CatalogExportService.Request(
                ids, false, true, 1, "Parity", null, "en", layout,
                new CatalogExportService.BrochureOptions(true, false, false, false, false, "Parity", "Check"),
                null, Map.of(), true);
    }

    private List<String> pictures(String fragment) {
        List<String> found = new ArrayList<>();
        Matcher matcher = IMAGE.matcher(fragment);
        while (matcher.find()) {
            String key = printed.get(matcher.group(1));
            if (key == null) throw new IllegalStateException("A printed picture is not one of the fixture photos");
            found.add(key);
        }
        return found;
    }

    private void learnPrintedPictures() {
        for (Stored photo : stored.values()) {
            if (printed.containsValue(photo.key())) continue;
            for (int[] rendition : RENDITIONS) {
                String uri = imageEncoder.encodeContainedTrimmed(
                        photo.bytes(), rendition[0], rendition[1], rendition[2], Color.WHITE);
                String previous = printed.putIfAbsent(uri, photo.key());
                if (previous != null && !previous.equals(photo.key())) {
                    throw new IllegalStateException("Two fixture photos encode to the same picture");
                }
            }
        }
    }

    /** The stored original behind an overview key: "F12" a series photo, "P7" an own photo. */
    private String storageKey(String key) {
        if (key == null) return null;
        long id = Long.parseLong(key.substring(1));
        return key.charAt(0) == 'F'
                ? entityManager.find(ProductFamilyPhotoEntity.class, id).largeStorageKey
                : entityManager.find(ProductPhotoEntity.class, id).storageKey;
    }

    private static String between(String text, String opening, String closing) {
        int from = text.indexOf(opening);
        if (from < 0) return null;
        int to = text.indexOf(closing, from + opening.length());
        return text.substring(from + opening.length(), to < 0 ? text.length() : to);
    }

    /* ----------------------------------------------------------- fixtures */

    /* A parity check clears the session so both sides read what is stored; later steps of a
       test change the stored rows through these. */
    private ProductEntity managed(ProductEntity fixture) {
        return entityManager.find(ProductEntity.class, fixture.id);
    }

    private ProductFamilyEntity managed(ProductFamilyEntity fixture) {
        return entityManager.find(ProductFamilyEntity.class, fixture.id);
    }

    private ProductFamilyEntity series(String key) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.publicHandle = key;
        family.active = true;
        family.name = "Parity series";
        family.categoryId = category().id;
        family.categoryKey = category().code;
        family.categoryName = category().name;
        family.websiteStatus = PublicationState.PUBLISHED;
        family.catalogueStatus = PublicationState.PUBLISHED;
        for (Language language : Language.values()) {
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = language;
            text.name = "Series " + language.code();
            text.summary = "Summary " + language.code();
            text.description = "Description " + language.code();
            text.highlightsJson = "[]";
            family.texts.add(text);
        }
        entityManager.persist(family);
        entityManager.flush();
        return family;
    }

    private CategoryEntity category() {
        if (category == null) {
            category = new CategoryEntity();
            category.code = "parity-" + System.nanoTime();
            category.name = "Parity";
            entityManager.persist(category);
            entityManager.flush();
        }
        return category;
    }

    private ProductEntity colour(ProductFamilyEntity family, String colour, int position) {
        ProductEntity product = new ProductEntity();
        product.sku = "PAR-" + (family == null ? "LOOSE" : family.familyKey.toUpperCase()) + "-" + colour;
        product.name = "Variant " + colour;
        product.familyId = family == null ? null : family.id;
        product.familyKey = family == null ? null : family.familyKey;
        product.categoryId = category().id;
        product.canonicalVariantKey = product.sku.toLowerCase();
        product.colour = colour;
        product.colourHex = "#A91F32";
        product.variantPosition = position;
        product.active = true;
        product.piecesPerCarton = 1;
        product.fixedSalesPriceEur = BigDecimal.TEN;
        for (Language language : Language.values()) {
            ProductTextEntity text = new ProductTextEntity();
            text.product = product;
            text.language = language;
            text.name = "Variant " + language.code();
            text.colour = colour + " " + language.code();
            product.texts.add(text);
        }
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    /** A series photo with a real stored original; small and large share it. */
    private ProductFamilyPhotoEntity seriesPhoto(ProductFamilyEntity family, String name, int position,
                                                 ProductEntity colour, String channels) {
        Stored source = stored(name);
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = name;
        photo.originalFilename = name + ".png";
        photo.smallStorageKey = source.key();
        photo.largeStorageKey = source.key();
        photo.smallContentType = "image/png";
        photo.largeContentType = "image/png";
        photo.smallSizeBytes = source.bytes().length;
        photo.largeSizeBytes = source.bytes().length;
        photo.smallWidthPx = 96;
        photo.smallHeightPx = 72;
        photo.largeWidthPx = 96;
        photo.largeHeightPx = 72;
        photo.position = position;
        photo.variantProduct = colour;
        photo.altTextsJson = "[]";
        photo.publishedChannelsJson = channels;
        family.photos.add(photo);
        entityManager.persist(photo);
        return photo;
    }

    private static ProductFamilyPhotoEntity seriesPhotoOf(ProductFamilyEntity family, String name) {
        return family.photos.stream().filter(photo -> name.equals(photo.sourceKey)).findFirst().orElseThrow();
    }

    private ProductPhotoEntity ownPhoto(ProductEntity product, String name, int position) {
        Stored source = stored(name);
        ProductPhotoEntity photo = new ProductPhotoEntity();
        photo.product = product;
        photo.storageKey = source.key();
        photo.originalFilename = name + ".png";
        photo.contentType = "image/png";
        photo.sizeBytes = source.bytes().length;
        photo.widthPx = 96;
        photo.heightPx = 72;
        photo.position = position;
        entityManager.persist(photo);
        product.photos.add(photo);
        return photo;
    }

    /** Gives every colour its rows of the series photos, after its own photos. */
    private void sync(ProductFamilyEntity family) {
        entityManager.flush();
        familyPhotoCompatibility.sync(family);
        entityManager.flush();
    }

    private void lead(ProductEntity colour, ProductFamilyPhotoEntity seriesPhoto) {
        row(colour, seriesPhoto).leadRoles = "CATALOGUE";
    }

    private void unlead(ProductEntity colour, ProductFamilyPhotoEntity seriesPhoto) {
        row(colour, seriesPhoto).leadRoles = null;
    }

    private ProductPhotoEntity row(ProductEntity colour, ProductFamilyPhotoEntity seriesPhoto) {
        return managed(colour).photos.stream()
                .filter(photo -> Objects.equals(photo.familyPhotoId, seriesPhoto.id))
                .min(Comparator.comparing(photo -> photo.id)).orElseThrow();
    }

    /** One solid colour per name, so every printed picture is recognised by its data URI. */
    private Stored stored(String name) {
        Stored known = stored.get(name);
        if (known != null) return known;
        try {
            BufferedImage image = new BufferedImage(96, 72, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(COLOURS.get(stored.size()));
            graphics.fillRect(0, 0, 96, 72);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            byte[] bytes = out.toByteArray();
            String key = "sha256-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)) + ".png";
            photoStorage.storeKnown(key, name + ".png", "image/png", bytes);
            Stored created = new Stored(key, bytes);
            stored.put(name, created);
            return created;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
