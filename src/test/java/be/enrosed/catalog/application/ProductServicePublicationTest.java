package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.push.StaffActionPushNotifier;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void refusesABarcodeThatAlreadySitsOnAnotherProductAndSaysWhere() {
        Product existing = withCodes(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true),
                new Barcodes("5410000000019", "15410000000016"), null);
        repository.add(existing);
        Product incoming = withCodes(product(null, "ENR-P02", "Beschrijving", "witte-roos",
                null, null, true), Barcodes.none(), "15410000000016");

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> service.create(incoming));

        assertEquals("Barcode 15410000000016 staat al op Roos (ENR-P01) als omdoosbarcode",
                error.getMessage());
        assertEquals("Barcode 5410000000019 staat al op Roos (ENR-P01) als stukbarcode",
                service.barcodeOwner(" 5410000000019 ", null).describe("5410000000019"));
        assertNull(service.barcodeOwner("5410000000019", 1L),
                "the product that carries the code may keep it");
    }

    @Test
    void manualStockCorrectionReplacesTheCountAndRefusesNegatives() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true));

        assertEquals(4, service.get(1L).stockQuantity());
        assertEquals(250, service.setStock(1L, 250).stockQuantity());
        assertEquals(250, repository.findById(1L).orElseThrow().stockQuantity());
        assertEquals(0, service.setStock(1L, 0).stockQuantity(), "sold out is a valid count");
        assertThrows(BusinessRuleException.class, () -> service.setStock(1L, -1));
    }

    @Test
    void aVariantCopyKeepsTheGiftBoxButNotItsBarcode() {
        Product boxed = withCodes(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true),
                new Barcodes("5410000000019", "15410000000016"), "5410000000026");
        repository.add(boxed);

        Product copy = service.duplicate(1L, "Roze");

        assertEquals(PackagingKind.GIFT_BOX, copy.packaging().kind(), "the box itself comes along");
        assertEquals(boxed.packaging().dimensions(), copy.packaging().dimensions());
        assertNull(copy.packaging().barcode(), "the box's EAN is unique and stays behind");
        assertNull(copy.barcodes().inner());
        assertNull(copy.barcodes().outer());
    }

    @Test
    void refusesOneBarcodeOnTwoLevelsOfTheSameProduct() {
        Product twice = withCodes(product(null, "ENR-P03", "Beschrijving", "gele-roos",
                null, null, true), new Barcodes("5410000000019", "5410000000019"), null);

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> service.create(twice));

        assertTrue(error.getMessage().contains("zowel als stukbarcode als omdoosbarcode"),
                error.getMessage());
    }

    private static Product withCodes(Product base, Barcodes codes, String giftBoxCode) {
        Packaging packaging = giftBoxCode == null ? Packaging.none()
                : new Packaging(PackagingKind.GIFT_BOX, new Dimensions(one(), one(), one()), giftBoxCode);
        return new Product(base.id(), base.sku(), base.name(), base.dimensions(), packaging,
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.active(), base.familyId(),
                base.canonicalVariantKey(), base.canonicalBarcode(), base.variantPosition(),
                base.inventoryKnown(), base.familyKey(), base.publicHandle(), base.websiteStatus(),
                base.orderAppStatus(), codes, base.hsCode(), base.carton(), base.exwPrice(),
                base.exwCurrency(), base.extraUnitCost(), base.landedCostEur(), base.landedCostSource(),
                base.markupPct(), base.fixedSalesPriceEur(), base.stockQuantity(), base.photos(),
                base.texts());
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
        /* Lowercase is the same colour, not a variant difference. */
        BusinessRuleException sameColour = assertThrows(BusinessRuleException.class,
                () -> service.duplicate(1L, null, "#aa1122", null));
        assertTrue(sameColour.getMessage().contains("verschillen"), sameColour.getMessage());
        BusinessRuleException invalidHex = assertThrows(BusinessRuleException.class,
                () -> service.duplicate(1L, null, "#zz1122", null));
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
    void staleGeneralProductPutCannotOverwriteAtomicPublicTranslations() {
        Product current = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withTexts(List.of(new ProductText(
                        Language.FR, "Texte approuvé", "Description approuvée", "Rouge", "Petit")));
        repository.add(current);

        Product staleRequest = current.withTexts(List.of(new ProductText(
                Language.FR, "Ancien brouillon", "Ancienne description", "Rouge", "Petit")));
        Product updated = service.update(1L, staleRequest);

        assertEquals("Texte approuvé", updated.textIn(Language.FR).name());
        assertEquals("Description approuvée", updated.textIn(Language.FR).description());
    }

    @Test
    void productCreateValidatesTranslationDuplicatesAndDatabaseBoundariesBeforeSave() {
        Product boundary = product(null, "ENR-P255", "Beschrijving", null,
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withTexts(List.of(new ProductText(
                        Language.EN, "x".repeat(255), "d".repeat(2_000),
                        "c".repeat(255), "s".repeat(255))));
        assertEquals(255, service.create(boundary).textIn(Language.EN).name().length());

        Product overlong = product(null, "ENR-P256", "Beschrijving", null,
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withTexts(List.of(new ProductText(
                        Language.EN, "x".repeat(256), null, null, null)));
        BusinessRuleException length = assertThrows(
                BusinessRuleException.class, () -> service.create(overlong));
        assertTrue(length.getMessage().contains("255"), length.getMessage());
        assertTrue(repository.findBySku("ENR-P256").isEmpty());

        Product duplicate = product(null, "ENR-P-DUP-TEXT", "Beschrijving", null,
                PublicationState.DRAFT, PublicationState.DRAFT, true)
                .withTexts(List.of(
                        new ProductText(Language.EN, "One", null, null, null),
                        new ProductText(Language.EN, "Two", null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(duplicate));
        assertTrue(repository.findBySku("ENR-P-DUP-TEXT").isEmpty());
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
    void photoUploadRegistersRollbackCleanupBeforeSavingItsProductReference() {
        ProductRepository failingRepository = mock(ProductRepository.class);
        Product current = product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, false);
        when(failingRepository.findById(1L)).thenReturn(Optional.of(current));
        PhotoStorage blobStore = mock(PhotoStorage.class);
        byte[] bytes = "GIF89a-new-photo".getBytes(StandardCharsets.US_ASCII);
        when(blobStore.store(eq("supplier-photo.gif"), eq("image/gif"), any(byte[].class)))
                .thenReturn(new PhotoStorage.Stored("upload-key", bytes.length, null, null));
        when(failingRepository.save(any(Product.class)))
                .thenThrow(new IllegalStateException("database write failed"));
        ProductService failingService = new ProductService(
                failingRepository, blobStore, mock(ProductValidator.class));
        @SuppressWarnings("unchecked")
        Event<ProductPhotoCleanup.UploadReady> cleanup = mock(Event.class);
        failingService.photoUploadCleanup = cleanup;

        assertThrows(IllegalStateException.class, () -> failingService.addPhoto(
                1L, "supplier-photo.tmp", new ByteArrayInputStream(bytes)));

        InOrder sequence = inOrder(blobStore, cleanup, failingRepository);
        sequence.verify(blobStore).store(eq("supplier-photo.gif"), eq("image/gif"), any(byte[].class));
        sequence.verify(cleanup).fire(new ProductPhotoCleanup.UploadReady(1L, "upload-key"));
        sequence.verify(failingRepository).save(any(Product.class));
    }

    @Test
    void theSamePictureIsRefusedASecondTimeEvenUnderAnotherName() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, false));
        byte[] bytes = "GIF89a-the-same-picture".getBytes(StandardCharsets.US_ASCII);
        service.addPhoto(1L, "roos.gif", new ByteArrayInputStream(bytes));

        BusinessRuleException error = assertThrows(BusinessRuleException.class, () -> service.addPhoto(
                1L, "roos-kopie.gif", new ByteArrayInputStream(bytes)));

        assertEquals("Deze foto staat al bij dit product (roos.gif)", error.getMessage());
        assertEquals(1, photoStorage.blobs.size(), "nothing was stored for the duplicate");

        /* Same size, different bytes: a genuinely different photo still goes in. */
        Product updated = service.addPhoto(1L, "ander.gif",
                new ByteArrayInputStream("GIF89a-another-picture!".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(2, updated.photos().size());
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

        @SuppressWarnings("unchecked")
        Event<ProductPhotoCleanup.DeleteReady> cleanup = mock(Event.class);
        service.photoDeleteCleanup = cleanup;

        service.delete(1L);

        assertTrue(repository.findById(1L).isEmpty());
        assertTrue(photoStorage.deleted.isEmpty(), "blob cleanup waits for a successful commit");
        verify(cleanup).fire(argThat(ready -> ready.storageKeys().equals(List.of("photo-key"))));
    }

    @Test
    void removingAProductOwnedPhotoDefersBlobCleanupUntilCommit() {
        repository.add(product(1L, "ENR-P01", "Beschrijving", "rode-roos",
                PublicationState.DRAFT, PublicationState.DRAFT, true));
        @SuppressWarnings("unchecked")
        Event<ProductPhotoCleanup.DeleteReady> cleanup = mock(Event.class);
        service.photoDeleteCleanup = cleanup;

        Product updated = service.removePhoto(1L, 3L);

        assertTrue(updated.photos().isEmpty());
        assertTrue(photoStorage.deleted.isEmpty(), "the blob still exists until the product change commits");
        verify(cleanup).fire(new ProductPhotoCleanup.DeleteReady(List.of("photo-key")));
    }

    @Test
    void createUpdateAndDeleteAppendServerAttributedActivityAndFireOneSafePush() {
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        service.activity = activityInstance;

        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.current()).thenReturn(new ActorRef("berat", "Berat"));
        @SuppressWarnings("unchecked")
        Instance<CurrentActor> actorInstance = mock(Instance.class);
        when(actorInstance.isResolvable()).thenReturn(true);
        when(actorInstance.get()).thenReturn(currentActor);
        service.actor = actorInstance;

        @SuppressWarnings("unchecked")
        Event<StaffActionPushNotifier.Ready> productPush = mock(Event.class);
        service.staffPush = productPush;
        @SuppressWarnings("unchecked")
        Event<ProductPhotoCleanup.DeleteReady> cleanup = mock(Event.class);
        service.photoDeleteCleanup = cleanup;

        Product created = service.create(product(82L, "ENR-BOWL-XL", "Beschrijving", "bowl-xl",
                PublicationState.DRAFT, PublicationState.DRAFT, true));
        Product updated = service.update(82L, created.withActive(false));
        service.delete(82L);

        InOrder auditOrder = inOrder(activities);
        auditOrder.verify(activities).record(ActivityLogService.ACTION_CREATED,
                "PRODUCT", "82", "ENR-BOWL-XL", "Product aangemaakt");
        auditOrder.verify(activities).record(ActivityLogService.ACTION_UPDATED,
                "PRODUCT", "82", "ENR-BOWL-XL", "Product bijgewerkt",
                List.of(new ActivityChangeDto("active", "Actief", "Ja", "Nee")));
        auditOrder.verify(activities).record(ActivityLogService.ACTION_DELETED,
                "PRODUCT", "82", "ENR-BOWL-XL", "Product verwijderd");

        verify(productPush).fire(argThat(ready -> ready.entityId() == updated.id()
                && ready.actor().equals(new ActorRef("berat", "Berat"))
                && ready.productSku().equals("ENR-BOWL-XL")));
        verify(cleanup).fire(argThat(ready -> ready.storageKeys().equals(List.of("photo-key"))));
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
        private final Map<String, byte[]> blobs = new LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Stored store(String originalFilename, String contentType, byte[] data) {
            this.data = data;
            String key = "stored-" + (blobs.size() + 1);
            blobs.put(key, data);
            return new Stored(key, data.length, null, null);
        }

        @Override
        public InputStream read(String storageKey) {
            return new ByteArrayInputStream(blobs.getOrDefault(storageKey, new byte[0]));
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
        }

        @Override
        public boolean exists(String storageKey) { return true; }
    }
}
