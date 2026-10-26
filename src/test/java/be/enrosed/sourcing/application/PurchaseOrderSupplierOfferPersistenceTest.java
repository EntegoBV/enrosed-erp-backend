package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.application.ProductSupplierWrite;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PriceBasis;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purchase orders on a product with two suppliers ("versies", 2026-10-25): a container of B names B's row, prices at
 * B's price, freezes B's terms when placed, and never drifts when B's row or the product card changes later.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class PurchaseOrderSupplierOfferPersistenceTest {

    private static final BigDecimal CNY_USD = new BigDecimal("0.14");
    private static final BigDecimal USD_EUR = new BigDecimal("0.90");

    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject ProductService products;
    @Inject ProductSupplierService productSuppliers;
    @Inject ProductCostHistoryService history;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void aConceptOfBNamesBsRowLiveAndCalculatesAtBsPrice() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", "0.10");
        ProductSupplier bRow = addRow(dome, b, "B · dikker glas", "CUL-GD-10", "2.10", "0.15");

        PurchaseOrder concept = order(b, PurchaseOrderStatus.CONCEPT, line(dome, 100));
        PurchaseOrderLine saved = concept.lines().getFirst();

        assertEquals(bRow.id(), saved.productSupplierId());
        assertEquals("B · dikker glas", saved.supplierVersionLabel());
        assertEquals("CUL-GD-10", saved.supplierReference());
        assertNull(saved.exwPrice(), "a concept follows the row live");
        assertNull(saved.extraUnitCost());
        LandedCost.Line costed = purchases.calculate(concept).lines().getFirst();
        assertEquals(0, new BigDecimal("2.25").multiply(new BigDecimal("100")).compareTo(costed.goodsUsd()),
                "100 x (B's 2.10 + B's 0.15), not A's 2.35 + 0.10");

        PurchaseOrder ofA = order(a, PurchaseOrderStatus.CONCEPT, line(dome, 100));
        assertEquals(0, new BigDecimal("245.00").compareTo(purchases.calculate(ofA).lines().getFirst().goodsUsd()),
                "A's container still prices at A's row");
        assertEquals(productSuppliers.row(dome.id(), a.id()).orElseThrow().id(),
                ofA.lines().getFirst().productSupplierId());
    }

    @Test @TestTransaction
    void placingFreezesBsTermsAndLaterRowEditsMoveNothing() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", "0.10");
        ProductSupplier bRow = addRow(dome, b, "B · dikker glas", "CUL-GD-10", "2.10", "0.15");
        Product plain = productAt(a, "1.00", null);
        ProductSupplier plainB = addRow(plain, b, null, null, "0.80", null);

        PurchaseOrder concept = order(b, PurchaseOrderStatus.CONCEPT, line(dome, 100), line(plain, 50));
        PurchaseOrder placed = save(concept, PurchaseOrderStatus.BESTELD);
        PurchaseOrderLine domeLine = lineOf(placed, dome);
        PurchaseOrderLine plainLine = lineOf(placed, plain);

        assertEquals(0, new BigDecimal("2.10").compareTo(domeLine.exwPrice()));
        assertEquals(Currency.USD, domeLine.exwCurrency());
        assertEquals(0, new BigDecimal("0.15").compareTo(domeLine.extraUnitCost()));
        assertEquals("B · dikker glas", domeLine.supplierVersionLabel());
        assertEquals("CUL-GD-10", domeLine.supplierReference());
        assertEquals(bRow.id(), domeLine.productSupplierId());
        assertEquals(0, BigDecimal.ZERO.compareTo(plainLine.extraUnitCost()), "no extra on the row: frozen at 0");
        assertEquals(plainB.id(), plainLine.productSupplierId());
        BigDecimal goodsBefore = purchases.calculate(placed).totals().goodsUsd();

        ProductSupplier stored = productSuppliers.row(dome.id(), b.id()).orElseThrow();
        productSuppliers.update(dome.id(), stored.id(), new ProductSupplierWrite(b.id(), "C · ander glas", "CUL-NEW",
                new BigDecimal("3.00"), Currency.USD, new BigDecimal("0.50"), null, null, null,
                stored.updatedAt()), b.name());
        products.update(dome.id(), products.get(dome.id()).withPurchasePrice(new BigDecimal("9.99"),
                Currency.USD, new BigDecimal("1.00")));
        entities.flush();
        entities.clear();

        PurchaseOrder reread = purchases.get(placed.id());
        assertEquals(0, goodsBefore.compareTo(purchases.calculate(reread).totals().goodsUsd()),
                "a placed container never follows a later row or product edit");
        PurchaseOrderLine kept = lineOf(reread, dome);
        assertEquals(0, new BigDecimal("2.10").compareTo(kept.exwPrice()));
        assertEquals(0, new BigDecimal("0.15").compareTo(kept.extraUnitCost()));
        assertEquals("B · dikker glas", kept.supplierVersionLabel());
        assertEquals("CUL-GD-10", kept.supplierReference());

        /* An older client that omits what was frozen gets storage back, not the live row. */
        PurchaseOrder omitted = purchases.update(reread.id(), reread.withReceipt(reread.status(), null, null, false,
                reread.notes(), reread.lines().stream().map(line -> new PurchaseOrderLine(line.id(), line.productId(),
                        line.quantity(), null, null, null, line.orderedQuantity(), line.priceBasis())).toList()))
                .order();
        assertEquals(0, new BigDecimal("2.10").compareTo(lineOf(omitted, dome).exwPrice()));
        assertEquals(0, new BigDecimal("0.15").compareTo(lineOf(omitted, dome).extraUnitCost()));
        assertEquals("B · dikker glas", lineOf(omitted, dome).supplierVersionLabel());
    }

    @Test @TestTransaction
    void theClientNeverSetsTheSupplierLink() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", null);
        ProductSupplier bRow = addRow(dome, b, "B", "REF-B", "2.10", null);
        PurchaseOrder concept = purchases.create(b.id(), CNY_USD, USD_EUR, BigDecimal.TEN);

        PurchaseOrder saved = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null,
                null, false, null, List.of(line(dome, 10).withSupplierLink(999_999L, "FORGED", "FORGED-REF"))))
                .order();

        assertEquals(bRow.id(), saved.lines().getFirst().productSupplierId());
        assertEquals("B", saved.lines().getFirst().supplierVersionLabel());
        assertEquals("REF-B", saved.lines().getFirst().supplierReference());
    }

    @Test @TestTransaction
    void aProductWithoutARowOfBIsRefusedOnBsContainer() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product onlyA = productAt(a, "2.35", null);
        PurchaseOrder concept = purchases.create(b.id(), CNY_USD, USD_EUR, BigDecimal.TEN);

        BusinessRuleException refused = assertThrows(BusinessRuleException.class, () -> purchases.update(concept.id(),
                concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null, List.of(line(onlyA, 10)))));

        assertEquals("Product " + onlyA.sku() + " wordt niet gemaakt door " + b.name()
                + ". Voeg die leverancier eerst toe aan het product.", refused.getMessage());
    }

    @Test @TestTransaction
    void anInactiveRowKeepsItsStoredConceptLineButTakesNoNewLineAndCannotBePlaced() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", null);
        Product other = productAt(a, "1.20", null);
        ProductSupplier bRow = addRow(dome, b, "B", null, "2.10", null);
        ProductSupplier otherB = addRow(other, b, null, null, "1.00", null);
        PurchaseOrder concept = order(b, PurchaseOrderStatus.CONCEPT, line(dome, 10));
        productSuppliers.setActive(dome.id(), bRow.id(), false, b.name());
        productSuppliers.setActive(other.id(), otherB.id(), false, b.name());
        String inactive = b.name() + " staat op inactief. Zet die leverancier weer actief of haal de regel weg.";

        PurchaseOrder stillSaves = purchases.update(concept.id(), concept.withClearance(
                be.enrosed.sourcing.domain.Clearance.BE_CUSTOMS)).order();
        assertEquals(bRow.id(), stillSaves.lines().getFirst().productSupplierId());

        List<PurchaseOrderLine> withNew = new java.util.ArrayList<>(stillSaves.lines());
        withNew.add(line(other, 5));
        BusinessRuleException newLine = assertThrows(BusinessRuleException.class, () -> purchases.update(
                concept.id(), stillSaves.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null, withNew)));
        assertEquals("Product " + other.sku() + ": " + inactive, newLine.getMessage());

        BusinessRuleException placing = assertThrows(BusinessRuleException.class,
                () -> save(stillSaves, PurchaseOrderStatus.BESTELD));
        assertEquals("Product " + dome.sku() + ": " + inactive, placing.getMessage());
    }

    @Test @TestTransaction
    void aLineAddedAfterPlacingIsFrozenAtOnce() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", null);
        Product later = productAt(a, "1.20", "0.05");
        addRow(dome, b, "B", null, "2.10", null);
        ProductSupplier laterB = addRow(later, b, "B2", "REF-B2", "1.10", "0.07");
        PurchaseOrder placed = save(order(b, PurchaseOrderStatus.CONCEPT, line(dome, 10)), PurchaseOrderStatus.BESTELD);

        List<PurchaseOrderLine> lines = new java.util.ArrayList<>(placed.lines());
        lines.add(line(later, 20));
        PurchaseOrder grown = purchases.update(placed.id(), placed.withReceipt(placed.status(), null, null, false,
                placed.notes(), lines)).order();

        PurchaseOrderLine added = lineOf(grown, later);
        assertEquals(0, new BigDecimal("1.10").compareTo(added.exwPrice()));
        assertEquals(Currency.USD, added.exwCurrency());
        assertEquals(0, new BigDecimal("0.07").compareTo(added.extraUnitCost()));
        assertEquals(laterB.id(), added.productSupplierId());
        assertEquals("B2", added.supplierVersionLabel());
        assertEquals("REF-B2", added.supplierReference());
    }

    @Test @TestTransaction
    void receiptValuationAndCopiesKeepTheRowAndACopyRefreshesItsLabels() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", null);
        ProductSupplier bRow = addRow(dome, b, "B · dikker glas", "CUL-GD-10", "2.10", "0.15");
        PurchaseOrder placed = save(order(b, PurchaseOrderStatus.CONCEPT, line(dome, 100)), PurchaseOrderStatus.BESTELD);

        PurchaseOrder received = purchases.receive(placed.id(), new PurchaseOrderService.Receipt(List.of(
                new PurchaseOrderService.ReceivedLine(dome.id(), 96, 2, null, "cracked")), false, null,
                LocalDate.now(ZoneId.of("Europe/Brussels")), null));
        PurchaseOrderLine afterReceipt = received.lines().getFirst();
        assertEquals(bRow.id(), afterReceipt.productSupplierId());
        assertEquals("B · dikker glas", afterReceipt.supplierVersionLabel());
        assertEquals("CUL-GD-10", afterReceipt.supplierReference());
        assertNotNull(afterReceipt.receiptUnitValueEur(), "the receipt value is taken at B's price");
        assertEquals(0, new BigDecimal("2.025").compareTo(afterReceipt.receiptUnitValueEur()),
                "(2.10 + 0.15) x 0.90 per piece: " + afterReceipt.receiptUnitValueEur());

        purchases.setReceiptUnitValue(placed.id(), afterReceipt.id(), new BigDecimal("2.50"));
        PurchaseOrderLine revalued = purchases.get(placed.id()).lines().getFirst();
        assertEquals(bRow.id(), revalued.productSupplierId());
        assertEquals("CUL-GD-10", revalued.supplierReference());

        PurchaseOrder copy = purchases.duplicate(placed.id());
        PurchaseOrderLine copied = copy.lines().getFirst();
        assertEquals(bRow.id(), copied.productSupplierId(), "the copy keeps the row");
        assertNull(copied.supplierVersionLabel(), "labels come back on the copy's first save");
        assertNull(copied.supplierReference());
        PurchaseOrder copySaved = purchases.update(copy.id(), copy).order();
        assertEquals("B · dikker glas", copySaved.lines().getFirst().supplierVersionLabel());
    }

    @Test
    void aLineRebuiltForItsEnrosedKostKeepsItsSupplierLink() {
        PurchaseOrderLine line = new PurchaseOrderLine(1L, 2L, 10, new BigDecimal("2.10"), Currency.USD,
                new BigDecimal("0.15"), 10, PriceBasis.EXW, null, null, null, null, 44L, "B", "REF");

        PurchaseOrderLine shared = line.withExtraShare(new BigDecimal("12.00"));

        assertEquals(44L, shared.productSupplierId());
        assertEquals("B", shared.supplierVersionLabel());
        assertEquals("REF", shared.supplierReference());
        assertEquals(0, new BigDecimal("12.00").compareTo(shared.extraShareEur()));
    }

    @Test @TestTransaction
    void aLegacyProductWithoutRowsStillOrdersAndKeepsItsVoorkeurWhenBIsAdded() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        ProductEntity legacy = new ProductEntity();
        legacy.sku = "LEG-" + UUID.randomUUID();
        legacy.name = "Legacy dome";
        legacy.active = true;
        legacy.supplierId = a.id();
        legacy.exwPrice = new BigDecimal("2.35");
        legacy.exwCurrency = Currency.USD;
        legacy.piecesPerCarton = 10;
        entities.persist(legacy);
        entities.flush();

        PurchaseOrder ofA = order(a, PurchaseOrderStatus.CONCEPT, new PurchaseOrderLine(null, legacy.id, 10,
                null, null, null, null));
        assertEquals(0, new BigDecimal("23.50").compareTo(purchases.calculate(ofA).lines().getFirst().goodsUsd()));

        productSuppliers.add(legacy.id, new ProductSupplierWrite(b.id(), "B", null, new BigDecimal("2.10"),
                Currency.USD, null, null, null, null, null), b.name());

        ProductSupplier aRow = productSuppliers.row(legacy.id, a.id()).orElseThrow();
        assertTrue(aRow.preferred(), "A, the product's own supplier, stays Voorkeur");
        assertFalse(productSuppliers.row(legacy.id, b.id()).orElseThrow().preferred());
        assertEquals(a.id(), products.get(legacy.id).supplierId());
        PurchaseOrder stillSaves = purchases.update(ofA.id(), purchases.get(ofA.id())).order();
        assertEquals(aRow.id(), stillSaves.lines().getFirst().productSupplierId());
    }

    @Test @TestTransaction
    void applyingBsContainerOnALiveProductWithOpenPointsRecordsBsPriceAndLeavesTheCardAlone() {
        Supplier a = supplier("A");
        Supplier b = supplier("B");
        Product dome = productAt(a, "2.35", "0.10");
        addRow(dome, b, "B · dikker glas", "CUL-GD-10", "2.10", "0.15");
        ProductEntity row = entities.find(ProductEntity.class, dome.id());
        row.websiteStatus = PublicationState.PUBLISHED;
        entities.flush();
        List<String> openPoints = products.get(dome.id()).publicationIssues();
        assertFalse(openPoints.isEmpty(), "the live product has open publication points");
        PurchaseOrder ofB = order(b, PurchaseOrderStatus.CONCEPT, line(dome, 100));

        LandedCost applied = purchases.applyToProducts(ofB.id());

        Product card = products.get(dome.id());
        assertEquals(0, applied.lines().getFirst().landedUnitEur().compareTo(card.landedCostEur()),
                "the container's cost reached the live product");
        assertEquals(PublicationState.PUBLISHED, card.publicationState(CatalogChannel.WEBSITE),
                "still live: the open points never block the apply");
        List<ProductCostEntry> rows = history.history(dome.id());
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("2.10").compareTo(rows.getFirst().exwPrice()), "B's EXW, not A's");
        assertEquals("USD", rows.getFirst().exwCurrency());
        assertEquals(0, new BigDecimal("0.15").compareTo(rows.getFirst().extraUnitCost()), "B's extra cost");
        assertEquals(a.id(), card.supplierId(), "the card keeps its Voorkeur");
        assertEquals(0, new BigDecimal("2.35").compareTo(card.exwPrice()));
        assertEquals(0, new BigDecimal("0.10").compareTo(card.extraUnitCost()));
        ProductSupplier voorkeur = productSuppliers.row(dome.id(), a.id()).orElseThrow();
        assertTrue(voorkeur.preferred());
        assertEquals(0, new BigDecimal("2.35").compareTo(voorkeur.exwPrice()));
        assertEquals(0, new BigDecimal("0.10").compareTo(voorkeur.extraUnitCost()));
        assertEquals(0, new BigDecimal("2.10").compareTo(productSuppliers.row(dome.id(), b.id()).orElseThrow()
                .exwPrice()), "applying never rewrites a row's EXW");
    }

    /* ---------------------------------------------------------- helpers */

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, "Versie " + name + " " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
    }

    private Product productAt(Supplier supplier, String exw, String extra) {
        return products.create(new Product(null, "VS-" + UUID.randomUUID(), "Glass dome", Dimensions.empty(), "Rood",
                null, null, supplier.id(), true, Barcodes.none(), null, Carton.empty(),
                new BigDecimal(exw), Currency.USD, extra == null ? null : new BigDecimal(extra),
                null, null, null, null, 0, List.of(), List.of()));
    }

    private ProductSupplier addRow(Product product, Supplier supplier, String label, String reference, String exw,
                                   String extra) {
        return productSuppliers.add(product.id(), new ProductSupplierWrite(supplier.id(), label, reference,
                new BigDecimal(exw), Currency.USD, extra == null ? null : new BigDecimal(extra), null, null, null,
                null), supplier.name());
    }

    private static PurchaseOrderLine line(Product product, int quantity) {
        return new PurchaseOrderLine(null, product.id(), quantity, null, null, null, null);
    }

    private PurchaseOrder order(Supplier supplier, PurchaseOrderStatus status, PurchaseOrderLine... lines) {
        PurchaseOrder concept = purchases.create(supplier.id(), CNY_USD, USD_EUR, BigDecimal.TEN);
        return purchases.update(concept.id(), concept.withReceipt(status, null, null, false, null, List.of(lines)))
                .order();
    }

    private PurchaseOrder save(PurchaseOrder order, PurchaseOrderStatus status) {
        return purchases.update(order.id(), order.withReceipt(status, null, null, false, order.notes(),
                order.lines())).order();
    }

    private static PurchaseOrderLine lineOf(PurchaseOrder order, Product product) {
        return order.lines().stream().filter(line -> product.id().equals(line.productId())).findFirst().orElseThrow();
    }
}
