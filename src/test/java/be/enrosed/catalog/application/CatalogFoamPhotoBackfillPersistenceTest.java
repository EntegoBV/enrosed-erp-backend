package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Currency;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CatalogFoamPhotoBackfillPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject CatalogFoamPhotoBackfillService backfill;
    @Inject FamilyPhotoPublicationPolicy publication;
    @Inject FamilyImageVariantService imageVariants;
    @Inject FamilyPhotoCompatibilityService compatibility;
    @Inject PhotoStorage storage;

    @Test
    @TestTransaction
    void linksTheExistingRedPhotoOnceAndKeepsTheOriginalProductPhoto() throws Exception {
        CategoryEntity category = category("FOAM-BACKFILL-TEST", 9_001);
        ProductFamilyEntity family = family(category, "foam-half-heart-25", "Half heart rose foam 25 cm");

        // The canonical variant key identifies the red product; its SKU is editable.
        ProductEntity red = product(category.id, family.id,
                "FHH-25-RD", "Half heart rose foam 25 cm", "Red", "25 cm");
        red.canonicalVariantKey = "foam-half-heart-25-red";
        entityManager.persist(red);
        entityManager.flush();

        PhotoStorage.Stored stored = attachPhoto(red, "foam-red.png");
        entityManager.flush();

        CatalogFoamPhotoBackfillService.Result first = backfill.apply();
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity migrated = entityManager.find(ProductFamilyEntity.class, family.id);
        ProductEntity migratedRed = entityManager.find(ProductEntity.class, red.id);
        ProductFamilyPhotoEntity familyPhoto = migrated.photos.getFirst();
        assertEquals(1, first.linkedPhotos());
        assertEquals("foam-half-heart-25", migrated.familyKey);
        assertEquals("foam-half-heart-25-red", migratedRed.canonicalVariantKey);
        assertEquals(CatalogFoamPhotoBackfillService.PRIMARY_SOURCE_KEY, familyPhoto.sourceKey);
        assertEquals(0, familyPhoto.position);
        assertEquals(red.id, familyPhoto.variantProduct.id);
        assertEquals(List.of(CatalogChannel.CATALOGUE), publication.publishedChannels(familyPhoto));
        assertNotEquals(familyPhoto.smallStorageKey, "");
        assertTrue(migratedRed.photos.stream().anyMatch(photo ->
                photo.familyPhotoId == null && photo.storageKey.equals(stored.storageKey())),
                "the original product-owned photo must stay available");

        publication.replacePublishedChannels(
                familyPhoto, List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE));
        entityManager.flush();
        CatalogFoamPhotoBackfillService.Result second = backfill.apply();
        entityManager.flush();
        entityManager.clear();
        ProductFamilyEntity repeated = entityManager.find(ProductFamilyEntity.class, family.id);
        assertEquals(0, second.linkedPhotos());
        assertEquals(1, repeated.photos.stream().filter(photo ->
                CatalogFoamPhotoBackfillService.PRIMARY_SOURCE_KEY.equals(photo.sourceKey)).count());
        assertEquals(List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE),
                publication.publishedChannels(repeated.photos.getFirst()),
                "a later seed pass must preserve an administrator's website selection");
    }

    @Test
    @TestTransaction
    void aLaterStartNeverMovesRebindsOrRepublishesTheSeedPhoto() throws Exception {
        ProductFamilyEntity arranged = seededHalfHeart("FOAM-SEED-KEPT-TEST", 9_004);
        ProductFamilyPhotoEntity seed = arranged.photos.getFirst();

        // What an administrator arranged afterwards: another photo leads, the seed is
        // internal and no longer belongs to the red colour.
        seriesPhoto(arranged, "admin-uploaded-later", 0);
        seed.position = 1;
        imageVariants.assign(seed, null);
        publication.replacePublishedChannels(seed, List.of());
        arranged.photos.sort(Comparator.comparingInt(photo -> photo.position));
        entityManager.flush();
        compatibility.sync(arranged);

        CatalogFoamPhotoBackfillService.Result later = backfill.apply();
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity kept = entityManager.find(ProductFamilyEntity.class, arranged.id);
        ProductFamilyPhotoEntity keptSeed = kept.photos.stream().filter(photo ->
                        CatalogFoamPhotoBackfillService.PRIMARY_SOURCE_KEY.equals(photo.sourceKey))
                .findFirst().orElseThrow();
        assertEquals(0, later.linkedPhotos());
        assertEquals(2, kept.photos.size());
        assertEquals("admin-uploaded-later", kept.photos.getFirst().sourceKey);
        assertEquals(1, keptSeed.position, "a later start must not move the seed back to the front");
        assertNull(keptSeed.variantProduct, "a later start must not bind the seed to the red colour again");
        assertEquals(List.of(), publication.publishedChannels(keptSeed),
                "a later start must not publish the seed for the catalogue again");
    }

    @Test
    @TestTransaction
    void aDeletedSeedPhotoIsNotRecreatedWhileTheSeriesHasOtherPhotos() throws Exception {
        ProductFamilyEntity arranged = seededHalfHeart("FOAM-SEED-DELETED-TEST", 9_005);
        ProductFamilyPhotoEntity seed = arranged.photos.getFirst();
        long redId = seed.variantProduct.id;

        seriesPhoto(arranged, "admin-uploaded-later", 1);
        entityManager.flush();
        arranged.photos.remove(seed);
        arranged.photos.getFirst().position = 0;
        entityManager.flush();
        compatibility.sync(arranged);

        CatalogFoamPhotoBackfillService.Result later = backfill.apply();
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity kept = entityManager.find(ProductFamilyEntity.class, arranged.id);
        assertEquals(0, later.linkedPhotos());
        assertEquals(List.of("admin-uploaded-later"),
                kept.photos.stream().map(photo -> photo.sourceKey).toList(),
                "a deleted seed must stay deleted while the series has other photos");
        assertTrue(entityManager.find(ProductEntity.class, redId).photos.stream()
                        .anyMatch(photo -> photo.familyPhotoId == null),
                "the red colour still has the own photo the seed was once made from");
    }

    @Test
    @TestTransaction
    void anOldSkuNoLongerReparentsOrRekeysAProductThatAlreadyHasAFamily() throws Exception {
        CategoryEntity category = category("FOAM-LEGACY-SKU-TEST", 9_003);
        ProductFamilyEntity family = family(category, "odoo-half-heart-foam-25", "Half heart rose foam 25 cm");
        ProductEntity red = product(category.id, family.id,
                "ENR-ODOO-HALF-HEART-FOAM-25-RED", "Half heart rose foam 25 cm", "Red", "25 cm");
        entityManager.persist(red);
        entityManager.flush();
        attachPhoto(red, "foam-legacy-red.png");
        entityManager.flush();

        CatalogFoamPhotoBackfillService.Result result = backfill.apply();
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity untouched = entityManager.find(ProductFamilyEntity.class, family.id);
        assertEquals(0, result.linkedPhotos());
        assertEquals(0, result.createdFamilies());
        assertEquals("odoo-half-heart-foam-25", untouched.familyKey);
        assertTrue(untouched.photos.isEmpty());
        assertNull(entityManager.find(ProductEntity.class, red.id).canonicalVariantKey,
                "only an unlinked legacy row may still be identified by its old SKU");
    }

    @Test
    @TestTransaction
    void createsTheSingletonFamilyAndOnlyOneMissingMixedVariant() throws Exception {
        CategoryEntity category = category("FOAM-STRUCTURE-BACKFILL-TEST", 9_002);
        ProductFamilyEntity bearFamily = family(category, "foam-bear-25", "Foam bear 25 cm");

        ProductEntity bear = product(category.id, bearFamily.id,
                "FB-25-RD", "Foam bear 25 cm", "Red", "25 cm");
        bear.canonicalVariantKey = "foam-bear-25-red";
        // An unlinked legacy row may still bootstrap its missing family by the old SKU.
        ProductEntity bearWithHeart = product(category.id, null,
                "ENR-P05", "Foam bear with heart 25 cm", "Red", "25 cm");
        entityManager.persist(bear);
        entityManager.persist(bearWithHeart);
        entityManager.flush();
        attachPhoto(bear, "foam-bear-red.png");
        attachPhoto(bearWithHeart, "foam-bear-heart-red.png");
        entityManager.flush();

        CatalogFoamPhotoBackfillService.Result first = backfill.apply();
        entityManager.flush();
        entityManager.clear();

        ProductEntity migratedBear = entityManager.find(ProductEntity.class, bear.id);
        ProductEntity migratedHeart = entityManager.find(ProductEntity.class, bearWithHeart.id);
        ProductFamilyEntity singleton = entityManager.find(
                ProductFamilyEntity.class, migratedHeart.familyId);
        assertEquals("foam-bear-25", entityManager.find(
                ProductFamilyEntity.class, migratedBear.familyId).familyKey);
        assertEquals("foam-bear-with-heart-25", singleton.familyKey);
        assertEquals(PublicationState.PUBLISHED, singleton.catalogueStatus);
        assertEquals(PublicationState.DRAFT, singleton.websiteStatus);
        assertEquals(1, first.createdFamilies());
        assertEquals(1, first.createdVariants());
        assertEquals(1L, entityManager.createQuery(
                        "select count(item) from ProductEntity item "
                                + "where item.canonicalVariantKey = :key", Long.class)
                .setParameter("key", "foam-bear-25-mixed").getSingleResult());

        CatalogFoamPhotoBackfillService.Result second = backfill.apply();
        entityManager.flush();
        assertEquals(0, second.createdFamilies());
        assertEquals(0, second.createdVariants());
        assertEquals(1L, entityManager.createQuery(
                        "select count(item) from ProductEntity item "
                                + "where item.canonicalVariantKey = :key", Long.class)
                .setParameter("key", "foam-bear-25-mixed").getSingleResult());
    }

    private CategoryEntity category(String code, int position) {
        CategoryEntity category = new CategoryEntity();
        category.code = code;
        category.name = "Foam";
        category.description = "Foam category";
        category.eyebrow = "Foam";
        category.position = position;
        category.updatedAt = Instant.now();
        entityManager.persist(category);
        entityManager.flush();
        return category;
    }

    private ProductFamilyEntity family(CategoryEntity category, String familyKey, String name) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = familyKey;
        family.active = true;
        family.name = name;
        family.highlightsJson = "[]";
        family.tagsJson = "[]";
        family.categoryId = category.id;
        family.categoryKey = CategoryPublicKey.from(category.code);
        family.categoryName = category.name;
        family.categoryPosition = category.position;
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        family.createdAt = Instant.now();
        family.updatedAt = family.createdAt;
        entityManager.persist(family);
        entityManager.flush();
        return family;
    }

    /** A half heart series whose red colour has one own photo, after the first start seeded it. */
    private ProductFamilyEntity seededHalfHeart(String categoryCode, int position) throws Exception {
        CategoryEntity category = category(categoryCode, position);
        ProductFamilyEntity family = family(category, "foam-half-heart-25", "Half heart rose foam 25 cm");
        ProductEntity red = product(category.id, family.id,
                "FHH-25-RD", "Half heart rose foam 25 cm", "Red", "25 cm");
        red.canonicalVariantKey = "foam-half-heart-25-red";
        entityManager.persist(red);
        entityManager.flush();
        attachPhoto(red, "foam-red.png");
        entityManager.flush();

        assertEquals(1, backfill.apply().linkedPhotos());
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(ProductFamilyEntity.class, family.id);
    }

    /** An internal series photo for every colour, as an administrator's upload leaves it. */
    private static ProductFamilyPhotoEntity seriesPhoto(
            ProductFamilyEntity family, String sourceKey, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = sourceKey + ".jpg";
        photo.smallStorageKey = sourceKey + "-small";
        photo.largeStorageKey = sourceKey + "-large";
        photo.smallContentType = "image/jpeg";
        photo.largeContentType = "image/jpeg";
        photo.smallWidthPx = 320;
        photo.smallHeightPx = 320;
        photo.largeWidthPx = 1200;
        photo.largeHeightPx = 1200;
        photo.position = position;
        photo.altTextsJson = "[]";
        photo.publishedChannelsJson = "[]";
        family.photos.add(photo);
        return photo;
    }

    private PhotoStorage.Stored attachPhoto(ProductEntity product, String filename) throws Exception {
        byte[] sourceBytes = sourcePng();
        PhotoStorage.Stored stored = storage.store(filename, "image/png", sourceBytes);
        ProductPhotoEntity original = new ProductPhotoEntity();
        original.product = product;
        original.storageKey = stored.storageKey();
        original.originalFilename = filename;
        original.contentType = "image/png";
        original.sizeBytes = stored.sizeBytes();
        original.widthPx = stored.widthPx();
        original.heightPx = stored.heightPx();
        original.position = 0;
        product.photos.add(original);
        entityManager.persist(original);
        return stored;
    }

    private static ProductEntity product(
            long categoryId, Long familyId, String sku, String name, String colour, String size) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = name;
        product.publicName = product.name;
        product.description = "Red Foam half heart";
        product.categoryId = categoryId;
        product.colour = colour;
        product.variantSize = size;
        product.colourHex = "#A91F32";
        product.active = true;
        product.inventoryKnown = true;
        product.familyId = familyId;
        product.familyKey = familyId == null ? null : "legacy-family";
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        product.piecesPerCarton = 1;
        product.exwCurrency = Currency.USD;
        product.fixedSalesPriceEur = BigDecimal.TEN;
        product.websiteStatus = PublicationState.DRAFT;
        product.orderAppStatus = PublicationState.DRAFT;
        return product;
    }

    private static byte[] sourcePng() throws Exception {
        BufferedImage image = new BufferedImage(720, 540, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(169, 31, 50));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
