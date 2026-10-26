package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierAgreementPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierEntity;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suppliers of a product as the row sheet writes them, and the product card that mirrors the Voorkeur. */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductSupplierServicePersistenceTest {

    private static final long YIWU = 9101L;
    private static final long CULINAN = 9102L;
    private static final long PUBEI = 9103L;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 0, 0, 0};
    private static final byte[] OTHER_PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2, 0, 0, 0};

    @Inject ProductService products;
    @Inject ProductSupplierService suppliers;
    @Inject ProductSupplierAgreementPhotoService photos;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void theFirstRowIsTheVoorkeurAndWritesTheProductCard() {
        Product bare = products.create(product("PS-FIRST", null, null));
        assertTrue(suppliers.rows(bare.id()).isEmpty());

        ProductSupplier first = suppliers.add(bare.id(), write(YIWU, "A · stolp 2 mm", "PB-1530", "2.35",
                Currency.USD, "0.10", "Dome 2 mm"), "Yiwu");

        assertTrue(first.preferred());
        assertTrue(first.active());
        Product card = products.get(bare.id());
        assertEquals(YIWU, card.supplierId());
        assertEquals(0, new BigDecimal("2.35").compareTo(card.exwPrice()));
        assertEquals(Currency.USD, card.exwCurrency());
        assertEquals(0, new BigDecimal("0.10").compareTo(card.extraUnitCost()));
        assertEquals("Dome 2 mm", card.supplierNote());

        ProductSupplier second = suppliers.add(bare.id(), write(CULINAN, "B · dikker glas", "CUL-GD-10", "2.10",
                Currency.CNY, "0.15", "Glass 3 mm"), "Culinan");
        assertFalse(second.preferred());
        Product unchanged = products.get(bare.id());
        assertEquals(YIWU, unchanged.supplierId(), "a second row leaves the card alone");
        assertEquals(0, new BigDecimal("2.35").compareTo(unchanged.exwPrice()));
        assertEquals("Dome 2 mm", unchanged.supplierNote());
        assertEquals(List.of(YIWU, CULINAN), suppliers.rows(bare.id()).stream().map(ProductSupplier::supplierId).toList());

        assertEquals(List.of("Leverancier toegevoegd: Yiwu", "Leverancier toegevoegd: Culinan"),
                summaries(bare.id(), "Leverancier toegevoegd"));
    }

    @Test @TestTransaction
    void editingTheVoorkeurMovesTheCardButNeverItsPublication() {
        Product made = products.create(product("PS-LIVE", YIWU, "2.35"));
        ProductEntity row = entities.find(ProductEntity.class, made.id());
        row.websiteStatus = PublicationState.PUBLISHED;
        entities.flush();
        List<String> openPoints = products.get(made.id()).publicationIssues();
        assertFalse(openPoints.isEmpty(), "the live product has open publication points");
        ProductSupplier voorkeur = suppliers.rows(made.id()).getFirst();

        ProductSupplier edited = suppliers.update(made.id(), voorkeur.id(), write(YIWU, null, null, "2.50",
                Currency.USD, null, "New agreement"), "Yiwu");

        Product card = products.get(made.id());
        assertEquals(0, new BigDecimal("2.50").compareTo(card.exwPrice()));
        assertNull(card.extraUnitCost());
        assertEquals("New agreement", card.supplierNote());
        assertEquals(PublicationState.PUBLISHED, card.publicationState(CatalogChannel.WEBSITE));
        assertEquals(openPoints, card.publicationIssues());
        assertEquals(0, new BigDecimal("2.50").compareTo(edited.exwPrice()));

        ProductSupplier other = suppliers.add(made.id(), write(CULINAN, null, null, "2.10", Currency.USD, null,
                null), "Culinan");
        suppliers.update(made.id(), other.id(), write(CULINAN, "B", null, "1.90", Currency.USD, null, null),
                "Culinan");
        assertEquals(0, new BigDecimal("2.50").compareTo(products.get(made.id()).exwPrice()),
                "another supplier's row never moves the card");
    }

    @Test @TestTransaction
    void makingAnotherSupplierVoorkeurSwitchesTheCardIncludingTheNote() {
        Product made = products.create(product("PS-SWITCH", YIWU, "2.35").withSupplierNote("Yiwu note"));
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, "B", "CUL-1", "2.10", Currency.CNY, "0.15",
                "Culinan note"), "Culinan");

        ProductSupplier promoted = suppliers.setPreferred(made.id(), culinan.id(), "Culinan");

        assertTrue(promoted.preferred());
        Product card = products.get(made.id());
        assertEquals(CULINAN, card.supplierId());
        assertEquals(0, new BigDecimal("2.10").compareTo(card.exwPrice()));
        assertEquals(Currency.CNY, card.exwCurrency());
        assertEquals(0, new BigDecimal("0.15").compareTo(card.extraUnitCost()));
        assertEquals("Culinan note", card.supplierNote());
        assertFalse(suppliers.row(made.id(), YIWU).orElseThrow().preferred());
        assertEquals(1, suppliers.rows(made.id()).stream().filter(ProductSupplier::preferred).count());
        assertEquals(List.of("Voorkeursleverancier: Culinan"), summaries(made.id(), "Voorkeursleverancier"));
        ActivityLogEntity entry = activity(made.id(), "Voorkeursleverancier").getFirst();
        assertTrue(entry.changesJson.contains("\"field\":\"supplierId\""), entry.changesJson);
        assertTrue(entry.changesJson.contains(String.valueOf(CULINAN)), entry.changesJson);
    }

    @Test @TestTransaction
    void theVoorkeurStaysActiveAndAnInactiveRowCannotBecomeVoorkeur() {
        Product made = products.create(product("PS-GUARDS", YIWU, "2.35"));
        ProductSupplier voorkeur = suppliers.rows(made.id()).getFirst();
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, null, null, "2.10", Currency.USD, null,
                null), "Culinan");

        assertRefused("Maak eerst een andere leverancier voorkeur.",
                () -> suppliers.setActive(made.id(), voorkeur.id(), false, "Yiwu"));
        assertRefused("Maak eerst een andere leverancier voorkeur.",
                () -> suppliers.delete(made.id(), voorkeur.id(), "Yiwu"));

        ProductSupplier inactive = suppliers.setActive(made.id(), culinan.id(), false, "Culinan");
        assertFalse(inactive.active());
        assertRefused("Zet deze leverancier eerst weer actief.",
                () -> suppliers.setPreferred(made.id(), culinan.id(), "Culinan"));
        assertTrue(suppliers.setActive(made.id(), culinan.id(), true, "Culinan").active());
        assertEquals(List.of("Leverancier Culinan op inactief", "Leverancier Culinan weer actief"),
                summaries(made.id(), "Leverancier Culinan"));

        assertRefused("Culinan staat al bij dit product",
                () -> suppliers.add(made.id(), write(CULINAN, null, null, "2.00", Currency.USD, null, null),
                        "Culinan"));
        Product other = products.create(product("PS-OTHER", PUBEI, "1.00"));
        assertThrows(NotFoundException.class, () -> suppliers.update(other.id(), culinan.id(),
                write(CULINAN, null, null, "1.00", Currency.USD, null, null), "Culinan"),
                "a row of another product is not found");
    }

    @Test @TestTransaction
    void aStaleSheetIsRefusedWhileTwoSavesInARowEachEchoingTheLastAnswerSucceed() {
        Product made = products.create(product("PS-STALE", YIWU, "2.35"));
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, null, null, "2.10", Currency.USD, null,
                null), "Culinan");
        Instant loaded = culinan.updatedAt();
        assertEquals(loaded, ProductSupplierRules.stamp(loaded), "the answer carries micro-truncated instants");

        entities.flush();
        entities.clear();
        ProductSupplier first = suppliers.update(made.id(), culinan.id(), writeAt(CULINAN, "2.20", loaded), "Culinan");
        entities.flush();
        entities.clear();
        ProductSupplier second = suppliers.update(made.id(), culinan.id(), writeAt(CULINAN, "2.30",
                first.updatedAt()), "Culinan");
        assertEquals(0, new BigDecimal("2.30").compareTo(second.exwPrice()));

        entities.flush();
        entities.clear();
        assertRefused("Deze leverancier is intussen gewijzigd; laad het product opnieuw.",
                () -> suppliers.update(made.id(), culinan.id(), writeAt(CULINAN, "2.40", loaded.minusSeconds(60)),
                        "Culinan"));
    }

    @Test @TestTransaction
    void aNoteGoesOnARowWithoutPriceAndAnEmptyNoteClearsIt() {
        Product made = products.create(product("PS-NOTE", YIWU, "2.35"));
        /* A history row from the migration: inactive, no price yet. */
        ProductSupplierEntity history = new ProductSupplierEntity();
        history.productId = made.id();
        history.supplierId = PUBEI;
        history.active = false;
        history.createdAt = Instant.now();
        history.updatedAt = Instant.now();
        entities.persist(history);
        entities.flush();

        ProductSupplier noted = suppliers.updateNote(made.id(), history.id, "  Old mould, 2 mm  ", null, "Pubei");
        assertEquals("Old mould, 2 mm", noted.supplierNote());
        assertNull(noted.exwPrice());
        assertEquals("Old mould, 2 mm", suppliers.updateNote(made.id(), history.id, null, null, "Pubei").supplierNote(),
                "no note keeps it");
        assertNull(suppliers.updateNote(made.id(), history.id, "", noted.updatedAt(), "Pubei").supplierNote());
        assertRefused("Leveranciersnotitie mag maximaal 4000 tekens hebben",
                () -> suppliers.updateNote(made.id(), history.id, "x".repeat(4001), null, "Pubei"));
        assertEquals("Vul de EXW-prijs in", assertThrows(BusinessRuleException.class,
                () -> suppliers.update(made.id(), history.id, write(PUBEI, null, null, null, Currency.USD, null,
                        "x"), "Pubei")).getMessage(), "the sheet still needs a price");
    }

    @Test @TestTransaction
    void eachSheetRefusalComesBackWithItsMessage() {
        Product made = products.create(product("PS-REFUSALS", YIWU, "2.35"));
        ProductSupplier voorkeur = suppliers.rows(made.id()).getFirst();
        assertRefused("Kies een leverancier",
                () -> suppliers.add(made.id(), write(null, null, null, "1", Currency.USD, null, null), null));
        assertRefused("Kies de munt",
                () -> suppliers.update(made.id(), voorkeur.id(), write(YIWU, null, null, "1", null, null, null), "Yiwu"));
        assertRefused("EXW-prijs kan niet negatief zijn",
                () -> suppliers.update(made.id(), voorkeur.id(), write(YIWU, null, null, "-1", Currency.USD, null,
                        null), "Yiwu"));
        assertRefused("Extra kost kan niet negatief zijn",
                () -> suppliers.update(made.id(), voorkeur.id(), write(YIWU, null, null, "1", Currency.USD, "-1",
                        null), "Yiwu"));
        assertRefused("MOQ moet minstens 1 stuk zijn", () -> suppliers.update(made.id(), voorkeur.id(),
                new ProductSupplierWrite(YIWU, null, null, BigDecimal.ONE, Currency.USD, null, 0, null, null, null),
                "Yiwu"));
        assertRefused("Levertijd kan niet negatief zijn", () -> suppliers.update(made.id(), voorkeur.id(),
                new ProductSupplierWrite(YIWU, null, null, BigDecimal.ONE, Currency.USD, null, null, -1, null, null),
                "Yiwu"));
        assertRefused("Versie mag maximaal 60 tekens hebben", () -> suppliers.update(made.id(), voorkeur.id(),
                write(YIWU, "v".repeat(61), null, "1", Currency.USD, null, null), "Yiwu"));
        assertRefused("Referentie mag maximaal 120 tekens hebben", () -> suppliers.update(made.id(), voorkeur.id(),
                write(YIWU, null, "r".repeat(121), "1", Currency.USD, null, null), "Yiwu"));
    }

    @Test @TestTransaction
    void deletingARowRemovesThatSuppliersAgreementPhotosOnly() {
        Product made = products.create(product("PS-DELETE", YIWU, "2.35"));
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, null, null, "2.10", Currency.USD, null,
                null), "Culinan");
        var own = photos.upload(made.id(), "yiwu.png", new ByteArrayInputStream(PNG), "Yiwu sample");
        var theirs = photos.upload(made.id(), CULINAN, "culinan.png", new ByteArrayInputStream(OTHER_PNG),
                "Culinan sample");

        suppliers.delete(made.id(), culinan.id(), "Culinan");

        assertTrue(suppliers.row(made.id(), CULINAN).isEmpty());
        assertNull(entities.find(ProductSupplierAgreementPhotoEntity.class, theirs.id()));
        assertNotNull(entities.find(ProductSupplierAgreementPhotoEntity.class, own.id()));
        assertEquals(List.of("Leverancier verwijderd: Culinan"), summaries(made.id(), "Leverancier verwijderd"));
    }

    @Test @TestTransaction
    void anEditLogsEachChangedFieldAndTheNoteOnlyAsChanged() {
        Product made = products.create(product("PS-ACTIVITY", YIWU, "2.35"));
        ProductSupplier culinan = suppliers.add(made.id(), write(CULINAN, null, null, "2.10", Currency.USD, null,
                null), "Culinan");
        suppliers.update(made.id(), culinan.id(), write(CULINAN, "B · dikker glas", "CUL-GD-10", "2.100000",
                Currency.USD, null, "Secret agreement"), "Culinan");

        ActivityLogEntity entry = activity(made.id(), "Leverancier Culinan bijgewerkt").getFirst();
        assertTrue(entry.changesJson.contains("\"label\":\"Versie\""), entry.changesJson);
        assertTrue(entry.changesJson.contains("\"label\":\"Referentie leverancier\""), entry.changesJson);
        assertFalse(entry.changesJson.contains("EXW-prijs"), "2.10 and 2.100000 are the same price");
        assertTrue(entry.changesJson.contains("Leveranciersnotitie"), entry.changesJson);
        assertFalse(entry.changesJson.contains("Secret agreement"), "the note itself stays out of the log");
    }

    @Test @TestTransaction
    void aLegacyProductWithoutRowsKeepsItsOwnSupplierAsVoorkeurWhenASecondIsAdded() {
        ProductEntity legacy = new ProductEntity();
        legacy.sku = "PS-LEGACY";
        legacy.name = "Legacy dome";
        legacy.active = true;
        legacy.piecesPerCarton = 1;
        legacy.supplierId = YIWU;
        legacy.exwPrice = new BigDecimal("2.35");
        legacy.exwCurrency = Currency.USD;
        legacy.supplierNote = "Legacy note";
        entities.persist(legacy);
        entities.flush();

        suppliers.add(legacy.id, write(CULINAN, null, null, "2.10", Currency.USD, null, null), "Culinan");

        ProductSupplier yiwu = suppliers.row(legacy.id, YIWU).orElseThrow();
        assertTrue(yiwu.preferred());
        assertEquals(0, new BigDecimal("2.35").compareTo(yiwu.exwPrice()));
        assertEquals("Legacy note", yiwu.supplierNote());
        assertFalse(suppliers.row(legacy.id, CULINAN).orElseThrow().preferred());
        assertEquals(YIWU, products.get(legacy.id).supplierId());
    }

    private List<String> summaries(long productId, String prefix) {
        return activity(productId, prefix).stream().map(entry -> entry.summary).toList();
    }

    private List<ActivityLogEntity> activity(long productId, String prefix) {
        entities.flush();
        return entities.createQuery("from ActivityLogEntity a where a.entityType = 'PRODUCT' and a.entityId = :id "
                        + "and a.summary like :prefix order by a.id", ActivityLogEntity.class)
                .setParameter("id", String.valueOf(productId))
                .setParameter("prefix", prefix + "%")
                .getResultList();
    }

    private static void assertRefused(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    static ProductSupplierWrite write(Long supplierId, String label, String reference, String exw, Currency currency,
                                      String extra, String note) {
        return new ProductSupplierWrite(supplierId, label, reference, exw == null ? null : new BigDecimal(exw),
                currency, extra == null ? null : new BigDecimal(extra), null, null, note, null);
    }

    private static ProductSupplierWrite writeAt(long supplierId, String exw, Instant expected) {
        return new ProductSupplierWrite(supplierId, null, null, new BigDecimal(exw), Currency.USD, null, null, null,
                null, expected);
    }

    static Product product(String sku, Long supplierId, String exw) {
        return new Product(null, sku, "Glass dome " + sku, Dimensions.empty(), "Rood", null, null, supplierId,
                true, Barcodes.none(), null, Carton.empty(),
                exw == null ? null : new BigDecimal(exw), Currency.USD, null,
                null, null, null, null, 0, List.of(), List.of());
    }
}
