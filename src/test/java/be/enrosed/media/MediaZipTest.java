package be.enrosed.media;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several library files come down as one zip, named after their library names. */
@QuarkusTest
class MediaZipTest {
    @Inject MediaService media;

    @Test
    void chosenFilesArriveAsOneZipWithDistinctNames() throws Exception {
        long stamp = System.nanoTime();
        MediaDtos.Detail first = media.upload("Prijslijst", document("a-" + stamp), null).asset();
        MediaDtos.Detail second = media.upload("Prijslijst", document("b-" + stamp), null).asset();

        byte[] zip = given().auth().preemptive().basic("emre", "named-auth-test-password")
                .contentType("application/json")
                .body("{\"ids\": [" + first.id() + ", " + second.id() + ", 999999999], \"variant\": \"original\"}")
                .when().post("/api/media-assets/zip")
                .then().statusCode(200).contentType("application/zip")
                .extract().asByteArray();

        List<String> names = new ArrayList<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
                total += in.readAllBytes().length;
            }
        }
        assertEquals(List.of("Prijslijst.pdf", "Prijslijst (2).pdf"), names, "same name twice gets a counter; an unknown id is skipped");
        assertTrue(total > 0);
    }

    private static MediaUploadPolicy.ValidatedFile document(String tag) {
        return new MediaUploadPolicy.ValidatedFile("prijslijst-" + tag + ".pdf", "application/pdf",
                MediaKind.DOCUMENT, ("%PDF-1.4 " + tag).getBytes());
    }
}
