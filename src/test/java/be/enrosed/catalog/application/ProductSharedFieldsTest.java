package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductSharedFieldsTest {
    private InMemoryProducts products;
    private ProductService service;

    @BeforeEach
    void setUp() {
        products = new InMemoryProducts();
        service = new ProductService(
                products,
                new NoopPhotoStorage(),
                new ProductValidator(new BarcodeValidator()));
    }

    @Test
    void appliesOnlySelectedGroupsAndPreservesEveryVariantOwnedValue() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);

        ProductService.SharedFieldsResult result = service.applySharedFields(
                source.id(),
                42L,
                List.of(target.id()),
                Set.of(
                        ProductService.SharedField.DESCRIPTION,
                        ProductService.SharedField.PACKAGING,
                        ProductService.SharedField.PURCHASE_PRICE));

        Product updated = service.get(target.id());
        assertEquals(List.of(2L), result.updatedProductIds());
        assertEquals(1, result.updatedProducts());

        assertEquals(target.name(), updated.name(), "NAME was not selected");
        assertEquals(source.description(), updated.description());
        assertEquals(target.dimensions(), updated.dimensions(), "DIMENSIONS was not selected");
        assertEquals(source.packaging().kind(), updated.packaging().kind());
        assertEquals(source.packaging().dimensions(), updated.packaging().dimensions());
        assertEquals(source.packaging().piecesPerUnit(), updated.packaging().piecesPerUnit());
        assertEquals(target.packaging().barcode(), updated.packaging().barcode(),
                "a colour variant keeps its own packaging EAN");
        assertEquals(target.carton(), updated.carton(), "CARTON was not selected");
        assertEquals(source.exwPrice(), updated.exwPrice());
        assertEquals(source.exwCurrency(), updated.exwCurrency());
        assertEquals(source.extraUnitCost(), updated.extraUnitCost());
        assertEquals(target.markupPct(), updated.markupPct(), "SALES_PRICE was not selected");
        assertEquals(target.fixedSalesPriceEur(), updated.fixedSalesPriceEur());
        assertEquals(target.hsCode(), updated.hsCode(), "HS_CODE was not selected");

        assertEquals(target.sku(), updated.sku());
        assertEquals(target.colour(), updated.colour());
        assertEquals(target.variantSize(), updated.variantSize());
        assertEquals(target.colourHex(), updated.colourHex());
        assertEquals(target.barcodes(), updated.barcodes());
        assertEquals(target.canonicalVariantKey(), updated.canonicalVariantKey());
        assertEquals(target.stockQuantity(), updated.stockQuantity());
        assertEquals(target.inventoryKnown(), updated.inventoryKnown());
        assertEquals(target.photos(), updated.photos());
        assertEquals(target.supplierId(), updated.supplierId());
        assertEquals(target.supplierNote(), updated.supplierNote());
        assertEquals(target.landedCostEur(), updated.landedCostEur());
        assertEquals(target.landedCostSource(), updated.landedCostSource());

        ProductText english = updated.textIn(Language.EN);
        assertEquals("Target English name", english.name());
        assertEquals("Source English description", english.description());
        assertEquals("Pink", english.colour());
        assertEquals("Large", english.variantSize());
        ProductText french = updated.textIn(Language.FR);
        assertEquals("Description source", french.description(),
                "a source-only translated description is added");
        ProductText german = updated.textIn(Language.DE);
        assertEquals("Zielname", german.name());
        assertEquals("Alte Beschreibung", german.description(),
                "an incomplete source never erases an existing target translation");
        assertEquals("Rosa", german.colour());
        assertEquals("Groß", german.variantSize());
    }

    @Test
    void nameCopiesItsTranslationsWithoutTouchingDescriptionsOrVariantTranslations() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);

        service.applySharedFields(
                source.id(), 42L, List.of(target.id()),
                Set.of(ProductService.SharedField.NAME));

        Product updated = service.get(target.id());
        assertEquals(source.name(), updated.name());
        ProductText english = updated.textIn(Language.EN);
        assertEquals("Source English name", english.name());
        assertEquals("Old English description", english.description());
        assertEquals("Pink", english.colour());
        assertEquals("Large", english.variantSize());
        assertEquals("Zielname", updated.textIn(Language.DE).name(),
                "an absent source name keeps the translated target name");
    }

    @Test
    void copiesTheRemainingWhitelistedGroupsAsAtomicPairs() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);

        service.applySharedFields(
                source.id(),
                42L,
                List.of(target.id()),
                Set.of(
                        ProductService.SharedField.DIMENSIONS,
                        ProductService.SharedField.CARTON,
                        ProductService.SharedField.SALES_PRICE,
                        ProductService.SharedField.HS_CODE));

        Product updated = service.get(target.id());
        assertEquals(source.dimensions(), updated.dimensions());
        assertEquals(source.carton(), updated.carton());
        assertEquals(source.markupPct(), updated.markupPct());
        assertEquals(source.fixedSalesPriceEur(), updated.fixedSalesPriceEur());
        assertEquals(source.hsCode(), updated.hsCode());
        assertEquals(target.exwPrice(), updated.exwPrice(),
                "purchase price remains independent when its group was not selected");
        assertEquals(target.exwCurrency(), updated.exwCurrency());
        assertEquals(target.extraUnitCost(), updated.extraUnitCost());
        assertEquals(target.packaging(), updated.packaging());
    }

    @Test
    void recordsOneActivityForEachTargetThatReallyChanged() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        service.activity = activityInstance;

        service.applySharedFields(
                source.id(), 42L, List.of(target.id()),
                Set.of(ProductService.SharedField.DESCRIPTION));

        verify(activities).record(
                eq(ActivityLogService.ACTION_UPDATED),
                eq("PRODUCT"),
                eq("2"),
                eq("ENR-TARGET"),
                eq("Gedeelde productgegevens toegepast"),
                anyList());
    }

    @Test
    void skipsTargetsThatAlreadyHaveTheSelectedSharedValues() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);
        service.applySharedFields(
                source.id(), 42L, List.of(target.id()),
                Set.of(ProductService.SharedField.DESCRIPTION));
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        service.activity = activityInstance;

        ProductService.SharedFieldsResult second = service.applySharedFields(
                source.id(), 42L, List.of(target.id()),
                Set.of(ProductService.SharedField.DESCRIPTION));

        assertEquals(List.of(), second.updatedProductIds());
        assertEquals(0, second.updatedProducts());
        verifyNoInteractions(activities);
    }

    @Test
    void rejectsOutsidersAndEmptySelectionsWithoutSavingAnything() {
        Product source = source(1L, 42L);
        Product outsider = target(2L, 99L);
        products.add(source);
        products.add(outsider);

        BusinessRuleException wrongFamily = assertThrows(
                BusinessRuleException.class,
                () -> service.applySharedFields(
                        source.id(), 42L, List.of(outsider.id()),
                        Set.of(ProductService.SharedField.CARTON)));
        assertTrue(wrongFamily.getMessage().contains("verwachte productfamilie"),
                wrongFamily.getMessage());
        assertEquals(0, products.saves);

        BusinessRuleException noTargets = assertThrows(
                BusinessRuleException.class,
                () -> service.applySharedFields(
                        source.id(), 42L, List.of(),
                        Set.of(ProductService.SharedField.CARTON)));
        assertTrue(noTargets.getMessage().contains("minstens één kleur of maat"),
                noTargets.getMessage());

        BusinessRuleException noFields = assertThrows(
                BusinessRuleException.class,
                () -> service.applySharedFields(
                        source.id(), 42L, List.of(outsider.id()), Set.of()));
        assertTrue(noFields.getMessage().contains("minstens één gedeeld veld"),
                noFields.getMessage());
        assertEquals(0, products.saves);
    }

    @Test
    void rechecksMembershipAfterStableFamilyAndProductLocks() {
        Product source = source(1L, 42L);
        Product target = target(2L, 42L);
        products.add(source);
        products.add(target);
        ProductFamilyWriteGuard guard = mock(ProductFamilyWriteGuard.class);
        doAnswer(invocation -> {
            products.add(target.withCanonicalIdentity(
                    99L,
                    target.canonicalVariantKey(),
                    target.canonicalBarcode(),
                    target.variantPosition(),
                    target.inventoryKnown()));
            return null;
        }).when(guard).lockProducts(anyCollection());
        service = new ProductService(
                products,
                new NoopPhotoStorage(),
                new ProductValidator(new BarcodeValidator()),
                mock(CanonicalCatalogDaos.Families.class),
                guard,
                null);

        BusinessRuleException changedFamily = assertThrows(
                BusinessRuleException.class,
                () -> service.applySharedFields(
                        source.id(), 42L, List.of(target.id()),
                        Set.of(ProductService.SharedField.DESCRIPTION)));

        assertTrue(changedFamily.getMessage().contains("verwachte productfamilie"),
                changedFamily.getMessage());
        verify(guard).lockFamilies(anyCollection());
        verify(guard).lockProducts(anyCollection());
        assertEquals(0, products.saves);
    }

    private static Product source(long id, long familyId) {
        return new Product(
                id,
                "ENR-SOURCE",
                "Shared foam product",
                dimensions("10", "20", "30", "0.45"),
                new Packaging(
                        PackagingKind.GIFT_BOX,
                        dimensions("12", "22", "32", "0.55"),
                        "5410000000033",
                        2),
                "Red",
                "Small",
                "#AA1122",
                "Shared quote description",
                3L,
                7L,
                "Source supplier note",
                true,
                familyId,
                "source-red-small",
                null,
                0,
                true,
                "foam-family",
                null,
                PublicationState.DRAFT,
                PublicationState.DRAFT,
                Barcodes.none(),
                "6702",
                new Carton(dimensions("50", "40", "30", null), 12,
                        decimal("8.5"), 9_600),
                decimal("2.15"),
                Currency.CNY,
                decimal("0.10"),
                decimal("3.90"),
                "PO-SOURCE",
                decimal("35"),
                decimal("8.95"),
                10,
                List.of(new Photo(11L, "source-photo", "source.jpg", "image/jpeg", 10, 5, 5, 0)),
                List.of(
                        new ProductText(Language.EN, "Source English name",
                                "Source English description", "Red", "Small"),
                        new ProductText(Language.FR, "Nom source",
                                "Description source", "Rouge", "Petit")),
                false);
    }

    private static Product target(long id, long familyId) {
        return new Product(
                id,
                "ENR-TARGET",
                "Target pink product",
                dimensions("7", "8", "9", "0.25"),
                new Packaging(
                        PackagingKind.DISPLAY,
                        dimensions("21", "22", "23", "0.75"),
                        "5410000000026",
                        8),
                "Pink",
                "Large",
                "#CC7799",
                "Old quote description",
                3L,
                9L,
                "Keep target supplier note",
                true,
                familyId,
                "target-pink-large",
                "canonical-target-barcode",
                1,
                false,
                "foam-family",
                null,
                PublicationState.DRAFT,
                PublicationState.DRAFT,
                new Barcodes("5410000000019", "15410000000016"),
                "0603",
                new Carton(dimensions("31", "32", "33", null), 6,
                        decimal("4.2"), 7_200),
                decimal("5.25"),
                Currency.USD,
                decimal("0.35"),
                decimal("6.80"),
                "PO-TARGET",
                decimal("22"),
                decimal("12.50"),
                77,
                List.of(new Photo(22L, "target-photo", "target.jpg", "image/jpeg", 20, 8, 8, 0)),
                List.of(
                        new ProductText(Language.EN, "Target English name",
                                "Old English description", "Pink", "Large"),
                        new ProductText(Language.DE, "Zielname",
                                "Alte Beschreibung", "Rosa", "Groß")),
                true);
    }

    private static Dimensions dimensions(
            String length, String width, String height, String weight) {
        return new Dimensions(
                decimal(length), decimal(width), decimal(height),
                weight == null ? null : decimal(weight));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static final class InMemoryProducts implements ProductRepository {
        private final Map<Long, Product> byId = new LinkedHashMap<>();
        private int saves;

        void add(Product product) {
            byId.put(product.id(), product);
        }

        @Override
        public List<Product> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<Product> findBySupplier(long supplierId) {
            return byId.values().stream()
                    .filter(product -> product.supplierId() != null
                            && product.supplierId() == supplierId)
                    .toList();
        }

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Product> findBySku(String sku) {
            return byId.values().stream().filter(product -> product.sku().equals(sku)).findFirst();
        }

        @Override
        public Optional<Product> findByPublicHandle(String publicHandle) {
            return Optional.empty();
        }

        @Override
        public Product save(Product product) {
            saves++;
            byId.put(product.id(), product);
            return product;
        }

        @Override
        public void deleteById(long id) {
            byId.remove(id);
        }

        @Override public long countByCategory(long categoryId) { return 0; }
        @Override public long countByHsCode(String hsCode) { return 0; }
        @Override public long countBySupplier(long supplierId) { return 0; }
    }

    private static final class NoopPhotoStorage implements PhotoStorage {
        @Override
        public Stored store(String originalFilename, String contentType, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream read(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {}

        @Override
        public boolean exists(String storageKey) {
            return false;
        }
    }
}
