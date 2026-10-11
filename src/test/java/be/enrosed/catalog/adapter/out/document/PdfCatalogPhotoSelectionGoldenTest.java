package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.catalog.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.enrosed.catalog.application.CatalogExportServiceTest.product;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins which photos the catalogue prints, where and in which order, through the
 * renderer's real entry point. Every fixture photo is a solid colour of its own, so
 * each printed picture is recognised by its encoded data URI and written here as
 * {@code name@aspect}. The expectations are the rules written out by hand: this test
 * must stay green, unedited, whenever the selection code is moved or shared.
 */
@QuarkusTest
class PdfCatalogPhotoSelectionGoldenTest {

    private static final Category CATEGORY = new Category(
            1L, "counter", "Counter Displays", "Retail-ready products", 0);
    private static final long SERIES = 100L;
    private static final long RED = 1L;
    private static final long PINK = 2L;
    private static final long WHITE = 3L;
    /** Every rendition the two templates can print: aspect width, aspect height, longest edge. */
    private static final int[][] RENDITIONS = {
            {4, 3, 1_200}, {4, 3, 1_000}, {16, 9, 2_400}, {80, 27, 2_400}, {5, 6, 1_600},
            {7, 6, 1_900}, {7, 6, 1_300}, {16, 9, 1_500}, {17, 9, 900}, {40, 26, 720},
            {184, 178, 2_400}, {184, 89, 2_200}, {184, 107, 2_200}, {92, 71, 1_400}, {92, 89, 1_600}};
    private static final List<Color> COLOURS = List.of(
            new Color(0xB01030), new Color(0x1050B0), new Color(0x10A040), new Color(0xE0A010),
            new Color(0x7020A0), new Color(0x10A0A0), new Color(0xE06010), new Color(0x404040),
            new Color(0xE040A0), new Color(0x80B010), new Color(0x603010), new Color(0x2080E0),
            new Color(0x909090), new Color(0x004040), new Color(0xF08080), new Color(0x202080));
    private static final Pattern IMAGE = Pattern.compile("<img [^>]*?src=\"([^\"]*)\"");

    @Inject PdfCatalogRenderer renderer;
    @Inject PhotoStorage photoStorage;
    @Inject PdfImageEncoder imageEncoder;

    private final Map<String, StoredPhoto> stored = new LinkedHashMap<>();
    private final Map<String, String> labels = new HashMap<>();

    private record StoredPhoto(String key, byte[] bytes, int width, int height) {}

    /* (a) */
    @Test
    void withoutAnyChoiceTheSeriesOrderDecidesAndEachColourShowsItsOwnSeriesPhoto() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink-big", 2, PINK)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(211, "all", 0, 11), inherited(213, "pink-big", 1, 13));

        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink-big@20x13]""",
                brochure(1, group(series, red, pink)));
        assertEquals("""
                range [all@4x3]
                family-01 media two [all@5x6, red@5x6]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink-big@20x13]""",
                brochure(2, group(series, red, pink)));
        // Three photos have one larger tile: the strongest photo moves to it and to the range table.
        assertEquals("""
                range [pink-big@4x3]
                family-01 media three [pink-big@7x6, all@7x6, red@7x6]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink-big@20x13]""",
                brochure(3, group(series, red, pink)));
        assertEquals("""
                GOLD-R [all@4x3]
                GOLD-P [all@4x3]""",
                compact(1, group(series, red, pink)));
        assertEquals("""
                GOLD-R [all@4x3, red@4x3]
                GOLD-P [all@4x3, pink-big@4x3]""",
                compact(2, group(series, red, pink)));
    }

    /* (b) */
    @Test
    void aColoursOwnPhotoWinsInTheStripWhileTheCompactRowFollowsTheProductsPhotoOrder() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(12, "red-series", 0, RED), gallery(14, "pink-late", 3, PINK),
                gallery(13, "pink-early", 1, PINK)));
        Product red = colour(RED, "GOLD-R", 0,
                own(501, "red-own-late", 5), inherited(112, "red-series", 0, 12),
                own(502, "red-own-early", 2));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(214, "pink-late", 0, 14), inherited(213, "pink-early", 1, 13));

        // The strip sorts the series photos by position; the large photo takes them as delivered.
        assertEquals("""
                range [red-series@4x3]
                family-01 media one [red-series@16x9]
                family-01 strip [GOLD-R=red-own-early@20x13, GOLD-P=pink-early@20x13]""",
                brochure(1, group(series, red, pink)));
        assertEquals("""
                range [red-series@4x3]
                family-01 media four-plus [red-series@16x9, pink-late@16x9, pink-early@16x9, red-own-early@16x9]
                family-01 strip [GOLD-R=red-own-early@20x13, GOLD-P=pink-early@20x13]""",
                brochure(4, group(series, red, pink)));
        assertEquals("""
                GOLD-R [red-series@4x3]
                GOLD-P [pink-late@4x3]""",
                compact(1, group(series, red, pink)));
        assertEquals("""
                GOLD-R [red-series@4x3, red-own-early@4x3, red-own-late@4x3]
                GOLD-P [pink-late@4x3, pink-early@4x3]""",
                compact(3, group(series, red, pink)));
    }

    /* (c) */
    @Test
    void aColourWithOnlyAnAllColoursPhotoGetsNoStripPicture() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12));
        Product pink = colour(PINK, "GOLD-P", 1, inherited(211, "all", 0, 11));

        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=-]""",
                brochure(1, group(series, red, pink)));
        assertEquals("""
                GOLD-R [all@4x3]
                GOLD-P [all@4x3]""",
                compact(1, group(series, red, pink)));

        CatalogFamilyReader.Family shared = series(List.of(gallery(11, "all", 0, null)));
        Product plainRed = colour(RED, "GOLD-R", 0, inherited(111, "all", 0, 11));
        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip none""",
                brochure(1, group(shared, plainRed, pink)));
    }

    /* (d) */
    @Test
    void aCatalogueChoiceOnTheColoursSeriesPhotoBecomesTheLargePhotoAndTheStripPicture() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED),
                gallery(13, "pink-first", 2, PINK), gallery(15, "pink-chosen", 3, PINK)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(211, "all", 0, 11), inherited(213, "pink-first", 1, 13),
                lead(inherited(215, "pink-chosen", 2, 15)));

        assertEquals("""
                range [pink-chosen@4x3]
                family-01 media one [pink-chosen@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(1, group(series, red, pink)));
        // A choice is the whole selection: a larger budget does not bring the other photos back.
        assertEquals("""
                range [pink-chosen@4x3]
                family-01 media one [pink-chosen@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(3, group(series, red, pink)));
        assertEquals("""
                GOLD-R [all@4x3, red@4x3]
                GOLD-P [pink-chosen@4x3]""",
                compact(3, group(series, red, pink)));
    }

    /* (e) */
    @Test
    void aChoiceOnASeriesPhotoOutsideTheCatalogueGalleryIsIgnored() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12));
        Product pink = colour(PINK, "GOLD-P", 1,
                lead(inherited(216, "pink-internal", 0, 16)), inherited(211, "all", 1, 11));

        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=-]""",
                brochure(1, group(series, red, pink)));
        assertEquals("""
                GOLD-R [all@4x3, red@4x3]
                GOLD-P [all@4x3]""",
                compact(2, group(series, red, pink)));
    }

    /* (e) continued: the projection row still names a blob the series photo no longer has. */
    @Test
    void aChoiceWhoseBlobDiffersFromTheSeriesPhotoOpensTheSeriesButNotTheStrip() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), lead(inherited(112, "red-stale", 1, 12)));
        Product pink = colour(PINK, "GOLD-P", 1, inherited(211, "all", 0, 11));

        assertEquals("""
                range [red-stale@4x3]
                family-01 media one [red-stale@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=-]""",
                brochure(2, group(series, red, pink)));
        assertEquals("""
                GOLD-R [red-stale@4x3]
                GOLD-P [all@4x3]""",
                compact(2, group(series, red, pink)));
    }

    /* (f) */
    @Test
    void aChoiceOnAnAllColoursPhotoOpensTheSeriesButNeverIllustratesAColour() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(12, "red", 0, RED), gallery(11, "all", 1, null)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(112, "red", 0, 12), inherited(111, "all", 1, 11));
        Product pink = colour(PINK, "GOLD-P", 1, lead(inherited(211, "all", 0, 11)));

        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=-]""",
                brochure(1, group(series, red, pink)));

        CatalogFamilyReader.Family withPink = series(List.of(
                gallery(12, "red", 0, RED), gallery(11, "all", 1, null), gallery(13, "pink", 2, PINK)));
        Product pinkWithOwnSeriesPhoto = colour(PINK, "GOLD-P", 1,
                lead(inherited(211, "all", 0, 11)), inherited(213, "pink", 1, 13));
        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(1, group(withPink, red, pinkWithOwnSeriesPhoto)));
        assertEquals("""
                GOLD-R [red@4x3, all@4x3]
                GOLD-P [all@4x3]""",
                compact(2, group(withPink, red, pinkWithOwnSeriesPhoto)));
    }

    /* (g) */
    @Test
    void choicesOfTwoColoursFillTheBudgetInTheOrderTheColoursWereRequested() {
        CatalogFamilyReader.Family series = series(List.of(gallery(11, "all", 0, null)));
        Product red = colour(RED, "GOLD-R", 0,
                lead(own(501, "red-chosen", 0)), inherited(111, "all", 1, 11));
        Product pink = colour(PINK, "GOLD-P", 1,
                lead(own(601, "pink-chosen", 0)), inherited(211, "all", 1, 11));

        assertEquals("""
                range [red-chosen@4x3]
                family-01 media one [red-chosen@16x9]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(1, group(series, red, pink)));
        // The requested order decides the large photo; the strip is sorted unless the order is kept.
        assertEquals("""
                range [pink-chosen@4x3]
                family-01 media one [pink-chosen@16x9]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(1, group(series, pink, red)));
        assertEquals("""
                range [pink-chosen@4x3]
                family-01 media one [pink-chosen@16x9]
                family-01 strip [GOLD-P=pink-chosen@20x13, GOLD-R=red-chosen@20x13]""",
                brochure(1, false, true, group(series, pink, red)));
        assertEquals("""
                range [red-chosen@4x3]
                family-01 media two [red-chosen@5x6, pink-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(2, group(series, red, pink)));
        assertEquals("""
                range [pink-chosen@4x3]
                family-01 media two [pink-chosen@5x6, red-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(2, group(series, pink, red)));
        assertEquals("""
                range [red-chosen@4x3]
                family-01 media two [red-chosen@5x6, pink-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-chosen@20x13]""",
                brochure(3, group(series, red, pink)));
    }

    /* (h) */
    @Test
    void theSeriesChoicesForTheLargePhotoAndTheRangeThumbnailComeFirst() {
        List<CatalogFamilyReader.GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink", 2, PINK));
        // The large photo is another colour's own photo, the thumbnail a series photo.
        CatalogFamilyReader.GalleryPhoto detail = gallery(-601, "pink-own", -1, PINK);
        CatalogFamilyReader.GalleryPhoto overview = gallery(12, "red", -1, RED);
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12),
                lead(own(501, "red-chosen", 2)));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(211, "all", 0, 11), inherited(213, "pink", 1, 13),
                lead(own(601, "pink-own", 2)));

        assertEquals("""
                range [red@4x3]
                family-01 media one [pink-own@16x9]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-own@20x13]""",
                brochure(1, group(series(gallery, overview, detail, "STANDARD"), red, pink)));
        assertEquals("""
                range [red@4x3]
                family-01 media two [pink-own@5x6, red-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-own@20x13]""",
                brochure(2, group(series(gallery, overview, detail, "STANDARD"), red, pink)));
        // The pink colour's own choice is the same blob as the series choice: printed once.
        assertEquals("""
                range [red@4x3]
                family-01 media two [pink-own@5x6, red-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-own@20x13]""",
                brochure(3, group(series(gallery, overview, detail, "STANDARD"), red, pink)));
        assertEquals("""
                range [red@4x3]
                family-01 media one [pink-own@80x27]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-own@20x13]""",
                brochure(1, group(series(gallery, overview, detail, "LARGE"), red, pink)));
        assertEquals("""
                range [red@4x3]
                family-01 media two [pink-own@5x6, red-chosen@5x6]
                family-01 strip [GOLD-R=red-chosen@20x13, GOLD-P=pink-own@20x13]""",
                brochure(2, group(series(gallery, overview, detail, "LARGE"), red, pink)));
    }

    /* (h) continued */
    @Test
    void aThumbnailChoiceAloneLeavesTheLargePhotoAutomaticAndAnUnreadableChoiceIsSkipped() {
        List<CatalogFamilyReader.GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink", 2, PINK));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(111, "all", 0, 11), inherited(112, "red", 1, 12));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(211, "all", 0, 11), inherited(213, "pink", 1, 13));

        assertEquals("""
                range [pink@4x3]
                family-01 media two [all@5x6, red@5x6]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(2, group(series(gallery, gallery(13, "pink", -1, PINK), null, "LARGE"), red, pink)));
        // A series choice alone stops the automatic fill, also with a larger budget.
        assertEquals("""
                range [red@4x3]
                family-01 media one [red@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(3, group(series(gallery, null, gallery(12, "red", -1, RED), "STANDARD"), red, pink)));
        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(1, group(series(gallery, gallery(18, "missing-thumbnail", -1, null),
                        gallery(19, "missing-large", -1, null), "STANDARD"), red, pink)));
    }

    /* (i) */
    @Test
    void photosAndChoicesOfAColourThatIsNotSelectedStayOutOfTheBrochure() {
        List<CatalogFamilyReader.GalleryPhoto> gallery = List.of(
                gallery(14, "white", 0, WHITE), gallery(11, "all", 1, null),
                gallery(12, "red", 2, RED), gallery(13, "pink", 3, PINK));
        CatalogFamilyReader.GalleryPhoto whiteChoice = gallery(14, "white", -1, WHITE);
        CatalogFamilyReader.Family series = series(gallery, whiteChoice, whiteChoice, "STANDARD");
        Product red = colour(RED, "GOLD-R", 0,
                lead(inherited(114, "white", 0, 14)), inherited(111, "all", 1, 11),
                inherited(112, "red", 2, 12));
        Product pink = colour(PINK, "GOLD-P", 1,
                inherited(211, "all", 0, 11), inherited(213, "pink", 1, 13));
        Product white = colour(WHITE, "GOLD-W", 2, inherited(314, "white", 0, 14));

        assertEquals("""
                range [all@4x3]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(1, group(series, red, pink)));
        assertEquals("""
                range [all@4x3]
                family-01 media three [all@7x6, red@7x6, pink@7x6]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13]""",
                brochure(4, group(series, red, pink)));
        // The compact row only asks whether the series photo is a catalogue photo at all.
        assertEquals("""
                GOLD-R [white@4x3]
                GOLD-P [all@4x3]""",
                compact(1, group(series, red, pink)));
        assertEquals("""
                range [white@4x3]
                family-01 media one [white@16x9]
                family-01 strip [GOLD-R=red@20x13, GOLD-P=pink@20x13, GOLD-W=white@20x13]""",
                brochure(4, group(series, red, pink, white)));
    }

    /* (j) */
    @Test
    void oneSelectedColourPrintsNoStripAndSkipsTheOtherColoursPhotos() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(13, "pink", 0, PINK), gallery(12, "red", 1, RED), gallery(11, "all", 2, null)));
        Product red = colour(RED, "GOLD-R", 0,
                inherited(112, "red", 0, 12), inherited(111, "all", 1, 11));

        assertEquals("""
                range [red@4x3]
                family-01 media one [red@16x9]
                family-01 strip none""",
                brochure(1, group(series, red)));
        assertEquals("""
                range [red@4x3]
                family-01 media two [red@5x6, all@5x6]
                family-01 strip none""",
                brochure(4, group(series, red)));
    }

    /* (k) */
    @Test
    void aSeriesPhotoAndAnOwnPhotoSharingOneBlobArePrintedOnce() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(11, "seed", 0, RED), gallery(13, "pink", 1, PINK)));
        Product red = colour(RED, "GOLD-R", 0,
                own(501, "seed", 0), inherited(111, "seed", 1, 11));
        Product pink = colour(PINK, "GOLD-P", 1, inherited(213, "pink", 0, 13));

        assertEquals("""
                range [seed@4x3]
                family-01 media two [seed@5x6, pink@5x6]
                family-01 strip [GOLD-R=seed@20x13, GOLD-P=pink@20x13]""",
                brochure(3, group(series, red, pink)));
        assertEquals("""
                GOLD-R [seed@4x3]
                GOLD-P [pink@4x3]""",
                compact(3, group(series, red, pink)));

        Product redChosen = colour(RED, "GOLD-R", 0,
                lead(own(501, "seed", 0)), lead(inherited(111, "seed", 1, 11)));
        assertEquals("""
                range [seed@4x3]
                family-01 media one [seed@16x9]
                family-01 strip [GOLD-R=seed@20x13, GOLD-P=pink@20x13]""",
                brochure(3, group(series, redChosen, pink)));
        assertEquals("""
                GOLD-R [seed@4x3]
                GOLD-P [pink@4x3]""",
                compact(3, group(series, redChosen, pink)));
    }

    /* (l) */
    @Test
    void aProductWithoutASeriesPrintsItsOwnPhotos() {
        Product loose = colour(9L, "GOLD-X", null, 0, own(901, "loose-first", 0), own(902, "loose-second", 1));
        Product chosen = colour(9L, "GOLD-X", null, 0,
                own(901, "loose-first", 0), lead(own(902, "loose-second", 1)));
        // The series could not be read: its photos are not trusted, own photos still print.
        Product orphan = colour(8L, "GOLD-Y", 55L, 0,
                inherited(951, "orphan-series", 0, 77), own(952, "orphan-own", 1));

        assertEquals("""
                range [loose-first@4x3]
                family-01 media one [loose-first@16x9]
                family-01 strip none""",
                brochure(1, group(null, loose)));
        assertEquals("""
                range [loose-first@4x3]
                family-01 media two [loose-first@5x6, loose-second@5x6]
                family-01 strip none""",
                brochure(2, group(null, loose)));
        assertEquals("""
                range [loose-second@4x3, orphan-own@4x3]
                family-01 media one [loose-second@16x9]
                family-01 strip none
                family-02 media one [orphan-own@16x9]
                family-02 strip none""",
                brochure(2, group(null, chosen), group(null, orphan)));
        assertEquals("""
                GOLD-X [loose-first@4x3, loose-second@4x3]
                GOLD-Y [orphan-own@4x3]""",
                compact(2, group(null, loose), group(null, orphan)));
        assertEquals("GOLD-X [loose-second@4x3]", compact(2, group(null, chosen)));
    }

    /* (m) */
    @Test
    void aLargerBudgetIsFilledWithOwnPhotosAndUnreadablePhotosAreSkipped() {
        CatalogFamilyReader.Family series = series(List.of(
                gallery(17, "missing-series", 0, null), gallery(11, "all", 1, null)));
        Product red = colour(RED, "GOLD-R", 0,
                own(501, "first", 0), own(503, "missing-own", 1), own(502, "second-big", 2));
        Product pink = colour(PINK, "GOLD-P", 1, own(601, "third", 0), own(602, "fourth", 1));

        assertEquals("""
                range [all@4x3]
                chapter one [all@92x89]
                family-01 media one [all@16x9]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(1, true, false, group(series, red, pink)));
        assertEquals("""
                range [all@4x3]
                family-01 media two [all@5x6, first@5x6]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(2, group(series, red, pink)));
        assertEquals("""
                range [second-big@4x3]
                family-01 media three [second-big@7x6, all@7x6, first@7x6]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(3, group(series, red, pink)));
        // The chapter page is built from the photos the family sheet selected.
        assertEquals("""
                range [all@4x3]
                chapter four-plus [second-big@92x89, all@92x89, first@92x89, third@92x89]
                family-01 media four-plus [all@16x9, first@16x9, second-big@16x9, third@16x9]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(4, true, false, group(series, red, pink)));
        assertEquals("""
                range [all@4x3]
                family-01 media four-plus [all@16x9, first@16x9, second-big@16x9, third@16x9]
                family-01 extras [fourth@17x9]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(6, group(series, red, pink)));
        assertEquals("""
                GOLD-R [first@4x3, second-big@4x3]
                GOLD-P [third@4x3, fourth@4x3]""",
                compact(2, group(series, red, pink)));
    }

    /* (m) continued */
    @Test
    void anUnreadableChoiceDoesNotStopTheAutomaticSelection() {
        CatalogFamilyReader.Family series = series(List.of(gallery(11, "all", 0, null)));
        Product red = colour(RED, "GOLD-R", 0,
                lead(own(503, "missing-chosen", 0)), own(501, "first", 1));
        Product pink = colour(PINK, "GOLD-P", 1, own(601, "third", 0));

        assertEquals("""
                range [all@4x3]
                family-01 media two [all@5x6, first@5x6]
                family-01 strip [GOLD-R=first@20x13, GOLD-P=third@20x13]""",
                brochure(2, group(series, red, pink)));
        assertEquals("""
                GOLD-R [first@4x3]
                GOLD-P [third@4x3]""",
                compact(1, group(series, red, pink)));
    }

    /* (n) */
    @Test
    void withPhotosOffNothingIsPrintedAnywhere() {
        List<CatalogFamilyReader.GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink", 2, PINK));
        CatalogFamilyReader.Family series = series(
                gallery, gallery(12, "red", -1, RED), gallery(13, "pink", -1, PINK), "LARGE");
        Product red = colour(RED, "GOLD-R", 0,
                inherited(112, "red", 0, 12), lead(own(501, "red-chosen", 1)));
        Product pink = colour(PINK, "GOLD-P", 1, inherited(213, "pink", 0, 13));

        assertEquals("""
                range [-]
                chapter none
                family-01 media empty
                family-01 strip none""",
                brochure(0, true, false, group(series, red, pink)));
        assertEquals("""
                GOLD-R no photo column
                GOLD-P no photo column""",
                compact(0, group(series, red, pink)));
    }

    @Test
    void theCompactRowKeepsItsChoicesWithinTheBudgetAndPrintsABlobOnce() {
        CatalogFamilyReader.Family series = series(List.of(gallery(11, "all", 0, null)));
        CatalogFamilyReader.Family other = series(200L, List.of(gallery(21, "other-series", 0, null)),
                null, null, "STANDARD");
        Product choices = colour(RED, "GOLD-R", 0,
                own(501, "first", 0), lead(own(502, "chosen-one", 1)), lead(own(503, "chosen-two", 2)),
                lead(inherited(116, "internal", 3, 16)));
        Product duplicates = colour(PINK, "GOLD-P", 1,
                own(601, "third", 0), own(604, "third", 1), own(602, "fourth", 2));
        // Any selected series' catalogue photo counts, also for a colour of another series.
        Product borrowed = colour(WHITE, "GOLD-W", 2, inherited(321, "other-series", 0, 21));
        Product otherSeries = colour(4L, "GOLD-Q", 200L, 0, inherited(421, "other-series", 0, 21));

        assertEquals("""
                GOLD-R [chosen-one@4x3]
                GOLD-P [third@4x3]
                GOLD-W [other-series@4x3]
                GOLD-Q [other-series@4x3]""",
                compact(1, group(series, choices, duplicates, borrowed), group(other, otherSeries)));
        assertEquals("""
                GOLD-R [chosen-one@4x3, chosen-two@4x3]
                GOLD-P [third@4x3, fourth@4x3]
                GOLD-W [other-series@4x3]
                GOLD-Q [other-series@4x3]""",
                compact(4, group(series, choices, duplicates, borrowed), group(other, otherSeries)));
        assertEquals("""
                GOLD-R [chosen-one@4x3, chosen-two@4x3]
                GOLD-P [third@4x3, fourth@4x3]
                GOLD-W []""",
                compact(4, group(series, choices, duplicates, borrowed)));
    }

    /* ---------------------------------------------------------- rendering */

    private String brochure(int photos, CatalogExportService.FamilyGroup... groups) {
        return brochure(photos, false, false, groups);
    }

    private String brochure(int photos, boolean chapterPages, boolean keepOrder,
                            CatalogExportService.FamilyGroup... groups) {
        String html = renderer.renderHtml(model(
                CatalogExportService.Layout.BROCHURE, photos, chapterPages, keepOrder, groups));
        List<String> lines = new ArrayList<>();
        List<String> range = new ArrayList<>();
        String cell = "<td class=\"range-photo\"><div>";
        for (int at = html.indexOf(cell); at >= 0; at = html.indexOf(cell, at + 1)) {
            String rest = html.substring(at + cell.length());
            range.add(rest.startsWith("<img ") ? images(rest.substring(0, rest.indexOf('>') + 1)).getFirst() : "-");
        }
        lines.add("range " + range);
        for (String chapter : sections(html, "<section class=\"page category-page")) {
            String grid = between(chapter, "<table class=\"editorial-grid editorial-grid--", "</table>");
            lines.add("chapter " + (grid == null ? "none"
                    : grid.substring(0, grid.indexOf('"')) + " " + images(grid)));
        }
        for (String family : sections(html, "<section id=\"family-")) {
            String anchor = "family-" + family.substring(0, family.indexOf('"'));
            String media = between(family, "<table class=\"family-media family-media--", "</table>");
            String kind = media.substring(0, media.indexOf('"'));
            lines.add(anchor + " media " + kind + ("empty".equals(kind) ? "" : " " + images(media)));
            String extras = between(family, "<div class=\"gallery-strip\">", "</div>");
            if (extras != null) lines.add(anchor + " extras " + images(extras));
            String strip = between(family, "<table class=\"variant-photos\">", "</table>");
            if (strip == null) {
                lines.add(anchor + " strip none");
                continue;
            }
            List<String> colours = new ArrayList<>();
            String[] cells = strip.split("<td data-sku=\"");
            for (int index = 1; index < cells.length; index++) {
                List<String> image = images(cells[index]);
                colours.add(cells[index].substring(0, cells[index].indexOf('"')) + "="
                        + (image.isEmpty() ? "-" : image.getFirst()));
            }
            lines.add(anchor + " strip " + colours);
        }
        return String.join("\n", lines);
    }

    private String compact(int photos, CatalogExportService.FamilyGroup... groups) {
        String html = renderer.renderHtml(model(
                CatalogExportService.Layout.SIMPLE, photos, false, true, groups));
        List<String> lines = new ArrayList<>();
        String[] blocks = html.split("<div class=\"product-block\">");
        for (int index = 1; index < blocks.length; index++) {
            String sku = between(blocks[index], "<div class=\"sku\">SKU · ", "</div>");
            String visual = between(blocks[index], "<td class=\"visual\">", "<td class=\"identity\">");
            lines.add(sku + " " + (visual == null ? "no photo column" : images(visual)));
        }
        return String.join("\n", lines);
    }

    private List<String> images(String fragment) {
        learnLabels();
        List<String> found = new ArrayList<>();
        Matcher matcher = IMAGE.matcher(fragment);
        while (matcher.find()) found.add(labels.getOrDefault(matcher.group(1), "UNKNOWN"));
        return found;
    }

    /** Each stored photo in every printable rendition; photos stay apart because their colours differ. */
    private void learnLabels() {
        for (Map.Entry<String, StoredPhoto> photo : stored.entrySet()) {
            if (photo.getValue().bytes().length == 0
                    || labels.containsValue(photo.getKey() + "@4x3")) continue;
            for (int[] rendition : RENDITIONS) {
                int divisor = java.math.BigInteger.valueOf(rendition[0])
                        .gcd(java.math.BigInteger.valueOf(rendition[1])).intValue();
                String label = photo.getKey() + "@" + rendition[0] / divisor + "x" + rendition[1] / divisor;
                String uri = imageEncoder.encodeContainedTrimmed(
                        photo.getValue().bytes(), rendition[0], rendition[1], rendition[2], Color.WHITE);
                String previous = labels.putIfAbsent(uri, label);
                if (previous != null && !previous.equals(label)) {
                    throw new IllegalStateException(previous + " and " + label + " encode to the same picture");
                }
            }
        }
    }

    private static List<String> sections(String html, String opening) {
        List<String> found = new ArrayList<>();
        for (int at = html.indexOf(opening); at >= 0; at = html.indexOf(opening, at + 1)) {
            int end = html.indexOf("</section>", at);
            found.add(html.substring(at + opening.length(), end < 0 ? html.length() : end));
        }
        return found;
    }

    private static String between(String text, String opening, String closing) {
        int from = text.indexOf(opening);
        if (from < 0) return null;
        int to = text.indexOf(closing, from + opening.length());
        return text.substring(from + opening.length(), to < 0 ? text.length() : to);
    }

    /* ----------------------------------------------------------- fixtures */

    private static CatalogExportService.Model model(
            CatalogExportService.Layout layout, int photos, boolean chapterPages, boolean keepOrder,
            CatalogExportService.FamilyGroup... groups) {
        List<Product> products = new ArrayList<>();
        for (CatalogExportService.FamilyGroup group : groups) products.addAll(group.variants());
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, false, photos > 0, photos, "Golden selection", null, "en", layout,
                new CatalogExportService.BrochureOptions(
                        true, chapterPages, false, false, false, "Golden", "Selection"),
                null, Map.of(), keepOrder);
        return new CatalogExportService.Model(
                products, Map.of(CATEGORY.id(), CATEGORY), List.of(groups), request);
    }

    private static CatalogExportService.FamilyGroup group(
            CatalogFamilyReader.Family series, Product... variants) {
        return new CatalogExportService.FamilyGroup(series, List.of(variants), CATEGORY, series == null);
    }

    private static CatalogFamilyReader.Family series(List<CatalogFamilyReader.GalleryPhoto> gallery) {
        return series(gallery, null, null, "STANDARD");
    }

    private static CatalogFamilyReader.Family series(
            List<CatalogFamilyReader.GalleryPhoto> gallery, CatalogFamilyReader.GalleryPhoto overview,
            CatalogFamilyReader.GalleryPhoto detail, String detailSize) {
        return series(SERIES, gallery, overview, detail, detailSize);
    }

    private static CatalogFamilyReader.Family series(
            long id, List<CatalogFamilyReader.GalleryPhoto> gallery,
            CatalogFamilyReader.GalleryPhoto overview, CatalogFamilyReader.GalleryPhoto detail,
            String detailSize) {
        return new CatalogFamilyReader.Family(
                id, "golden-" + id, "golden-" + id, CATEGORY.id(), CATEGORY.code(), CATEGORY.name(),
                0, 0, "Golden series " + id, "A lasting collection.", "A refined presentation.",
                "Counter display", List.of(), null, List.of(), List.of(), gallery, overview, detail,
                detailSize);
    }

    private static Product colour(long id, String sku, int position, Photo... photos) {
        return colour(id, sku, SERIES, position, photos);
    }

    private static Product colour(long id, String sku, Long seriesId, int position, Photo... photos) {
        return product(id, sku, seriesId, CATEGORY.id(), position)
                .withVariantAttributes("Colour " + sku, null, null)
                .withPhotos(List.of(photos));
    }

    private CatalogFamilyReader.GalleryPhoto gallery(long id, String name, int position, Long colourId) {
        return new CatalogFamilyReader.GalleryPhoto(id, stored(name).key(), "image/png", position, colourId);
    }

    private Photo own(long id, String name, int position) {
        return photo(id, name, position, null);
    }

    private Photo inherited(long id, String name, int position, long seriesPhotoId) {
        return photo(id, name, position, seriesPhotoId);
    }

    private static Photo lead(Photo photo) {
        return photo.withLeadFor(Set.of(PhotoRole.CATALOGUE));
    }

    private Photo photo(long id, String name, int position, Long seriesPhotoId) {
        StoredPhoto source = stored(name);
        return new Photo(id, source.key(), name + ".png", "image/png", source.bytes().length,
                source.width(), source.height(), position, seriesPhotoId, Set.of());
    }

    /** "missing-…" is a row whose blob is gone; "…-big" is large enough to earn the lead tile. */
    private StoredPhoto stored(String name) {
        StoredPhoto known = stored.get(name);
        if (known != null) return known;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StoredPhoto created;
            if (name.startsWith("missing-")) {
                created = new StoredPhoto("sha256-" + HexFormat.of().formatHex(
                        digest.digest(("golden " + name).getBytes(StandardCharsets.UTF_8))) + ".png",
                        new byte[0], 96, 72);
            } else {
                int width = name.endsWith("-big") ? 1_000 : 96;
                int height = name.endsWith("-big") ? 860 : 72;
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(COLOURS.get((int) stored.values().stream()
                        .filter(other -> other.bytes().length > 0).count()));
                graphics.fillRect(0, 0, width, height);
                graphics.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                byte[] bytes = out.toByteArray();
                String key = "sha256-" + HexFormat.of().formatHex(digest.digest(bytes)) + ".png";
                photoStorage.storeKnown(key, name + ".png", "image/png", bytes);
                created = new StoredPhoto(key, bytes, width, height);
            }
            stored.put(name, created);
            return created;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
