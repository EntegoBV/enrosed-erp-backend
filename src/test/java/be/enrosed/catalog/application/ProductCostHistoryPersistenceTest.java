package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.ProductCostBasis;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost history as applying a container writes it: once per change, with
 * the basis the cost holds on and what one unit was made of; nothing for a
 * line that received nothing; and the container's name also after it went
 * to the trash.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductCostHistoryPersistenceTest {

    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject ProductService productService;
    @Inject ProductCostHistoryService history;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void aContainerIsRecordedOnceWithItsBasisAndPartsAndAChangedCostIsAReapply() {
        Supplier supplier = supplier();
        ProductEntity diamond = displayProduct(supplier, "diamant");
        PurchaseOrder first = order(supplier, null, new PurchaseOrderLine(null, diamond.id, 120,
                new BigDecimal("1.20"), Currency.USD, new BigDecimal("0.10"), null));
        PurchaseOrderEntity firstRow = entities.find(PurchaseOrderEntity.class, first.id());
        firstRow.freightUsd = new BigDecimal("1000");
        firstRow.destinationCostsEur = new BigDecimal("300");
        firstRow.extraRevenueEur = new BigDecimal("120");
        entities.flush();

        LandedCost applied = purchases.applyToProducts(first.id());
        LandedCost.Line line = applied.lines().getFirst();
        List<ProductCostEntry> rows = history.history(diamond.id);
        assertEquals(1, rows.size());
        ProductCostEntry row = rows.getFirst();
        assertEquals("APPLY", row.kind());
        assertEquals(0, line.landedUnitEur().compareTo(row.landedUnitEur()));
        assertNull(row.previousLandedUnitEur(), "the product had no cost before");
        assertEquals(first.number(), row.source());
        assertEquals(first.id(), row.purchaseOrderId());
        assertEquals(first.number(), row.containerName(), "no Herkenbare naam: the number");
        assertFalse(row.containerGone());
        assertEquals(120, row.quantity());
        assertEquals(new ProductCostBasis("DISPLAY", 12, "diamant"), row.basis());
        assertEquals(0, new BigDecimal("0.10").compareTo(row.extraUnitCost()), "in the EXW currency");
        assertEquals("USD", row.exwCurrency());
        ProductCostBreakdown parts = row.breakdown();
        assertNotNull(parts);
        assertEquals(perUnit(line.goodsEur(), 120), parts.goodsEur(), "the goods include the extra unit cost");
        assertEquals(perUnit(line.originEur(), 120), parts.originEur());
        assertEquals(perUnit(line.freightEur(), 120), parts.freightEur());
        assertEquals(perUnit(line.dutyEur(), 120), parts.dutyEur());
        assertEquals(perUnit(line.destinationEur(), 120), parts.destinationEur());
        assertEquals(new BigDecimal("1.0000"), parts.enrosedEur(), "the Enrosed kost is the line's extra revenue");
        assertEquals(perUnit(line.separateEur(), 120), parts.separateEur());
        assertTrue(parts.freightEur().signum() > 0 && parts.dutyEur().signum() > 0);

        purchases.applyToProducts(first.id());
        assertEquals(1, history.history(diamond.id).size(), "the same container at the same cost adds nothing");

        firstRow = entities.find(PurchaseOrderEntity.class, first.id());
        firstRow.freightUsd = new BigDecimal("1600");
        entities.flush();
        BigDecimal dearer = purchases.applyToProducts(first.id()).lines().getFirst().landedUnitEur();
        rows = history.history(diamond.id);
        assertEquals(2, rows.size());
        assertEquals("REAPPLY", rows.getFirst().kind(), "the same container at another cost");
        assertEquals(0, line.landedUnitEur().compareTo(rows.getFirst().previousLandedUnitEur()));
        assertEquals(0, dearer.compareTo(rows.getFirst().landedUnitEur()));

        PurchaseOrder second = order(supplier, "voor Frans", new PurchaseOrderLine(null, diamond.id, 60,
                new BigDecimal("1.50"), Currency.USD, null, null));
        purchases.applyToProducts(second.id());
        rows = history.history(diamond.id);
        assertEquals(3, rows.size());
        assertEquals("APPLY", rows.getFirst().kind(), "another container");
        assertEquals("voor Frans", rows.getFirst().containerName(), "the Herkenbare naam wins");
        assertNull(rows.getFirst().extraUnitCost(), "neither the line nor the product has an extra unit cost");

        /* Since the batches round an older container keeps a newer container's card, unless the product is named
           ("Ook op de productkaart"). */
        BigDecimal newerCard = productService.get(diamond.id).landedCostEur();
        purchases.applyToProducts(first.id());
        assertEquals(3, history.history(diamond.id).size(), "the newer container's card is kept: no row");
        assertEquals(0, newerCard.compareTo(productService.get(diamond.id).landedCostEur()));
        assertEquals(second.number(), productService.get(diamond.id).landedCostSource());
        purchases.applyToProducts(first.id(), new PurchaseOrderService.ApplyRequest(List.of(diamond.id)));
        rows = history.history(diamond.id);
        assertEquals(4, rows.size(), "an older container after a newer one is a real change");
        assertEquals("APPLY", rows.getFirst().kind());
        assertEquals(first.id(), rows.getFirst().purchaseOrderId());
        assertEquals(0, dearer.compareTo(rows.getFirst().landedUnitEur()));
        assertEquals(0, dearer.compareTo(productService.get(diamond.id).landedCostEur()));
    }

    @Test @TestTransaction
    void reapplyingAtTheSameCostFillsTheMissingPartsOfThatContainersRowOnly() {
        Supplier supplier = supplier();
        ProductEntity rose = displayProduct(supplier, "roos");
        PurchaseOrder older = order(supplier, null, new PurchaseOrderLine(null, rose.id, 60,
                new BigDecimal("1.50"), Currency.USD, null, null));
        PurchaseOrder order = order(supplier, null, new PurchaseOrderLine(null, rose.id, 120,
                new BigDecimal("1.20"), Currency.USD, new BigDecimal("0.10"), null));
        withCosts(order.id(), "1000");
        purchases.applyToProducts(older.id());
        purchases.applyToProducts(order.id());
        /* Both rows as written before 2026-10-16: no parts. */
        List<ProductCostEntry> before = history.history(rose.id);
        assertEquals(2, before.size());
        before.forEach(row -> withoutParts(row.id()));
        entities.clear();
        ProductCostEntry newest = history.history(rose.id).getFirst();
        assertNull(newest.breakdown());
        assertNull(newest.extraUnitCost());
        assertEquals("USD", newest.exwCurrency(), "EXW price and currency were kept then");

        LandedCost.Line line = purchases.applyToProducts(order.id()).lines().getFirst();
        entities.flush();
        entities.clear();

        List<ProductCostEntry> rows = history.history(rose.id);
        assertEquals(2, rows.size(), "the same container at the same cost still adds no row");
        ProductCostEntry filled = rows.getFirst();
        assertEquals(newest.id(), filled.id());
        assertEquals("APPLY", filled.kind());
        assertEquals(newest.appliedAt(), filled.appliedAt(), "the row keeps the day it was applied");
        assertEquals(0, newest.landedUnitEur().compareTo(filled.landedUnitEur()));
        assertEquals(partsOf(line), filled.breakdown(), "all seven parts of this container");
        assertEquals(0, new BigDecimal("0.10").compareTo(filled.extraUnitCost()), "the goods part includes it, so the row names it");
        assertEquals("USD", filled.exwCurrency());
        assertEquals(older.id(), rows.get(1).purchaseOrderId());
        assertNull(rows.get(1).breakdown(), "another container's row is left alone");

        /* A row that has its parts is never rewritten. */
        entities.find(ProductCostHistoryEntity.class, filled.id()).goodsEur = new BigDecimal("9.9999");
        entities.flush();
        purchases.applyToProducts(order.id());
        entities.flush();
        entities.clear();
        rows = history.history(rose.id);
        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("9.9999"), rows.getFirst().breakdown().goodsEur());
        assertEquals(partsOf(line).freightEur(), rows.getFirst().breakdown().freightEur());
    }

    @Test @TestTransaction
    void aStartRowWithoutPartsTakesThemAndAnotherCostIsStillAReapply() {
        Supplier supplier = supplier();
        ProductEntity dome = displayProduct(supplier, "stolp");
        PurchaseOrder order = order(supplier, null, new PurchaseOrderLine(null, dome.id, 18,
                new BigDecimal("6.00"), Currency.USD, new BigDecimal("0.25"), null));
        withCosts(order.id(), "800");
        purchases.applyToProducts(order.id());
        /* The one row the first start wrote: this container, no quantity, no parts. */
        long start = history.history(dome.id).getFirst().id();
        ProductCostHistoryEntity seed = entities.find(ProductCostHistoryEntity.class, start);
        seed.kind = "SEED";
        seed.appliedBy = "system";
        seed.quantity = null;
        seed.exwPrice = null;
        seed.exwCurrency = null;
        entities.flush();
        withoutParts(start);

        LandedCost.Line line = purchases.applyToProducts(order.id()).lines().getFirst();
        entities.flush();
        entities.clear();
        List<ProductCostEntry> rows = history.history(dome.id);
        assertEquals(1, rows.size());
        assertEquals("SEED", rows.getFirst().kind());
        assertEquals("system", rows.getFirst().appliedBy());
        assertNull(rows.getFirst().quantity(), "the quantity of then is not proven by the cost");
        assertNull(rows.getFirst().exwPrice());
        assertEquals(partsOf(line), rows.getFirst().breakdown());
        assertEquals(0, new BigDecimal("0.25").compareTo(rows.getFirst().extraUnitCost()), "part of the goods");
        assertEquals("USD", rows.getFirst().exwCurrency(), "the extra unit cost comes with its currency");

        /* Another cost from the same container: a new row with its own parts, the start row stays as it was. */
        withoutParts(start);
        withCosts(order.id(), "1400");
        LandedCost.Line dearer = purchases.applyToProducts(order.id()).lines().getFirst();
        entities.flush();
        entities.clear();
        rows = history.history(dome.id);
        assertEquals(List.of("REAPPLY", "SEED"), rows.stream().map(ProductCostEntry::kind).toList());
        assertEquals(0, dearer.landedUnitEur().compareTo(rows.getFirst().landedUnitEur()));
        assertEquals(partsOf(dearer), rows.getFirst().breakdown());
        assertNull(rows.get(1).breakdown(), "parts of another cost do not belong to the start row");
        assertNull(rows.get(1).extraUnitCost());
    }

    @Test @TestTransaction
    void aLineThatReceivedNothingKeepsTheCostAndWritesNoRow() {
        Supplier supplier = supplier();
        ProductEntity missing = displayProduct(supplier, null);
        missing.landedCostEur = new BigDecimal("2.5000");
        missing.landedCostSource = "PO-OLD";
        ProductEntity arrived = displayProduct(supplier, null);
        entities.flush();
        PurchaseOrder order = order(supplier, null,
                new PurchaseOrderLine(null, missing.id, 0, new BigDecimal("1.20"), Currency.USD, null, null),
                new PurchaseOrderLine(null, arrived.id, 50, new BigDecimal("1.20"), Currency.USD, null, null));

        purchases.applyToProducts(order.id());

        assertEquals(0, new BigDecimal("2.5").compareTo(productService.get(missing.id).landedCostEur()),
                "a € 0 piece price would wipe the cost the product carries");
        assertEquals("PO-OLD", productService.get(missing.id).landedCostSource());
        assertTrue(history.history(missing.id).isEmpty());
        assertEquals(1, history.history(arrived.id).size());
        assertTrue(purchases.get(order.id()).notes()
                        .contains("1 product(en) bijgewerkt in de catalogus, 1 overgeslagen (niets ontvangen)."),
                purchases.get(order.id()).notes());
    }

    @Test @TestTransaction
    void aLiveProductWithOpenPublicationPointsStillTakesTheContainerCost() {
        /* PO-2026-005 on 2026-10-25: one live product missing its handle and swatch rolled back the whole apply. */
        Supplier supplier = supplier();
        ProductEntity live = displayProduct(supplier, "stolp");
        live.websiteStatus = be.enrosed.catalog.domain.PublicationState.PUBLISHED;
        ProductEntity draft = displayProduct(supplier, "stolp");
        entities.flush();
        assertFalse(productService.get(live.id).publicationIssues().isEmpty(), "the live product has open points");
        PurchaseOrder order = order(supplier, null,
                new PurchaseOrderLine(null, live.id, 18, new BigDecimal("8.50"), Currency.USD, null, null),
                new PurchaseOrderLine(null, draft.id, 96, new BigDecimal("8.50"), Currency.USD, null, null));
        withCosts(order.id(), "6000");

        LandedCost result = purchases.applyToProducts(order.id());

        for (ProductEntity product : List.of(live, draft)) {
            LandedCost.Line line = result.lines().stream().filter(l -> l.productId().equals(product.id)).findFirst().orElseThrow();
            assertEquals(0, line.landedUnitEur().compareTo(productService.get(product.id).landedCostEur()));
            assertEquals(order.number(), productService.get(product.id).landedCostSource());
            assertEquals(1, history.history(product.id).size());
        }
        assertEquals(be.enrosed.catalog.domain.PublicationState.PUBLISHED,
                productService.get(live.id).publicationState(be.enrosed.catalog.domain.CatalogChannel.WEBSITE));
    }

    @Test @TestTransaction
    void aTrashedContainerKeepsItsNameAndReadsAsGone() {
        Supplier supplier = supplier();
        ProductEntity product = displayProduct(supplier, null);
        PurchaseOrder order = order(supplier, "Proefcontainer", new PurchaseOrderLine(null, product.id, 30,
                new BigDecimal("2.00"), Currency.USD, null, null));
        purchases.applyToProducts(order.id());
        assertEquals("Proefcontainer", history.history(product.id).getFirst().containerName());
        assertFalse(history.history(product.id).getFirst().containerGone());

        purchases.delete(order.id());
        entities.flush();
        ProductCostEntry trashed = history.history(product.id).getFirst();
        assertEquals("Proefcontainer", trashed.containerName(), "the trash keeps the name");
        assertTrue(trashed.containerGone());

        ProductCostHistoryEntity purged = entities.find(ProductCostHistoryEntity.class, trashed.id());
        purged.purchaseOrderId = 987_654_321L;
        entities.flush();
        ProductCostEntry gone = history.history(product.id).getFirst();
        assertEquals(order.number(), gone.containerName(), "purged for good: the number the row kept");
        assertTrue(gone.containerGone());
    }

    @Test @TestTransaction
    void aCopyStartsWithTheCostItTookOver() {
        Supplier supplier = supplier();
        ProductEntity source = displayProduct(supplier, "hart");
        source.colour = "Wit";
        PurchaseOrder order = order(supplier, null, new PurchaseOrderLine(null, source.id, 40,
                new BigDecimal("3.00"), Currency.USD, null, null));
        purchases.applyToProducts(order.id());
        ProductCostEntry applied = history.history(source.id).getFirst();

        long copyId = productService.duplicate(source.id, "Rood").id();
        List<ProductCostEntry> rows = history.history(copyId);
        assertEquals(1, rows.size());
        ProductCostEntry copy = rows.getFirst();
        assertEquals("COPY", copy.kind());
        assertEquals(0, applied.landedUnitEur().compareTo(copy.landedUnitEur()));
        assertNull(copy.previousLandedUnitEur());
        assertNull(copy.quantity());
        assertEquals(order.id(), copy.purchaseOrderId(), "the source's newest row is this cost: same container");
        assertEquals(order.number(), copy.source());
        assertEquals(applied.breakdown(), copy.breakdown());
        assertEquals(new ProductCostBasis("DISPLAY", 12, "hart"), copy.basis());

        /* The copy joins the same container: its first apply from it is an apply, not a re-apply. */
        PurchaseOrder current = purchases.get(order.id());
        List<PurchaseOrderLine> lines = new ArrayList<>(current.lines());
        lines.add(new PurchaseOrderLine(null, copyId, 40, new BigDecimal("3.00"), Currency.USD, null, null));
        purchases.update(order.id(), current.withReceipt(current.status(), null, null, false, current.notes(), lines));
        LandedCost both = purchases.applyToProducts(order.id());
        rows = history.history(copyId);
        assertEquals(List.of("APPLY", "COPY"), rows.stream().map(ProductCostEntry::kind).toList());
        assertEquals(order.id(), rows.getFirst().purchaseOrderId());
        assertEquals(40, rows.getFirst().quantity(), "the copy's own quantity from the container");
        assertEquals(0, copy.landedUnitEur().compareTo(rows.getFirst().previousLandedUnitEur()));
        assertEquals(0, both.lines().stream().filter(l -> l.productId() == copyId).findFirst().orElseThrow()
                .landedUnitEur().compareTo(rows.getFirst().landedUnitEur()));
        assertEquals(List.of("REAPPLY", "APPLY"), history.history(source.id).stream().map(ProductCostEntry::kind).toList(),
                "the source did carry this container: another cost is a re-apply there");

        /* The same container at the very cost the copy took over: still written, as the copy's first apply. */
        ProductCostEntry sourceNow = history.history(source.id).getFirst();
        long second = productService.duplicate(source.id, "Groen").id();
        assertEquals(order.id(), history.history(second).getFirst().purchaseOrderId());
        history.record(second, sourceNow.landedUnitEur(), sourceNow.landedUnitEur(), order.number(), order.id(), 40,
                new BigDecimal("3.00"), "USD", null, new ProductCostBasis("DISPLAY", 12, "hart"), null);
        rows = history.history(second);
        assertEquals(List.of("APPLY", "COPY"), rows.stream().map(ProductCostEntry::kind).toList());
        assertEquals(40, rows.getFirst().quantity());

        /* A cost the history never saw: only the text the cost carried. */
        ProductEntity loose = displayProduct(supplier, null);
        loose.colour = "Wit";
        loose.packagingKind = PackagingKind.NONE;
        loose.landedCostEur = new BigDecimal("4.1000");
        loose.landedCostSource = "Offerte maart";
        entities.flush();
        ProductCostEntry looseCopy = history.history(productService.duplicate(loose.id, "Rood").id()).getFirst();
        assertEquals("COPY", looseCopy.kind());
        assertEquals("Offerte maart", looseCopy.source());
        assertNull(looseCopy.purchaseOrderId());
        assertNull(looseCopy.breakdown());
        assertEquals(new ProductCostBasis("PIECE", null, "stuk"), looseCopy.basis());

        loose.landedCostEur = null;
        entities.flush();
        long without = productService.duplicate(loose.id, "Blauw").id();
        assertTrue(history.history(without).isEmpty(), "no cost, no row");
    }

    @Test @TestTransaction
    void theMigrationFillsTheBasisAndKindOfOlderRowsOnceAndLeavesNewRowsAlone() throws IOException {
        Supplier supplier = supplier();
        ProductEntity display = displayProduct(supplier, " Diamant ");
        ProductEntity giftBox = displayProduct(supplier, null);
        giftBox.packagingKind = PackagingKind.GIFT_BOX;
        giftBox.packagingPiecesPerUnit = 1;
        ProductEntity pieces = displayProduct(supplier, "");
        pieces.packagingSalesUnit = SalesUnit.PIECE;
        entities.flush();
        long seed = legacyRow(display.id, "system");
        long systemApply = legacyRow(giftBox.id, "systeem");
        long applied = legacyRow(pieces.id, "emre");
        long orphan = legacyRow(999_999_999L, null);
        ProductCostHistoryEntity recent = entities.find(ProductCostHistoryEntity.class, legacyRow(display.id, "emre"));
        recent.kind = "REAPPLY";
        recent.salesUnit = "PIECE";
        recent.unitKey = "roos";
        entities.flush();

        runMigration();
        runMigration();
        entities.clear();

        assertRow(seed, "SEED", "DISPLAY", 12, "diamant");
        assertRow(systemApply, "APPLY", "PIECE", null, "stuk");
        assertRow(applied, "APPLY", "PIECE", 12, "stuk");
        assertRow(orphan, "APPLY", null, null, null);
        assertRow(recent.id, "REAPPLY", "PIECE", null, "roos");
    }

    private void assertRow(long id, String kind, String salesUnit, Integer pieces, String unitKey) {
        ProductCostHistoryEntity row = entities.find(ProductCostHistoryEntity.class, id);
        assertEquals(kind, row.kind, "kind of " + id);
        assertEquals(salesUnit, row.salesUnit, "sales unit of " + id);
        assertEquals(pieces, row.piecesPerUnit, "pieces of " + id);
        assertEquals(unitKey, row.unitKey, "unit of " + id);
    }

    private long legacyRow(long productId, String appliedBy) {
        ProductCostHistoryEntity row = new ProductCostHistoryEntity();
        row.productId = productId;
        row.landedUnitEur = new BigDecimal("1.2345");
        row.appliedAt = java.time.Instant.now();
        row.appliedBy = appliedBy;
        entities.persist(row);
        entities.flush();
        return row.id;
    }

    /**
     * The backfill statements of the Railway script, as written. The columns
     * themselves already exist here, and H2 would commit the test transaction
     * on a schema change, so the twelve additions are only counted.
     */
    private void runMigration() throws IOException {
        String script = Files.readString(Path.of("docs/migrations/2026-10-16/product-cost-history-basis-postgresql.sql"))
                .replaceAll("--[^\\r\\n]*", " ");
        List<String> statements = Arrays.stream(script.split(";"))
                .map(statement -> statement.strip().replaceAll("\\s+", " "))
                .filter(statement -> !statement.isEmpty())
                .toList();
        assertEquals(12, statements.stream().filter(statement -> statement.startsWith("alter table ")).count());
        List<String> backfills = statements.stream().filter(statement -> statement.startsWith("update ")).toList();
        assertEquals(2, backfills.size());
        for (String statement : backfills) entities.createNativeQuery(statement).executeUpdate();
    }

    private Supplier supplier() {
        return suppliers.save(new Supplier(null, "Cost history " + UUID.randomUUID(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
    }

    /** A display of twelve sold per display; null leaves the unit at its default. */
    private ProductEntity displayProduct(Supplier supplier, String unitKey) {
        ProductEntity product = new ProductEntity();
        product.sku = "CH-" + UUID.randomUUID();
        product.name = "Cost history rose";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
        product.packagingKind = PackagingKind.DISPLAY;
        product.packagingPiecesPerUnit = 12;
        product.packagingSalesUnit = SalesUnit.DISPLAY;
        product.packagingUnitKey = unitKey;
        entities.persist(product);
        entities.flush();
        return product;
    }

    private PurchaseOrder order(Supplier supplier, String alias, PurchaseOrderLine... lines) {
        PurchaseOrder concept = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        long id = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null,
                false, null, List.of(lines))).order().id();
        if (alias != null) {
            entities.find(PurchaseOrderEntity.class, id).alias = alias;
            entities.flush();
        }
        return purchases.get(id);
    }

    /** Container costs that make every part of the line count. */
    private void withCosts(long orderId, String freightUsd) {
        PurchaseOrderEntity row = entities.find(PurchaseOrderEntity.class, orderId);
        row.freightUsd = new BigDecimal(freightUsd);
        row.destinationCostsEur = new BigDecimal("300");
        row.extraRevenueEur = new BigDecimal("120");
        entities.flush();
    }

    /** A row as every apply before 2026-10-16 wrote it: no parts, no extra unit cost (EXW price and currency stay). */
    private void withoutParts(long rowId) {
        ProductCostHistoryEntity row = entities.find(ProductCostHistoryEntity.class, rowId);
        row.goodsEur = row.originEur = row.freightEur = row.dutyEur = null;
        row.destinationEur = row.enrosedEur = row.separateEur = null;
        row.extraUnitCost = null;
        entities.flush();
    }

    private static ProductCostBreakdown partsOf(LandedCost.Line line) {
        return ProductCostBreakdown.perUnit(line.quantity(), line.goodsEur(), line.originEur(), line.freightEur(),
                line.dutyEur(), line.destinationEur(), line.extraRevenueEur(), line.separateEur());
    }

    private static BigDecimal perUnit(BigDecimal total, int quantity) {
        return total.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
    }
}
