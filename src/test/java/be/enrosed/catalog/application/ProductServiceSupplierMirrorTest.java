package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierEntity;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.security.AdminIdentityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static be.enrosed.catalog.application.ProductSupplierServicePersistenceTest.product;
import static be.enrosed.catalog.application.ProductSupplierServicePersistenceTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The product's purchase columns as a copy of its Voorkeur row: what a product PUT (editor, old tab, CSV) does to the
 * rows, and the rows on create, duplicate, delete and shared fields.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductServiceSupplierMirrorTest {

    private static final long YIWU = 9201L;
    private static final long CULINAN = 9202L;
    private static final long PUBEI = 9203L;

    @Inject ProductService products;
    @Inject ProductSupplierService suppliers;
    @Inject EntityManager entities;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void createWithASupplierMakesItsOnePreferredRow() {
        Product made = products.create(product("PM-CREATE", YIWU, "2.35").withSupplierNote("Dome 2 mm"));

        List<ProductSupplier> rows = suppliers.rows(made.id());
        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().preferred());
        assertEquals(YIWU, rows.getFirst().supplierId());
        assertEquals(0, new BigDecimal("2.35").compareTo(rows.getFirst().exwPrice()));
        assertEquals("Dome 2 mm", rows.getFirst().supplierNote());
        assertTrue(suppliers.rows(products.create(product("PM-NONE", null, null)).id()).isEmpty());
    }

    @Test @TestTransaction
    void nullPurchaseFieldsKeepWhatIsStoredOnProductAndRow() {
        Product made = products.create(product("PM-KEEP", YIWU, "2.35").withSupplierNote("Keep me"));

        Product updated = products.update(made.id(), made.withPurchaseMirror(
                new PurchaseMirror(null, "Keep me", null, null, null)));

        assertEquals(YIWU, updated.supplierId());
        assertEquals(0, new BigDecimal("2.35").compareTo(updated.exwPrice()));
        assertEquals(Currency.USD, updated.exwCurrency());
        assertEquals("Keep me", updated.supplierNote());
        ProductSupplier row = suppliers.row(made.id(), YIWU).orElseThrow();
        assertEquals(0, new BigDecimal("2.35").compareTo(row.exwPrice()));
        assertEquals("Keep me", row.supplierNote());

        /* The suppliers editor sends supplierId null and the unchanged note: product and row keep it. */
        Product again = products.update(made.id(), updated.withPurchaseMirror(
                new PurchaseMirror(null, "Keep me", updated.exwPrice(), updated.exwCurrency(), null)));
        assertEquals("Keep me", again.supplierNote());
        assertEquals("Keep me", suppliers.row(made.id(), YIWU).orElseThrow().supplierNote());
    }

    @Test @TestTransaction
    void aPutWithoutCurrencyKeepsTheStoredCurrencyInsteadOfUsd() throws Exception {
        Product made = products.create(product("PM-CNY", YIWU, "13.62").withPurchasePrice(
                new BigDecimal("13.62"), Currency.CNY, null));
        ObjectNode body = json.valueToTree(ProductDto.from(made));
        body.putNull("exwCurrency");
        ProductDto dto = json.treeToValue(body, ProductDto.class);

        Product updated = products.update(made.id(), dto.toDomainForUpdate(products.get(made.id())));

        assertEquals(Currency.CNY, updated.exwCurrency());
        assertEquals(Currency.CNY, suppliers.row(made.id(), YIWU).orElseThrow().exwCurrency());
        assertEquals(Currency.USD, json.treeToValue(body, ProductDto.class).toDomain(null).exwCurrency(),
                "a new product still starts in USD");
    }

    @Test @TestTransaction
    void aNewPriceLandsOnTheVoorkeurWhileTheSameValuesInAnotherScaleWriteNothing() {
        Product made = products.create(product("PM-PRICE", YIWU, "2.35"));

        products.update(made.id(), products.get(made.id()).withPurchasePrice(new BigDecimal("2.40"), Currency.USD,
                null));
        ProductSupplier row = suppliers.row(made.id(), YIWU).orElseThrow();
        assertEquals(0, new BigDecimal("2.40").compareTo(row.exwPrice()));

        entities.flush();
        Instant stamped = row.updatedAt();
        long logged = activityCount(made.id());
        products.update(made.id(), products.get(made.id()).withPurchasePrice(new BigDecimal("2.400000"),
                Currency.USD, null));
        entities.flush();
        assertEquals(stamped, suppliers.row(made.id(), YIWU).orElseThrow().updatedAt(), "no row write");
        assertEquals(logged, activityCount(made.id()), "no activity for the same price in another scale");
    }

    @Test @TestTransaction
    void aNewSupplierBecomesVoorkeurAndAnExistingRowKeepsItsOwnPriceAndNote() {
        Product made = products.create(product("PM-SWITCH", YIWU, "2.35").withSupplierNote("Yiwu note"));
        suppliers.add(made.id(), write(CULINAN, "B", null, "2.10", Currency.CNY, "0.15", "Culinan note"), "Culinan");

        /* An old tab picks Culinan and echoes Yiwu's price and note. */
        Product current = products.get(made.id());
        Product updated = products.update(made.id(), current.withPurchaseMirror(new PurchaseMirror(CULINAN,
                current.supplierNote(), current.exwPrice(), current.exwCurrency(), current.extraUnitCost())));

        assertEquals(CULINAN, updated.supplierId());
        assertEquals(0, new BigDecimal("2.10").compareTo(updated.exwPrice()), "Culinan keeps its own price");
        assertEquals(Currency.CNY, updated.exwCurrency());
        assertEquals("Culinan note", updated.supplierNote());
        assertTrue(suppliers.row(made.id(), CULINAN).orElseThrow().preferred());
        assertFalse(suppliers.row(made.id(), YIWU).orElseThrow().preferred());

        /* A supplier the product had no row of: its row is made from the PUT, as Voorkeur. */
        Product third = products.update(made.id(), updated.withPurchaseMirror(new PurchaseMirror(PUBEI, null,
                new BigDecimal("1.99"), Currency.USD, null)));
        assertEquals(PUBEI, third.supplierId());
        ProductSupplier pubei = suppliers.row(made.id(), PUBEI).orElseThrow();
        assertTrue(pubei.preferred());
        assertEquals(0, new BigDecimal("1.99").compareTo(pubei.exwPrice()));
        assertEquals(3, suppliers.rows(made.id()).size());
        assertEquals(1, suppliers.rows(made.id()).stream().filter(ProductSupplier::preferred).count());
    }

    @Test @TestTransaction
    void lastWriteWinsWhenAnOldTabSendsTheOldPriceAfterARowEdit() {
        Product made = products.create(product("PM-LAST", YIWU, "2.35"));
        Product oldTab = products.get(made.id());
        ProductSupplier voorkeur = suppliers.rows(made.id()).getFirst();
        suppliers.update(made.id(), voorkeur.id(), write(YIWU, null, null, "2.50", Currency.USD, null, null), "Yiwu");
        assertEquals(0, new BigDecimal("2.50").compareTo(products.get(made.id()).exwPrice()));

        products.update(made.id(), oldTab);

        assertEquals(0, new BigDecimal("2.35").compareTo(suppliers.row(made.id(), YIWU).orElseThrow().exwPrice()));
        assertEquals(0, new BigDecimal("2.35").compareTo(products.get(made.id()).exwPrice()));
    }

    @Test @TestTransaction
    void aPutOnAProductWithoutItsOwnRowHealsItWithTheSameValues() {
        ProductEntity legacy = legacyProduct("PM-HEAL", YIWU, "2.35", "Legacy note");

        Product updated = products.update(legacy.id, products.get(legacy.id));

        ProductSupplier row = suppliers.row(legacy.id, YIWU).orElseThrow();
        assertTrue(row.preferred());
        assertEquals(0, new BigDecimal("2.35").compareTo(row.exwPrice()));
        assertEquals("Legacy note", row.supplierNote());
        assertEquals(0, new BigDecimal("2.35").compareTo(updated.exwPrice()));
        assertEquals("Legacy note", updated.supplierNote());
    }

    @Test @TestTransaction
    void aCopyTakesTheActiveSuppliersAndDeleteRemovesTheRows() {
        Product made = products.create(product("PM-COPY", YIWU, "2.35"));
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, "B", "CUL-1", "2.10", Currency.USD, "0.15",
                "Culinan note"), "Culinan");
        ProductSupplier pubei = suppliers.add(made.id(), write(PUBEI, null, null, "1.50", Currency.USD, null, null),
                "Pubei");
        suppliers.setActive(made.id(), pubei.id(), false, "Pubei");

        Product copy = products.duplicate(made.id(), "Wit");

        List<ProductSupplier> rows = suppliers.rows(copy.id());
        assertEquals(List.of(YIWU, CULINAN), rows.stream().map(ProductSupplier::supplierId).toList(),
                "the inactive supplier stays behind");
        assertTrue(rows.getFirst().preferred());
        ProductSupplier copied = rows.get(1);
        assertFalse(copied.preferred());
        assertEquals("B", copied.versionLabel());
        assertEquals("CUL-1", copied.supplierReference());
        assertEquals(0, culinan.exwPrice().compareTo(copied.exwPrice()));
        assertEquals("Culinan note", copied.supplierNote());

        products.delete(copy.id());
        entities.flush();
        assertEquals(0L, entities.createQuery("select count(s) from ProductSupplierEntity s where s.productId = :id",
                Long.class).setParameter("id", copy.id()).getSingleResult());
        assertEquals(3, suppliers.rows(made.id()).size());
    }

    @Test @TestTransaction
    void aSharedPriceReachesOnlyColoursOfTheSameSupplierAndTheirRow() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "pm-shared-price";
        family.name = "Shared price";
        entities.persist(family);
        entities.flush();
        Product source = products.create(product("PM-SH-RED", YIWU, "2.80")
                .withCanonicalIdentity(family.id, null, null, 0, true));
        Product sameSupplier = products.create(product("PM-SH-WHITE", YIWU, "2.35")
                .withVariantAttributes("Wit", null, null).withCanonicalIdentity(family.id, null, null, 1, true));
        Product otherSupplier = products.create(product("PM-SH-BLACK", CULINAN, "2.10")
                .withVariantAttributes("Zwart", null, null).withCanonicalIdentity(family.id, null, null, 2, true));

        products.applySharedFields(source.id(), family.id, List.of(sameSupplier.id(), otherSupplier.id()),
                Set.of(ProductService.SharedField.PURCHASE_PRICE));

        assertEquals(0, new BigDecimal("2.80").compareTo(products.get(sameSupplier.id()).exwPrice()));
        assertEquals(0, new BigDecimal("2.80").compareTo(
                suppliers.row(sameSupplier.id(), YIWU).orElseThrow().exwPrice()));
        assertEquals(0, new BigDecimal("2.10").compareTo(products.get(otherSupplier.id()).exwPrice()));
        assertEquals(0, new BigDecimal("2.10").compareTo(
                suppliers.row(otherSupplier.id(), CULINAN).orElseThrow().exwPrice()));
    }

    private ProductEntity legacyProduct(String sku, long supplierId, String exw, String note) {
        ProductEntity legacy = new ProductEntity();
        legacy.sku = sku;
        legacy.name = sku;
        legacy.active = true;
        legacy.piecesPerCarton = 1;
        legacy.supplierId = supplierId;
        legacy.exwPrice = new BigDecimal(exw);
        legacy.exwCurrency = Currency.USD;
        legacy.supplierNote = note;
        entities.persist(legacy);
        entities.flush();
        assertTrue(entities.createQuery("from ProductSupplierEntity s where s.productId = :id",
                ProductSupplierEntity.class).setParameter("id", legacy.id).getResultList().isEmpty());
        return legacy;
    }

    private long activityCount(long productId) {
        entities.flush();
        return entities.createQuery("select count(a) from ActivityLogEntity a where a.entityType = 'PRODUCT' "
                + "and a.entityId = :id", Long.class).setParameter("id", String.valueOf(productId)).getSingleResult();
    }
}
