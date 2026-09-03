package be.enrosed.media;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folders order the library for people; public links open one file to anyone with the token. */
@QuarkusTest
class MediaFolderShareTest {
    @Inject MediaService media;

    @Test
    void foldersHoldFilesAndHandThemBackWhenDeleted() throws Exception {
        MediaDtos.Folder marketing = media.createFolder(new MediaDtos.FolderRequest("Marketing " + System.nanoTime(), null));
        MediaDtos.Folder beurs = media.createFolder(new MediaDtos.FolderRequest("Beurs", marketing.id()));
        MediaDtos.UploadResult inFolder = media.upload("Standfoto", image("stand.jpg"), beurs.id());
        MediaDtos.UploadResult loose = media.upload("Los bestand", image("loose.jpg"), null);

        assertEquals(beurs.id(), inFolder.asset().folderId());
        assertNull(loose.asset().folderId());
        List<Long> inBeurs = media.list(null, null, null, null, null, null, false, 0, 100, beurs.id(), false)
                .stream().map(MediaDtos.Summary::id).toList();
        assertEquals(List.of(inFolder.asset().id()), inBeurs);
        List<Long> atRoot = media.list(null, null, null, null, null, null, false, 0, 500, null, true)
                .stream().map(MediaDtos.Summary::id).toList();
        assertTrue(atRoot.contains(loose.asset().id()));
        assertFalse(atRoot.contains(inFolder.asset().id()));
        assertEquals(1, media.folders().stream().filter(f -> f.id().equals(beurs.id())).findFirst().orElseThrow().assetCount());

        MediaDtos.Detail moved = media.move(loose.asset().id(), marketing.id());
        assertEquals(marketing.id(), moved.folderId());

        assertThrows(be.enrosed.shared.BusinessRuleException.class,
                () -> media.updateFolder(marketing.id(), new MediaDtos.FolderRequest(null, beurs.id())),
                "a folder cannot move into its own subfolder");

        media.deleteFolder(beurs.id());
        assertEquals(marketing.id(), media.get(inFolder.asset().id()).folderId(),
                "files of a deleted folder land in the parent");
        media.deleteFolder(marketing.id());
        assertNull(media.get(inFolder.asset().id()).folderId());
    }

    @Test
    void aPublicLinkServesTheFileUntilItIsRevoked() throws Exception {
        MediaDtos.UploadResult uploaded = media.upload("Prijslijst", image("share.jpg"), null);
        long id = uploaded.asset().id();
        assertNull(uploaded.asset().share());

        MediaDtos.Detail shared = media.share(id);
        assertNotNull(shared.share());
        String token = shared.share().token();
        assertTrue(token.length() >= 30);
        assertEquals(token, media.share(id).share().token(), "asking twice keeps one live link");

        byte[] served = given().when().get("/api/public/media/" + token)
                .then().statusCode(200).contentType(startsWith("image/jpeg"))
                .extract().asByteArray();
        try (InputStream original = media.file(id).data()) {
            assertEquals(original.readAllBytes().length, served.length);
        }
        given().when().get("/api/public/media/" + token + "/download")
                .then().statusCode(200).header("Content-Disposition", startsWith("attachment"));
        assertEquals(2, media.get(id).share().downloads());

        MediaDtos.Detail revoked = media.unshare(id);
        assertNull(revoked.share());
        given().when().get("/api/public/media/" + token).then().statusCode(404);
        given().when().get("/api/public/media/nope").then().statusCode(404);
    }

    /** A real JPEG with a unique tail, so the SHA-256 dedupe never joins two tests. */
    private MediaUploadPolicy.ValidatedFile image(String name) throws Exception {
        byte[] jpeg;
        try (InputStream in = getClass().getResourceAsStream("/seed-images/P06.jpg")) {
            jpeg = in.readAllBytes();
        }
        byte[] tail = (name + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[jpeg.length + tail.length];
        System.arraycopy(jpeg, 0, bytes, 0, jpeg.length);
        System.arraycopy(tail, 0, bytes, jpeg.length, tail.length);
        return new MediaUploadPolicy.ValidatedFile(name, "image/jpeg", MediaKind.IMAGE, bytes);
    }
}
