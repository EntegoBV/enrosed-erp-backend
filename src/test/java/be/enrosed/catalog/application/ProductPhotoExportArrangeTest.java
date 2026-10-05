package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.ProductPhotoExport.FileEntry;
import be.enrosed.catalog.application.ProductPhotoExport.PhotoSelection;
import be.enrosed.catalog.application.ProductPhotoExport.Source;
import be.enrosed.catalog.application.ProductPhotoExportService.Candidate;
import be.enrosed.catalog.domain.Photo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folder order, website filter and duplicate handling, without a database. */
class ProductPhotoExportArrangeTest {

    private static final Candidate OWN = candidate("p1", 1L, null, "own-key", 1_000, 640, 480, Source.PRODUCT);
    private static final Candidate VARIANT = candidate("p2", 2L, 20L, "sha256-variant", 2_000, 900, 600, Source.VARIANT);
    private static final Candidate SERIES = candidate("p3", 3L, 30L, "sha256-series", 3_000, 1200, 800, Source.SERIES);

    @Test
    void theHoofdfotoOpensTheFolderThenWebsitePhotosThenTheRest() {
        List<FileEntry> files = ProductPhotoExportService.arrange(
                List.of(OWN, VARIANT, SERIES), List.of(SERIES), 2L, PhotoSelection.ALL);

        assertEquals(List.of("01-hoofdfoto.jpg", "02.jpg", "03.jpg"), files.stream().map(FileEntry::name).toList());
        assertEquals(List.of("sha256-variant", "sha256-series", "own-key"),
                files.stream().map(FileEntry::storageKey).toList());
        assertTrue(files.get(0).lead());
        assertFalse(files.get(1).lead());
        assertEquals(List.of(false, true, false), files.stream().map(FileEntry::website).toList());
        assertEquals(List.of(Source.VARIANT, Source.SERIES, Source.PRODUCT),
                files.stream().map(FileEntry::source).toList());
    }

    @Test
    void websiteSelectionKeepsOnlyGalleryPhotosAndLeadsWithTheWebsitePrimary() {
        List<FileEntry> files = ProductPhotoExportService.arrange(
                List.of(OWN, VARIANT, SERIES), List.of(SERIES, OWN), 2L, PhotoSelection.WEBSITE);

        assertEquals(List.of("sha256-series", "own-key"), files.stream().map(FileEntry::storageKey).toList(),
                "the internal variant photo is not on the website, so the gallery primary leads");
        assertEquals("01-hoofdfoto.jpg", files.get(0).name());
        assertTrue(files.stream().allMatch(FileEntry::website));
    }

    @Test
    void theHoofdfotoStaysFirstWhenItIsAlsoOnTheWebsite() {
        List<FileEntry> files = ProductPhotoExportService.arrange(
                List.of(OWN, VARIANT, SERIES), List.of(SERIES, VARIANT), 2L, PhotoSelection.WEBSITE);
        assertEquals(List.of("sha256-variant", "sha256-series"), files.stream().map(FileEntry::storageKey).toList());
    }

    @Test
    void theSamePictureUploadedTwiceIsExportedOnce() {
        Candidate ownCopyOfSeries = candidate("p4", 4L, null, "uuid-copy", 3_000, 1200, 800, Source.PRODUCT);
        Candidate sameObject = candidate("f9", null, 9L, "own-key", 1_000, 640, 480, Source.SERIES);

        List<FileEntry> files = ProductPhotoExportService.arrange(
                List.of(ownCopyOfSeries, OWN, SERIES, sameObject), List.of(SERIES), 4L, PhotoSelection.ALL);

        assertEquals(List.of("uuid-copy", "own-key"), files.stream().map(FileEntry::storageKey).toList());
        assertTrue(files.get(0).website(), "the kept copy is the one the website shows");
        assertEquals(Source.SERIES, files.get(0).source(), "and it is shared by the series");
        assertEquals(Source.SERIES, files.get(1).source());
        assertEquals(List.of("01-hoofdfoto.jpg", "02.jpg"), files.stream().map(FileEntry::name).toList());
    }

    @Test
    void noPhotosMeansNoFiles() {
        assertTrue(ProductPhotoExportService.arrange(List.of(), List.of(), null, PhotoSelection.ALL).isEmpty());
        assertTrue(ProductPhotoExportService.arrange(List.of(OWN), List.of(), 1L, PhotoSelection.WEBSITE).isEmpty());
    }

    @Test
    void seriesPhotosExportTheirLargeOriginalNeverTheSmallRendition() {
        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        image.id = 30L;
        image.originalFilename = "IMG_0001.JPG";
        image.smallStorageKey = "sha256-small.jpg";
        image.smallContentType = "image/jpeg";
        image.smallSizeBytes = 40_000;
        image.smallWidthPx = 480;
        image.smallHeightPx = 320;
        image.largeStorageKey = "sha256-large.png";
        image.largeContentType = "image/png";
        image.largeSizeBytes = 9_000_000;
        image.largeWidthPx = 4000;
        image.largeHeightPx = 3000;
        Photo projection = new Photo(3L, "sha256-large.png", "IMG_0001.JPG", "image/png",
                9_000_000, 4000, 3000, 2, 30L);

        Candidate candidate = Candidate.of(projection, image);

        assertEquals("sha256-large.png", candidate.storageKey());
        assertEquals(4000, candidate.widthPx());
        assertEquals("image/png", candidate.contentType());
        assertEquals(Source.SERIES, candidate.source(), "no variant link: shared by every colour");
        assertEquals("png", PhotoExportNames.extension(candidate.contentType(), candidate.originalFilename()),
                "the extension follows the stored bytes, not the upload's name");
    }

    private static Candidate candidate(String identity, Long productPhotoId, Long familyPhotoId, String key,
                                       long size, int width, int height, Source source) {
        return new Candidate(identity, productPhotoId, familyPhotoId, key, "image/jpeg", size, width, height,
                key + ".jpg", source);
    }
}
