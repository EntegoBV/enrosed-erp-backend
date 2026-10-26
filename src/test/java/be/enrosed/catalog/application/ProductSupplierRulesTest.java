package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared row rules, on a fake repository that records its calls. PostgreSQL checks the one-preferred index per
 * row, immediately (H2 has no partial index), so the call order of {@code promote} is pinned here.
 */
class ProductSupplierRulesTest {

    private static final long A = 10L;
    private static final long B = 20L;
    private static final Instant NOW = Instant.parse("2026-10-25T20:00:00Z");

    @Test
    void promoteClearsTheFlagsThenSavesThePreferredRowThenFlushes() {
        FakeRows rows = new FakeRows();
        rows.put(new ProductSupplier(null, 1L, A, true, true, null, null, price("2.35"), Currency.USD, null,
                null, null, null, null, null));
        ProductSupplier atB = rows.put(row(1L, B, false, false, "2.10"));
        rows.calls.clear();

        ProductSupplier promoted = ProductSupplierRules.promote(rows, atB, NOW);

        assertEquals(List.of("clearPreferred(1)", "save(" + B + ",preferred=true,active=true)", "flush"), rows.calls);
        assertTrue(promoted.preferred());
        assertTrue(promoted.active(), "the Voorkeur is always active");
        assertEquals(1, rows.byProduct(1L).stream().filter(ProductSupplier::preferred).count());
    }

    @Test
    void ensureOwnRowInsertsTheRowOfAProductWithoutRowsThroughPromote() {
        FakeRows rows = new FakeRows();
        Product product = ProductSupplierOffersTest.product(1L, A, "2.35").withSupplierNote("Dome 2 mm");

        ProductSupplier own = ProductSupplierRules.ensureOwnRow(rows, product, NOW).orElseThrow();

        assertEquals(List.of("find(1," + A + ")", "clearPreferred(1)", "save(" + A + ",preferred=true,active=true)",
                "flush"), rows.calls);
        assertTrue(own.preferred());
        assertEquals(0, price("2.35").compareTo(own.exwPrice()));
        assertEquals(Currency.USD, own.exwCurrency());
        assertEquals("Dome 2 mm", own.supplierNote());
    }

    @Test
    void ensureOwnRowWritesNothingWhenTheOwnRowIsPreferredOrTheProductHasNoSupplier() {
        FakeRows rows = new FakeRows();
        rows.put(row(1L, A, true, true, "9.99"));
        rows.calls.clear();

        ProductSupplierRules.ensureOwnRow(rows, ProductSupplierOffersTest.product(1L, A, "2.35"), NOW);
        assertEquals(List.of("find(1," + A + ")"), rows.calls, "the card does not overwrite its own Voorkeur");

        rows.calls.clear();
        assertTrue(ProductSupplierRules.ensureOwnRow(rows, ProductSupplierOffersTest.product(2L, null, "2.35"), NOW)
                .isEmpty());
        assertTrue(rows.calls.isEmpty());
    }

    @Test
    void ensureOwnRowPromotesTheOwnRowWithTheCardValuesWhenAnotherSupplierIsPreferred() {
        FakeRows rows = new FakeRows();
        rows.put(row(1L, B, true, true, "2.10"));
        rows.put(row(1L, A, false, false, "1.00"));
        rows.calls.clear();
        Product product = ProductSupplierOffersTest.product(1L, A, "2.35").withSupplierNote("Card note");

        ProductSupplier own = ProductSupplierRules.ensureOwnRow(rows, product, NOW).orElseThrow();

        assertEquals(A, own.supplierId());
        assertTrue(own.preferred());
        assertTrue(own.active());
        assertEquals(0, price("2.35").compareTo(own.exwPrice()), "the product card wins");
        assertEquals("Card note", own.supplierNote());
        assertFalse(rows.find(1L, B).orElseThrow().preferred());
        assertEquals("clearPreferred(1)", rows.calls.get(1));
    }

    @Test
    void aSameSupplierEditWritesOnlyTheChangedPrice() {
        FakeRows rows = new FakeRows();
        rows.put(new ProductSupplier(null, 1L, A, true, true, "A", "PB-1", price("2.35"), Currency.USD,
                price("0.10"), 600, 40, "Note", null, null));
        rows.calls.clear();
        Product current = ProductSupplierOffersTest.product(1L, A, "2.35").withSupplierNote("Note");
        PurchaseMirror before = PurchaseMirror.of(current);
        PurchaseMirror after = new PurchaseMirror(A, "Note", price("2.50"), Currency.USD, price("0.10"));

        ProductSupplier saved = ProductSupplierRules.applyProductEdit(rows, current, before, after, NOW);

        assertEquals(0, price("2.50").compareTo(saved.exwPrice()));
        assertEquals("A", saved.versionLabel());
        assertEquals("PB-1", saved.supplierReference());
        assertEquals(600, saved.moq());
        assertEquals("Note", saved.supplierNote());
        assertEquals(List.of("find(1," + A + ")", "find(1," + A + ")", "save(" + A + ",preferred=true,active=true)"),
                rows.calls);
    }

    @Test
    void equalValuesByCompareToWriteNothing() {
        FakeRows rows = new FakeRows();
        rows.put(new ProductSupplier(null, 1L, A, true, true, null, null, new BigDecimal("2.350000"), Currency.USD,
                new BigDecimal("0.100000"), null, null, null, null, null));
        rows.calls.clear();
        Product current = ProductSupplierOffersTest.product(1L, A, "2.350000");
        PurchaseMirror before = PurchaseMirror.of(current);
        PurchaseMirror after = new PurchaseMirror(A, null, new BigDecimal("2.35"), Currency.USD,
                new BigDecimal("0.1"));

        assertTrue(after.sameAs(before));
        ProductSupplierRules.applyProductEdit(rows, current, before, after, NOW);

        assertTrue(rows.calls.stream().noneMatch(call -> call.startsWith("save") || call.startsWith("clear")),
                rows.calls.toString());
    }

    @Test
    void aSupplierSwitchToAnExistingRowKeepsItsOwnPriceAndNoteUnlessChangedThenPromotesIt() {
        FakeRows rows = new FakeRows();
        rows.put(new ProductSupplier(null, 1L, A, true, true, null, null, price("2.35"), Currency.USD, null,
                null, null, "A note", null, null));
        rows.put(new ProductSupplier(null, 1L, B, false, true, "B", null, price("2.10"), Currency.CNY, price("0.15"),
                null, null, "B note", null, null));
        Product current = ProductSupplierOffersTest.product(1L, A, "2.35").withSupplierNote("A note");
        PurchaseMirror before = PurchaseMirror.of(current);
        /* An old tab picks B and echoes A's values; the PUT drops an echoed note on a supplier change. */
        PurchaseMirror after = new PurchaseMirror(B, null, before.exwPrice(), before.exwCurrency(),
                before.extraUnitCost());

        ProductSupplier preferred = ProductSupplierRules.applyProductEdit(rows, current, before, after, NOW);

        assertEquals(B, preferred.supplierId());
        assertTrue(preferred.preferred());
        assertEquals(0, price("2.10").compareTo(preferred.exwPrice()), "B keeps its own price");
        assertEquals(Currency.CNY, preferred.exwCurrency());
        assertEquals(0, price("0.15").compareTo(preferred.extraUnitCost()));
        assertEquals("B note", preferred.supplierNote());
        assertFalse(rows.find(1L, A).orElseThrow().preferred());

        /* Switching back with a price the tab changed: that price lands on A's row. */
        Product onB = current.withPurchaseMirror(PurchaseMirror.of(preferred));
        PurchaseMirror backBefore = PurchaseMirror.of(onB);
        PurchaseMirror backAfter = new PurchaseMirror(A, null, price("2.40"), backBefore.exwCurrency(),
                backBefore.extraUnitCost());
        ProductSupplier back = ProductSupplierRules.applyProductEdit(rows, onB, backBefore, backAfter, NOW);
        assertEquals(A, back.supplierId());
        assertEquals(0, price("2.40").compareTo(back.exwPrice()));
        assertEquals(Currency.USD, back.exwCurrency(), "an unchanged currency keeps A's own");
        assertEquals("A note", back.supplierNote());
    }

    @Test
    void aSupplierSwitchToAHistoryRowFillsWhatThatRowNeverKnewFromTheEdit() {
        FakeRows rows = new FakeRows();
        rows.put(new ProductSupplier(null, 1L, A, true, true, null, null, price("2.35"), Currency.USD, price("0.10"),
                null, null, null, null, null));
        /* The migration's step 2: bought from B in 2024, inactive, priced as last ordered, no extra cost, no note. */
        rows.put(new ProductSupplier(null, 1L, B, false, false, null, null, price("1.80"), Currency.USD, null,
                null, null, null, null, null));
        Product current = ProductSupplierOffersTest.product(1L, A, "2.35").withPurchasePrice(price("2.35"),
                Currency.USD, price("0.10"));
        PurchaseMirror before = PurchaseMirror.of(current);
        PurchaseMirror after = new PurchaseMirror(B, null, before.exwPrice(), before.exwCurrency(),
                before.extraUnitCost());

        ProductSupplier preferred = ProductSupplierRules.applyProductEdit(rows, current, before, after, NOW);

        assertEquals(B, preferred.supplierId());
        assertTrue(preferred.preferred());
        assertTrue(preferred.active());
        assertEquals(0, price("1.80").compareTo(preferred.exwPrice()), "B keeps the price it was bought at");
        assertEquals(0, price("0.10").compareTo(preferred.extraUnitCost()),
                "the card's extra cost is not lost: the history row never had one");

        /* A row without any price takes the edit's pair whole. */
        FakeRows priceless = new FakeRows();
        priceless.put(new ProductSupplier(null, 2L, A, true, true, null, null, price("2.10"), Currency.EUR,
                price("0.20"), null, null, null, null, null));
        priceless.put(new ProductSupplier(null, 2L, B, false, true, null, null, null, null, price("0.05"),
                null, null, null, null, null));
        Product second = ProductSupplierOffersTest.product(2L, A, "2.10").withPurchasePrice(price("2.10"),
                Currency.EUR, price("0.20"));
        PurchaseMirror secondBefore = PurchaseMirror.of(second);
        ProductSupplier onB = ProductSupplierRules.applyProductEdit(priceless, second, secondBefore,
                new PurchaseMirror(B, null, secondBefore.exwPrice(), secondBefore.exwCurrency(),
                        secondBefore.extraUnitCost()), NOW);
        assertEquals(0, price("2.10").compareTo(onB.exwPrice()));
        assertEquals(Currency.EUR, onB.exwCurrency());
        assertEquals(0, price("0.05").compareTo(onB.extraUnitCost()), "a row's own extra cost stays");
    }

    @Test
    void aSupplierSwitchToANewSupplierCreatesItsRowFromTheEditAsVoorkeur() {
        FakeRows rows = new FakeRows();
        Product current = ProductSupplierOffersTest.product(1L, A, "2.35");
        PurchaseMirror before = PurchaseMirror.of(current);
        PurchaseMirror after = new PurchaseMirror(B, "New note", price("3.00"), Currency.EUR, null);

        ProductSupplier created = ProductSupplierRules.applyProductEdit(rows, current, before, after, NOW);

        assertEquals(B, created.supplierId());
        assertTrue(created.preferred());
        assertEquals(0, price("3.00").compareTo(created.exwPrice()));
        assertEquals(Currency.EUR, created.exwCurrency());
        assertNull(created.extraUnitCost());
        assertEquals("New note", created.supplierNote());
        assertEquals(2, rows.byProduct(1L).size(), "A got its row first, then B's was added");
        assertFalse(rows.find(1L, A).orElseThrow().preferred());
    }

    @Test
    void instantsAreKeptAtMicroseconds() {
        assertEquals(Instant.ofEpochSecond(1, 123456000), ProductSupplierRules.stamp(Instant.ofEpochSecond(1, 123456789)));
        assertTrue(ProductSupplierRules.sameMoment(Instant.ofEpochSecond(1, 123456789),
                Instant.ofEpochSecond(1, 123456000)));
        assertFalse(ProductSupplierRules.sameMoment(Instant.ofEpochSecond(1, 123456000),
                Instant.ofEpochSecond(1, 123457000)));
    }

    @Test
    void normaliseTrimsAndValidateNamesEachRefusal() {
        ProductSupplierWrite w = ProductSupplierRules.normalise(new ProductSupplierWrite(B, "  B  ", "   ",
                price("2.10"), Currency.USD, null, null, null, "   ", null));
        assertEquals("B", w.versionLabel());
        assertNull(w.supplierReference());
        assertEquals("", w.supplierNote(), "a blank note is the explicit clear");
        assertNull(ProductSupplierRules.normalise(new ProductSupplierWrite(B, null, null, null, null, null, null,
                null, null, null)).supplierNote(), "no note keeps the stored one");

        assertRefused("Kies een leverancier", new ProductSupplierWrite(null, null, null, price("1"), Currency.USD,
                null, null, null, null, null), true);
        assertRefused("Vul de EXW-prijs in", write(null, Currency.USD, null, null, null), false);
        assertRefused("EXW-prijs kan niet negatief zijn", write(price("-1"), Currency.USD, null, null, null), false);
        assertRefused("Kies de munt", write(price("1"), null, null, null, null), false);
        assertRefused("Extra kost kan niet negatief zijn", write(price("1"), Currency.USD, price("-0.01"), null, null),
                false);
        assertRefused("MOQ moet minstens 1 stuk zijn", write(price("1"), Currency.USD, null, 0, null), false);
        assertRefused("Levertijd kan niet negatief zijn", write(price("1"), Currency.USD, null, null, -1), false);
        assertRefused("Versie mag maximaal 60 tekens hebben", new ProductSupplierWrite(B, "x".repeat(61), null,
                price("1"), Currency.USD, null, null, null, null, null), false);
        assertRefused("Referentie mag maximaal 120 tekens hebben", new ProductSupplierWrite(B, null, "x".repeat(121),
                price("1"), Currency.USD, null, null, null, null, null), false);
        assertRefused("Leveranciersnotitie mag maximaal 4000 tekens hebben", new ProductSupplierWrite(B, null, null,
                price("1"), Currency.USD, null, null, null, "x".repeat(4001), null), false);
        ProductSupplierRules.validate(write(price("0"), Currency.CNY, price("0"), 1, 0), false);
    }

    private static void assertRefused(String message, ProductSupplierWrite write, boolean adding) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> ProductSupplierRules.validate(write, adding));
        assertEquals(message, refused.getMessage());
    }

    private static ProductSupplierWrite write(BigDecimal exw, Currency currency, BigDecimal extra, Integer moq,
                                              Integer lead) {
        return new ProductSupplierWrite(B, null, null, exw, currency, extra, moq, lead, null, null);
    }

    private static ProductSupplier row(long productId, long supplierId, boolean preferred, boolean active,
                                       String exw) {
        return new ProductSupplier(null, productId, supplierId, preferred, active, null, null, price(exw),
                Currency.USD, null, null, null, null, null, null);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    /** In memory, recording each call that matters for the flush order. */
    static final class FakeRows implements ProductSupplierRepository {
        final List<String> calls = new ArrayList<>();
        private final Map<Long, ProductSupplier> rows = new LinkedHashMap<>();
        private long next = 1;

        ProductSupplier put(ProductSupplier row) {
            ProductSupplier stored = row.withId(next++);
            rows.put(stored.id(), stored);
            return stored;
        }

        List<ProductSupplier> byProduct(long productId) {
            return rows.values().stream().filter(row -> row.productId() == productId).toList();
        }

        @Override
        public List<ProductSupplier> findByProduct(long productId) {
            return byProduct(productId).stream()
                    .sorted(Comparator.comparing(ProductSupplier::preferred).reversed()
                            .thenComparing(Comparator.comparing(ProductSupplier::active).reversed())
                            .thenComparing(ProductSupplier::id))
                    .toList();
        }

        @Override
        public List<ProductSupplier> findBySupplier(long supplierId) {
            return rows.values().stream().filter(row -> row.supplierId() == supplierId).toList();
        }

        @Override
        public Optional<ProductSupplier> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<ProductSupplier> find(long productId, long supplierId) {
            calls.add("find(" + productId + "," + supplierId + ")");
            return rows.values().stream()
                    .filter(row -> row.productId() == productId && row.supplierId() == supplierId).findFirst();
        }

        @Override
        public ProductSupplier save(ProductSupplier row) {
            calls.add("save(" + row.supplierId() + ",preferred=" + row.preferred() + ",active=" + row.active() + ")");
            if (row.preferred() && rows.values().stream().anyMatch(other -> other.productId() == row.productId()
                    && other.preferred() && !other.id().equals(row.id()))) {
                throw new IllegalStateException("uq_product_supplier_preferred: two preferred rows");
            }
            if (row.id() == null) return put(row);
            rows.put(row.id(), row);
            return row;
        }

        @Override
        public void clearPreferred(long productId) {
            calls.add("clearPreferred(" + productId + ")");
            rows.replaceAll((id, row) -> row.productId() == productId ? row.withPreferred(false) : row);
        }

        @Override
        public void flush() {
            calls.add("flush");
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
        }

        @Override
        public void deleteByProduct(long productId) {
            rows.values().removeIf(row -> row.productId() == productId);
        }
    }
}
