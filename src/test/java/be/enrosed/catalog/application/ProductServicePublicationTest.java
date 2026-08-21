package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductServicePublicationTest {

    private FakeProducts repository;
    private FakePhotoStorage photoStorage;
    private ProductService service;

    @BeforeEach
    void setUp() {
        repository = new FakeProducts();
        photoStorage = new FakePhotoStorage();
        service = new ProductService(repository, photoStorage,
                new ProductValidator(new BarcodeValidator()));
    }

    @Test
    void rejectsPublishedStateWhileReadinessBlockersRemain() {
        Product current = product(1L, "ENR-P01", null, "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true);
        repository.add(current);

        Product request = current.withPublicationMetadata(
                current.familyKey(), current.publicHandle(),
                PublicationState.PUBLISHED, PublicationState.DRAFT);
        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> service.update(1L, request));

        assertTrue(error.getMessage().contains("Beschrijving ontbreekt"), error.getMessage());
        assertEquals(PublicationState.DRAFT,
                repository.findById(1L).orElseThrow().publicationState(CatalogChannel.WEBSITE));
    }

    @Test
    void normalizesAndProtectsUniquePublicHandles() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true));
        Product second = product(null, "ENR-P02", "Beschrijving", " RODE-ROOS ",
                null, null, true);

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> service.create(second));

        assertTrue(error.getMessage().contains("bestaat al"), error.getMessage());
    }

    @Test
    void duplicateKeepsFamilyButStartsPrivateWithoutHandle() {
        Product source = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.PUBLISHED, PublicationState.READY, true);
        repository.add(source);

        Product duplicate = service.duplicate(1L, "Roze");

        assertEquals("rose-family", duplicate.familyKey());
        assertNull(duplicate.publicHandle());
        assertEquals(PublicationState.DRAFT, duplicate.publicationState(CatalogChannel.WEBSITE));
        assertEquals(PublicationState.DRAFT, duplicate.publicationState(CatalogChannel.ORDER_APP));
    }

    @Test
    void duplicateCanCreateASizeVariantWithoutChangingColourOrSwatch() {
        Product source = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withVariantAttributes("Rood", "Small", "#AA1122")
                .withCanonicalIdentity(42L, "source-key", null, 3, true)
                .withTexts(List.of(new ProductText(
                        Language.EN, "Rose", "Description", "Red")));
        repository.add(source);

        Product duplicate = service.duplicate(1L, null, null, "Large");

        assertEquals(42L, duplicate.familyId());
        assertEquals("Rood", duplicate.colour());
        assertEquals("Large", duplicate.variantSize());
        assertEquals("#AA1122", duplicate.colourHex());
        assertEquals("Red", duplicate.textIn(Language.EN).colour());
        assertNull(duplicate.canonicalVariantKey());
    }

    @Test
    void duplicateColourRemainsBackwardCompatibleAndDropsTheOldSwatch() {
        Product source = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withVariantAttributes("Rood", "Medium", "#AA1122")
                .withTexts(List.of(new ProductText(
                        Language.EN, "Rose", "Description", "Red")));
        repository.add(source);

        Product duplicate = service.duplicate(1L, "Roze");

        assertEquals("Roze", duplicate.colour());
        assertEquals("Medium", duplicate.variantSize());
        assertNull(duplicate.colourHex());
        assertNull(duplicate.textIn(Language.EN).colour());
    }

    @Test
    void duplicateBlankValuesExplicitlyClearColourSwatchAndSize() {
        Product source = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withVariantAttributes("Rood", "XL", "#AA1122");
        repository.add(source);

        Product duplicate = service.duplicate(1L, "", "", " ");

        assertNull(duplicate.colour());
        assertNull(duplicate.colourHex());
        assertNull(duplicate.variantSize());
    }

    @Test
    void duplicateRequiresARealVariantDifferenceAndValidHex() {
        Product source = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withVariantAttributes("Rood", "Medium", "#AA1122");
        repository.add(source);

        assertThrows(BusinessRuleException.class,
                () -> service.duplicate(1L, null, null, null));
        BusinessRuleException invalidHex = assertThrows(BusinessRuleException.class,
                () -> service.duplicate(1L, null, "#aa1122", null));
        assertTrue(invalidHex.getMessage().contains("#RRGGBB"), invalidHex.getMessage());
    }

    @Test
    void updateFromOlderClientDoesNotSilentlyUnpublishOrClearPublicIdentity() {
        Product current = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.PUBLISHED, PublicationState.READY, true)
                .withVariantAttributes("Rood", "XL", "#A91F32");
        repository.add(current);

        Product requestWithoutNewFields = current.withPublicationMetadata(null, null, null, null);
        Product updated = service.update(1L, requestWithoutNewFields);

        assertEquals("rose-family", updated.familyKey());
        assertEquals("rode-roos", updated.publicHandle());
        assertEquals(PublicationState.PUBLISHED,
                updated.publicationState(CatalogChannel.WEBSITE));
        assertEquals(PublicationState.READY,
                updated.publicationState(CatalogChannel.ORDER_APP));
    }

    @Test
    void productPutPreservesNullVariantFieldsAndUsesBlankAsExplicitClear() {
        Product current = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withVariantAttributes("Rood", "XL", "#A91F32");
        repository.add(current);

        Product preserved = service.update(
                1L, current.withVariantAttributes("Rood", null, null));
        assertEquals("XL", preserved.variantSize());
        assertEquals("#A91F32", preserved.colourHex());

        Product updated = service.update(
                1L, current.withVariantAttributes("Rood", " ", ""));

        assertNull(updated.variantSize());
        assertNull(updated.colourHex());
    }

    @Test
    void masterDataCsvRoundTripsPublicIdentityAndChannelStates() {
        Product current = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.PUBLISHED, PublicationState.READY, true)
                .withVariantAttributes("Rood", "XL", "#A91F32");
        repository.add(current);
        ProductCsv csv = new ProductCsv(
                repository, new ProductValidator(new BarcodeValidator()));

        byte[] exported = csv.export();
        String text = new String(exported, StandardCharsets.UTF_8);
        assertTrue(text.lines().findFirst().orElseThrow().endsWith(
                "family_key;public_handle;website_status;order_app_status;variant_size;colour_hex"), text);
        assertTrue(text.contains("rose-family;rode-roos;PUBLISHED;READY;XL;#A91F32"), text);

        ProductCsv.ImportResult result = csv.importFrom(new ByteArrayInputStream(exported));
        assertEquals(1, result.updatedProducts());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(PublicationState.PUBLISHED,
                repository.findById(1L).orElseThrow().publicationState(CatalogChannel.WEBSITE));
        assertEquals("XL", repository.findById(1L).orElseThrow().variantSize());
        assertEquals("#A91F32", repository.findById(1L).orElseThrow().colourHex());
    }

    @Test
    void photoUploadUsesDetectedTypeAndKeepsOriginalBytes() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, false));
        byte[] bytes = "GIF89a-original-quality".getBytes(StandardCharsets.US_ASCII);

        Product updated = service.addPhoto(
                1L, "supplier-photo.tmp", new ByteArrayInputStream(bytes));

        assertEquals("image/gif", updated.primaryPhoto().contentType());
        assertEquals("supplier-photo.gif", updated.primaryPhoto().originalFilename());
        assertArrayEquals(bytes, photoStorage.data);
    }

    @Test
    void photoUploadRejectsNonImageBeforeStorage() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, false));

        assertThrows(PhotoUploadPolicy.InvalidPhotoException.class, () -> service.addPhoto(
                1L, "attack.html", new ByteArrayInputStream("<html>".getBytes(StandardCharsets.UTF_8))));

        assertNull(photoStorage.data);
    }

    @Test
    void refusesToDeleteAProductThatIsPartOfBusinessHistory() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true));
        repository.referenceCounts = new ProductRepository.ReferenceCounts(1, 2, 3, 4);

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> service.delete(1L));

        assertTrue(error.getMessage().contains("ENR-P01"), error.getMessage());
        assertTrue(error.getMessage().contains("1 inkooporderregel"), error.getMessage());
        assertTrue(error.getMessage().contains("2 verkooporderregels"), error.getMessage());
        assertTrue(error.getMessage().contains("3 palletregels"), error.getMessage());
        assertTrue(error.getMessage().contains("4 offertevoorstelregels"), error.getMessage());
        assertTrue(error.getMessage().contains("inactief"), error.getMessage());
        assertTrue(repository.findById(1L).isPresent());
        assertTrue(photoStorage.deleted.isEmpty());
    }

    @Test
    void deletesAnUnreferencedProductAndItsPhoto() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true));

        service.delete(1L);

        assertTrue(repository.findById(1L).isEmpty());
        assertEquals(List.of("photo-key"), photoStorage.deleted);
    }

    private static Product product(Long id, String sku, String description, String publicHandle,
                                   PublicationState website, PublicationState orderApp,
                                   boolean withPhoto) {
        List<Photo> photos = withPhoto
                ? List.of(new Photo(3L, "photo-key", "rose.jpg", "image/jpeg", 10, 5, 5, 0))
                : List.of();
        return new Product(
                id, sku, "Roos", new Dimensions(one(), one(), one()), "Rood", description,
                1L, 2L, true, "rose-family", publicHandle, website, orderApp,
                Barcodes.none(), "0603", new Carton(new Dimensions(one(), one(), one()), 6, one()),
                one(), Currency.USD, BigDecimal.ZERO, new BigDecimal("10"), "PO-1",
                new BigDecimal("25"), new BigDecimal("20"), 4, photos, List.of());
    }

    private static BigDecimal one() {
        return BigDecimal.ONE;
    }

    private static final class FakeProducts implements ProductRepository {
        private final Map<String, Product> bySku = new LinkedHashMap<>();
        private ReferenceCounts referenceCounts = ReferenceCounts.none();

        void add(Product product) {
            bySku.put(product.sku(), product);
        }

        @Override
        public List<Product> findAll() {
            return new ArrayList<>(bySku.values());
        }

        @Override
        public List<Product> findBySupplier(long supplierId) {
            return List.of();
        }

        @Override
        public Optional<Product> findById(long id) {
            return bySku.values().stream()
                    .filter(product -> product.id() != null && product.id() == id)
                    .findFirst();
        }

        @Override
        public Optional<Product> findBySku(String sku) {
            return Optional.ofNullable(bySku.get(sku));
        }

        @Override
        public Optional<Product> findByPublicHandle(String publicHandle) {
            return bySku.values().stream()
                    .filter(product -> java.util.Objects.equals(product.publicHandle(), publicHandle))
                    .findFirst();
        }

        @Override
        public Product save(Product product) {
            bySku.put(product.sku(), product);
            return product;
        }

        @Override
        public void deleteById(long id) {
            findById(id).ifPresent(product -> bySku.remove(product.sku()));
        }

        @Override
        public ReferenceCounts referenceCounts(long productId) {
            return referenceCounts;
        }

        @Override
        public long countByCategory(long categoryId) { return 0; }

        @Override
        public long countByHsCode(String hsCode) { return 0; }

        @Override
        public long countBySupplier(long supplierId) { return 0; }
    }

    private static final class FakePhotoStorage implements PhotoStorage {
        private byte[] data;
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Stored store(String originalFilename, String contentType, byte[] data) {
            this.data = data;
            return new Stored("stored", data.length, null, null);
        }

        @Override
        public InputStream read(String storageKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
        }

        @Override
        public boolean exists(String storageKey) { return true; }
    }
}
