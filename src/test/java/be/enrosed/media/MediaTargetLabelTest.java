package be.enrosed.media;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library list labels every link in bulk. A link to a purchase order
 * used to break the whole list, because the nested purchase order entity
 * cannot be named by its simple name in HQL.
 */
@QuarkusTest
class MediaTargetLabelTest {
    @Inject MediaService media;
    @Inject EntityManager entities;

    @Test
    void aPurchaseOrderLinkDoesNotBreakTheList() throws Exception {
        MediaDtos.UploadResult uploaded = media.upload("Container CMR", image("cmr.jpg"), null);
        long assetId = uploaded.asset().id();
        QuarkusTransaction.requiringNew().run(() -> {
            MediaLinkEntity link = new MediaLinkEntity();
            link.assetId = assetId;
            link.targetType = MediaTargetType.PURCHASE_ORDER;
            link.targetId = 987_654_321L;
            link.role = MediaRole.INTERNAL;
            link.createdAt = Instant.now();
            entities.persist(link);
        });

        List<MediaDtos.Summary> page = media.list("Container CMR", null, null, null, null, null, false, 0, 50);
        MediaDtos.Summary summary = page.stream().filter(item -> item.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals(1, summary.links().size());
        assertEquals(MediaTargetType.PURCHASE_ORDER, summary.links().get(0).targetType());
        assertNull(summary.links().get(0).targetLabel(), "an order that no longer exists has no label, and no error");
        assertTrue(media.get(assetId).links().size() == 1);
    }

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
