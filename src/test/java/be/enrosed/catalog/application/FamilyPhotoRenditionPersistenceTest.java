package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyResource;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PublicationState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "Emre", roles = "admin")
class FamilyPhotoRenditionPersistenceTest {

    @Inject EntityManager entityManager;
    @Inject PhotoStorage storage;
    @Inject FamilyPhotoRenditionBackfillService backfill;
    @Inject ProductFamilyResource familyResource;

    @Test
    void boundedBackfillIsIdempotentAndIsolatesInvalidOrMissingRows() throws Exception {
        String prefix = "rendition-backfill-" + UUID.randomUUID();
        byte[] validBytes = largeJpeg(prefix.hashCode());
        byte[] invalidBytes = ByteBuffer.allocate(12)
                .put((byte) 0xff).put((byte) 0xd8).put((byte) 0xff).put((byte) 0)
                .putLong(prefix.hashCode()).array();
        Setup setup = QuarkusTransaction.requiringNew().call(() ->
                seedBackfill(prefix, validBytes, invalidBytes));
        long blobsBefore = blobCount(prefix);

        try {
            FamilyPhotoRenditionBackfillService.BatchResult first = backfill.processPending(8);

            assertEquals(3, first.selectedRows());
            assertEquals(3, first.examinedRows());
            assertEquals(1, first.resizedRows());
            assertEquals(2, first.reusedRows());
            assertEquals(0, first.failedRows());
            assertTrue(first.exhausted());

            BackfillState state = QuarkusTransaction.requiringNew().call(() ->
                    state(setup));
            assertEquals(PhotoRenditionService.POLICY_VERSION, state.validPolicy());
            assertNotEquals(setup.largeKey(), state.validSmallKey());
            assertEquals("image/jpeg", state.validSmallType());
            assertEquals(480, state.validSmallWidth());
            assertEquals(320, state.validSmallHeight());
            assertTrue(state.validSmallSize() < validBytes.length);
            assertEquals(setup.largeKey(), state.validLargeKey());
            assertEquals(setup.largeSha(), state.validLargeSha());
            assertEquals(validBytes.length, state.validLargeSize());
            assertEquals(1200, state.validLargeWidth());
            assertEquals(800, state.validLargeHeight());
            assertEquals(2, state.validPosition());
            assertEquals("ALT-SOURCE", state.validAltSource());
            assertEquals("[{\"language\":\"EN\",\"alt\":\"Exact alt\"}]",
                    state.validAltTexts());
            assertEquals("[\"WEBSITE\"]", state.validPublishedChannels());
            assertEquals(setup.largeKey(), state.compatibilityStorageKey(),
                    "legacy ERP projection intentionally stays on the exact large source");
            assertEquals(PhotoRenditionService.POLICY_VERSION, state.invalidPolicy());
            assertEquals(setup.invalidKey(), state.invalidSmallKey());
            assertEquals(PhotoRenditionService.POLICY_VERSION, state.missingPolicy());
            assertEquals(setup.missingKey(), state.missingSmallKey());
            assertNull(state.nonAdminPolicy());
            assertEquals(setup.largeKey(), state.nonAdminSmallKey());

            assertArrayEquals(validBytes, read(setup.largeKey()),
                    "backfill must never rewrite the exact large bytes");
            byte[] smallBytes = read(state.validSmallKey());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(smallBytes));
            assertEquals(480, decoded.getWidth());
            assertEquals(320, decoded.getHeight());
            assertEquals(blobsBefore + 1, blobCount(prefix));

            FamilyPhotoRenditionBackfillService.BatchResult second = backfill.processPending(8);
            assertEquals(0, second.selectedRows());
            assertEquals(0, second.examinedRows());
            assertEquals(0, second.resizedRows());
            assertEquals(0, second.failedRows());
            assertTrue(second.exhausted());
            assertEquals(blobsBefore + 1, blobCount(prefix),
                    "idempotent rerun must not create another blob");
            assertArrayEquals(smallBytes, read(state.validSmallKey()));
        } finally {
            cleanup(prefix, setup.familyId(), setup.productId());
        }
    }

    @Test
    void futureAdminUploadStoresExactLargeAndIndependentInternalSmall() throws Exception {
        String prefix = "rendition-upload-" + UUID.randomUUID();
        byte[] original = largeJpeg(prefix.hashCode());
        UploadSetup setup = QuarkusTransaction.requiringNew().call(() -> seedUpload(prefix));
        Path upload = Files.createTempFile(prefix, ".jpg");
        Files.write(upload, original);
        FileUpload file = mock(FileUpload.class);
        when(file.uploadedFile()).thenReturn(upload);
        when(file.fileName()).thenReturn(prefix + ".jpg");

        try {
            familyResource.uploadImage(setup.familyId(), file, null, null, null);

            UploadState state = QuarkusTransaction.requiringNew().call(() ->
                    uploadState(setup.familyId(), setup.productId()));
            assertEquals(1, state.familyPhotoCount());
            assertEquals(PhotoRenditionService.POLICY_VERSION, state.policy());
            assertEquals("[]", state.publishedChannels(),
                    "new dashboard uploads remain internal until explicitly published");
            assertNotEquals(state.largeKey(), state.smallKey());
            assertEquals(PhotoRenditionService.sha256(original), state.largeSha());
            assertEquals(1200, state.largeWidth());
            assertEquals(800, state.largeHeight());
            assertEquals(480, state.smallWidth());
            assertEquals(320, state.smallHeight());
            assertTrue(state.smallSize() < original.length);
            assertEquals(state.largeKey(), state.compatibilityStorageKey());
            assertArrayEquals(original, read(state.largeKey()));
            assertTrue(storage.exists(state.smallKey()));

            long blobs = blobCount(prefix);
            familyResource.uploadImage(setup.familyId(), file, null, null, null);
            UploadState duplicate = QuarkusTransaction.requiringNew().call(() ->
                    uploadState(setup.familyId(), setup.productId()));
            assertEquals(1, duplicate.familyPhotoCount());
            assertEquals(blobs, blobCount(prefix));
        } finally {
            Files.deleteIfExists(upload);
            cleanup(prefix, setup.familyId(), setup.productId());
        }
    }

    private Setup seedBackfill(String prefix, byte[] validBytes, byte[] invalidBytes) {
        ProductFamilyEntity family = family(prefix);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity product = product(prefix, family);
        entityManager.persist(product);
        entityManager.flush();

        String validSha = PhotoRenditionService.sha256(validBytes);
        String validKey = key(validSha, ".jpg");
        storage.storeKnown(validKey, prefix + "-valid.jpg", "image/jpeg", validBytes);
        String invalidSha = PhotoRenditionService.sha256(invalidBytes);
        String invalidKey = key(invalidSha, ".jpg");
        storage.storeKnown(invalidKey, prefix + "-invalid.jpg", "image/jpeg", invalidBytes);
        String missingKey = key("f".repeat(64), ".jpg");

        ProductFamilyPhotoEntity invalid = legacyPhoto(
                family, "admin-" + invalidSha, invalidKey, invalidSha,
                invalidBytes.length, 1200, 800, 0);
        ProductFamilyPhotoEntity missing = legacyPhoto(
                family, "admin-" + "f".repeat(64), missingKey, "f".repeat(64),
                100, 1200, 800, 1);
        ProductFamilyPhotoEntity valid = legacyPhoto(
                family, "admin-" + validSha, validKey, validSha,
                validBytes.length, 1200, 800, 2);
        valid.altTextSource = "ALT-SOURCE";
        valid.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Exact alt\"}]";
        valid.publishedChannelsJson = "[\"WEBSITE\"]";
        ProductFamilyPhotoEntity nonAdmin = legacyPhoto(
                family, "manifest-untouched", validKey, validSha,
                validBytes.length, 1200, 800, 3);
        for (ProductFamilyPhotoEntity photo : List.of(invalid, missing, valid, nonAdmin)) {
            family.photos.add(photo);
            entityManager.persist(photo);
        }
        entityManager.flush();

        ProductPhotoEntity compatibility = new ProductPhotoEntity();
        compatibility.product = product;
        compatibility.familyPhotoId = valid.id;
        compatibility.storageKey = valid.largeStorageKey;
        compatibility.originalFilename = valid.originalFilename;
        compatibility.contentType = valid.largeContentType;
        compatibility.sizeBytes = valid.largeSizeBytes;
        compatibility.widthPx = valid.largeWidthPx;
        compatibility.heightPx = valid.largeHeightPx;
        compatibility.position = 0;
        product.photos.add(compatibility);
        entityManager.persist(compatibility);
        entityManager.flush();
        return new Setup(
                family.id, product.id, valid.id, invalid.id, missing.id, nonAdmin.id,
                validKey, validSha, invalidKey, missingKey);
    }

    private UploadSetup seedUpload(String prefix) {
        ProductFamilyEntity family = family(prefix);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity product = product(prefix, family);
        entityManager.persist(product);
        entityManager.flush();
        return new UploadSetup(family.id, product.id);
    }

    private BackfillState state(Setup setup) {
        ProductFamilyPhotoEntity valid = entityManager.find(
                ProductFamilyPhotoEntity.class, setup.validPhotoId());
        ProductFamilyPhotoEntity invalid = entityManager.find(
                ProductFamilyPhotoEntity.class, setup.invalidPhotoId());
        ProductFamilyPhotoEntity missing = entityManager.find(
                ProductFamilyPhotoEntity.class, setup.missingPhotoId());
        ProductFamilyPhotoEntity nonAdmin = entityManager.find(
                ProductFamilyPhotoEntity.class, setup.nonAdminPhotoId());
        String compatibility = entityManager.createQuery("""
                        select photo.storageKey from ProductPhotoEntity photo
                        where photo.product.id = :productId and photo.familyPhotoId = :familyPhotoId
                        """, String.class)
                .setParameter("productId", setup.productId())
                .setParameter("familyPhotoId", setup.validPhotoId())
                .getSingleResult();
        return new BackfillState(
                valid.smallRenditionVersion, valid.smallStorageKey, valid.smallContentType,
                valid.smallSizeBytes, valid.smallWidthPx, valid.smallHeightPx,
                valid.largeStorageKey, valid.largeSha256, valid.largeSizeBytes,
                valid.largeWidthPx, valid.largeHeightPx, valid.position,
                valid.altTextSource, valid.altTextsJson, valid.publishedChannelsJson,
                compatibility,
                invalid.smallRenditionVersion, invalid.smallStorageKey,
                missing.smallRenditionVersion, missing.smallStorageKey,
                nonAdmin.smallRenditionVersion, nonAdmin.smallStorageKey);
    }

    private UploadState uploadState(long familyId, long productId) {
        List<ProductFamilyPhotoEntity> photos = entityManager.createQuery("""
                        select photo from ProductFamilyPhotoEntity photo
                        where photo.family.id = :familyId order by photo.position
                        """, ProductFamilyPhotoEntity.class)
                .setParameter("familyId", familyId)
                .getResultList();
        ProductFamilyPhotoEntity photo = photos.getFirst();
        String compatibility = entityManager.createQuery("""
                        select inherited.storageKey from ProductPhotoEntity inherited
                        where inherited.product.id = :productId
                          and inherited.familyPhotoId = :familyPhotoId
                        """, String.class)
                .setParameter("productId", productId)
                .setParameter("familyPhotoId", photo.id)
                .getSingleResult();
        return new UploadState(
                photos.size(), photo.smallRenditionVersion, photo.publishedChannelsJson,
                photo.smallStorageKey, photo.smallSizeBytes, photo.smallWidthPx,
                photo.smallHeightPx, photo.largeStorageKey, photo.largeSha256,
                photo.largeWidthPx, photo.largeHeightPx, compatibility);
    }

    private ProductFamilyEntity family(String prefix) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = prefix;
        family.publicHandle = prefix;
        family.name = prefix;
        family.active = true;
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        return family;
    }

    private ProductEntity product(String prefix, ProductFamilyEntity family) {
        ProductEntity product = new ProductEntity();
        product.sku = prefix + "-sku";
        product.name = prefix;
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.canonicalVariantKey = prefix + "-variant";
        product.active = true;
        product.inventoryKnown = true;
        product.piecesPerCarton = 1;
        return product;
    }

    private ProductFamilyPhotoEntity legacyPhoto(
            ProductFamilyEntity family, String sourceKey, String storageKey, String sha256,
            long size, int width, int height, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = family.familyKey + "-" + position + ".jpg";
        photo.originalWidthPx = width;
        photo.originalHeightPx = height;
        photo.smallStorageKey = storageKey;
        photo.smallContentType = "image/jpeg";
        photo.smallSha256 = sha256;
        photo.smallSizeBytes = size;
        photo.smallWidthPx = width;
        photo.smallHeightPx = height;
        photo.largeStorageKey = storageKey;
        photo.largeContentType = "image/jpeg";
        photo.largeSha256 = sha256;
        photo.largeSizeBytes = size;
        photo.largeWidthPx = width;
        photo.largeHeightPx = height;
        photo.position = position;
        photo.altTextSource = "TEST";
        photo.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Test\"}]";
        photo.publishedChannelsJson = "[]";
        return photo;
    }

    private long blobCount(String prefix) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createQuery("""
                        select count(blob) from PhotoBlobEntity blob
                        where blob.originalFilename like :prefix
                        """, Long.class)
                .setParameter("prefix", prefix + "%")
                .getSingleResult());
    }

    private byte[] read(String storageKey) {
        try (var input = storage.read(storageKey)) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void cleanup(String prefix, long familyId, long productId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductEntity product = entityManager.find(ProductEntity.class, productId);
            if (product != null) entityManager.remove(product);
            ProductFamilyEntity family = entityManager.find(ProductFamilyEntity.class, familyId);
            if (family != null) entityManager.remove(family);
            entityManager.flush();
            entityManager.createQuery("""
                            delete from PhotoBlobEntity blob
                            where blob.originalFilename like :prefix
                            """)
                    .setParameter("prefix", prefix + "%")
                    .executeUpdate();
        });
    }

    private static String key(String sha256, String extension) {
        return "sha256-" + sha256 + extension;
    }

    private static byte[] largeJpeg(long seed) throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(0x454e524f534544L ^ seed);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 255 / 1199 + random.nextInt(32)) & 0xff;
                int green = (y * 255 / 799 + random.nextInt(32)) & 0xff;
                int blue = (x + y + random.nextInt(64)) & 0xff;
                image.setRGB(x, y, red << 16 | green << 8 | blue);
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.98f);
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private record Setup(
            long familyId, long productId, long validPhotoId, long invalidPhotoId,
            long missingPhotoId, long nonAdminPhotoId, String largeKey, String largeSha,
            String invalidKey, String missingKey) {}

    private record UploadSetup(long familyId, long productId) {}

    private record BackfillState(
            String validPolicy, String validSmallKey, String validSmallType,
            long validSmallSize, Integer validSmallWidth, Integer validSmallHeight,
            String validLargeKey, String validLargeSha, long validLargeSize,
            Integer validLargeWidth, Integer validLargeHeight, int validPosition,
            String validAltSource, String validAltTexts, String validPublishedChannels,
            String compatibilityStorageKey,
            String invalidPolicy, String invalidSmallKey,
            String missingPolicy, String missingSmallKey,
            String nonAdminPolicy, String nonAdminSmallKey) {}

    private record UploadState(
            int familyPhotoCount, String policy, String publishedChannels,
            String smallKey, long smallSize, Integer smallWidth, Integer smallHeight,
            String largeKey, String largeSha, Integer largeWidth, Integer largeHeight,
            String compatibilityStorageKey) {}
}
