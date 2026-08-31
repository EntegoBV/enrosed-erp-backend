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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CatalogFoamPhotoBackfillPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject CatalogFoamPhotoBackfillService backfill;
    @Inject FamilyPhotoPublicationPolicy publication;
    @Inject PhotoStorage storage;

    @Test
    @TestTransaction
    void linksTheExistingRedPhotoOnceAndKeepsTheOriginalProductPhoto() throws Exception {
        CategoryEntity category = new CategoryEntity();
        category.code = "FOAM-BACKFILL-TEST";
        category.name = "Foam";
        category.description = "Foam category";
        category.eyebrow = "Foam";
        category.position = 9_001;
        category.updatedAt = Instant.now();
        entityManager.persist(category);
        entityManager.flush();

        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "odoo-half-heart-foam-25";
        family.active = true;
        family.name = "Half heart rose foam 25 cm";
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

        ProductEntity red = product(category.id, family.id,
                "ENR-ODOO-HALF-HEART-FOAM-25-RED",
                "Half heart rose foam 25 cm", "Red", "25 cm");
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
    void createsTheSingletonFamilyAndOnlyOneMissingMixedVariant() throws Exception {
        CategoryEntity category = new CategoryEntity();
        category.code = "FOAM-STRUCTURE-BACKFILL-TEST";
        category.name = "Foam";
        category.description = "Foam category";
        category.eyebrow = "Foam";
        category.position = 9_002;
        category.updatedAt = Instant.now();
        entityManager.persist(category);
        entityManager.flush();

        ProductFamilyEntity bearFamily = new ProductFamilyEntity();
        bearFamily.familyKey = "model-108-109";
        bearFamily.active = true;
        bearFamily.name = "Foam bear 25 cm";
        bearFamily.highlightsJson = "[]";
        bearFamily.tagsJson = "[]";
        bearFamily.categoryId = category.id;
        bearFamily.categoryKey = CategoryPublicKey.from(category.code);
        bearFamily.categoryName = category.name;
        bearFamily.categoryPosition = category.position;
        bearFamily.websiteStatus = PublicationState.DRAFT;
        bearFamily.orderAppStatus = PublicationState.DRAFT;
        bearFamily.catalogueStatus = PublicationState.DRAFT;
        bearFamily.createdAt = Instant.now();
        bearFamily.updatedAt = bearFamily.createdAt;
        entityManager.persist(bearFamily);
        entityManager.flush();

        ProductEntity bear = product(category.id, bearFamily.id,
                "ENR-P06", "Foam bear 25 cm", "Red", "25 cm");
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
