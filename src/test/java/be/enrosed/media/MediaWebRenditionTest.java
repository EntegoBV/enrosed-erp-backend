package be.enrosed.media;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A photo lives in two sizes: the upload as the print-quality original, and a web copy of at most 1600 px. */
@QuarkusTest
class MediaWebRenditionTest {
    @Inject MediaService media;

    @Test
    void aLargePhotoGetsAWebCopyAndBothAreServed() throws Exception {
        MediaDtos.UploadResult uploaded = media.upload("Standfoto groot", photo(2400, 1500), null);
        MediaDtos.Detail detail = uploaded.asset();
        assertNotNull(detail.web(), "an image wider than 1600 px gets a web copy");
        assertEquals(1600, detail.web().widthPx());
        assertEquals(1000, detail.web().heightPx());
        assertTrue(detail.web().sizeBytes() < detail.sizeBytes(), "the web copy is lighter than the original");
        assertEquals(detail.web(), detail.versions().get(0).web());

        try (InputStream web = media.webFile(detail.id()).data(); InputStream original = media.file(detail.id()).data()) {
            assertEquals(detail.web().sizeBytes(), web.readAllBytes().length);
            assertEquals(detail.sizeBytes(), original.readAllBytes().length);
        }

        String token = media.share(detail.id()).share().token();
        given().when().get("/api/public/media/" + token + "/web")
                .then().statusCode(200).contentType(startsWith("image/jpeg"));
        given().when().get("/api/public/media/" + token)
                .then().statusCode(200).contentType(startsWith("image/jpeg"));
    }

    @Test
    void aSmallPhotoKeepsOneFileAndDocumentsHaveNoWebCopy() throws Exception {
        MediaDtos.Detail small = media.upload("Kleine foto", photo(800, 600), null).asset();
        assertNotNull(small.web(), "a small image still reports its web size");
        assertEquals(small.sizeBytes(), small.web().sizeBytes(), "nothing to shrink: the original is the web copy");

        MediaDtos.Detail document = media.upload("Prijslijst", new MediaUploadPolicy.ValidatedFile(
                "prijslijst-" + System.nanoTime() + ".pdf", "application/pdf", MediaKind.DOCUMENT,
                ("%PDF-1.4 " + System.nanoTime()).getBytes()), null).asset();
        assertNull(document.web());
        try (InputStream data = media.webFile(document.id()).data()) {
            assertEquals(document.sizeBytes(), data.readAllBytes().length, "a document is served as itself");
        }
    }

    @Test
    void theListCanShowWhatNothingUsesYet() throws Exception {
        MediaDtos.Detail loose = media.upload("Losse foto " + System.nanoTime(), photo(640, 480), null).asset();
        List<Long> unused = media.list(null, null, null, null, null, null, false, 0, 500, null, false, false)
                .stream().map(MediaDtos.Summary::id).toList();
        List<Long> used = media.list(null, null, null, null, null, null, false, 0, 500, null, false, true)
                .stream().map(MediaDtos.Summary::id).toList();
        assertTrue(unused.contains(loose.id()));
        assertFalse(used.contains(loose.id()));
    }

    /** A fresh JPEG of pixel noise - it compresses like a real photo, and no two tests share a SHA-256. */
    private static MediaUploadPolicy.ValidatedFile photo(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return new MediaUploadPolicy.ValidatedFile("foto-" + System.nanoTime() + ".jpg", "image/jpeg",
                MediaKind.IMAGE, out.toByteArray());
    }
}
