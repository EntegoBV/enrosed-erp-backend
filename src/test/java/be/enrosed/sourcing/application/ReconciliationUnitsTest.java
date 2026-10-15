package be.enrosed.sourcing.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Line;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Totals;
import be.enrosed.sourcing.domain.PurchaseReconciliation.UnitCostBasis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §5.4: a box display of 12 long-stem roses, alone and next to a bowl sold per piece. */
class ReconciliationUnitsTest {

    private static final Product ROSES = product(1L, "Long stem rose box display",
            new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 12, SalesUnit.DISPLAY, "roos"));
    private static final Product BOWL = product(2L, "Rozenschaal", Packaging.none());
    private static final Product BOWL_TWO = product(3L, "Tweede schaal", Packaging.none());
    private static final Packaging BOX_OF_12 = new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 12,
            SalesUnit.DISPLAY, "roos");

    @Test
    void aSetContainerIsPricedPerSetWithThePiecePriceNextToIt() {
        var report = ReconciliationUnits.apply(report(false, line(1L, 100, 100, "3120.00", "3360.00")),
                Map.of(1L, ROSES));
        Line line = report.lines().getFirst();
        assertEquals("DISPLAY", line.salesUnit());
        assertEquals(12, line.piecesPerUnit());
        assertEquals("roos", line.unitKey());
        assertEquals("roos", line.unitOne());
        assertEquals("rozen", line.unitOther());
        assertEquals(100, line.unitCostQuantity(), "the sales-unit quantity keeps its meaning");
        assertEquals(1200, line.pieceQuantity());
        assertEquals(new BigDecimal("31.2000"), line.forecastExternalUnitEur(), "per set");
        assertEquals(new BigDecimal("2.6000"), line.forecastExternalPieceEur(), "per stuk");
        assertEquals(new BigDecimal("33.6000"), line.forecastPricingUnitEur());
        assertEquals(new BigDecimal("2.8000"), line.forecastPricingPieceEur());

        Totals totals = report.totals();
        assertEquals("DISPLAY", totals.setBasis());
        assertEquals(12, totals.setPieces());
        assertEquals("rozen", totals.setUnitOther());
        assertEquals(1200, totals.orderedPieceQuantity());
        assertEquals(1200, totals.unitCostPieceQuantity());
        assertEquals(new BigDecimal("31.2000"), totals.forecastExternalUnitEur());
        assertEquals(new BigDecimal("2.6000"), totals.forecastExternalPieceEur());
        assertTrue(report.notes().contains(ReconciliationUnits.SET_NOTE));
        assertEquals(1, totals.productCount());
        assertTrue(totals.containerAverage(), "one product: its figure is the container's");
        assertFalse(report.notes().contains(ReconciliationUnits.perProductNote(1)));
    }

    @Test
    void afterReceiptTheUsableSetsCarryTheCost() {
        var report = ReconciliationUnits.apply(report(true, line(1L, 100, 98, "3120.00", "3360.00")),
                Map.of(1L, ROSES));
        Line line = report.lines().getFirst();
        assertEquals(new BigDecimal("31.8367"), line.forecastExternalUnitEur(), "per bruikbare set");
        assertEquals(1176, line.pieceQuantity());
        assertEquals(new BigDecimal("2.6531"), line.forecastExternalPieceEur(), "per bruikbaar stuk");
        assertEquals(1176, report.totals().usablePieceQuantity());
        assertEquals(1200, report.totals().orderedPieceQuantity());
    }

    /** Round 2 (R2-4): the piece figures stay as data, but different products get no container average. */
    @Test
    void aMixedContainerCountsPiecesHasNoAverageAndLeavesTheSalesUnitFiguresAlone() {
        var before = report(false, line(1L, 100, 100, "3120.00", "3360.00"), line(2L, 200, 200, "1000.00", "1000.00"));
        var report = ReconciliationUnits.apply(before, Map.of(1L, ROSES, 2L, BOWL));
        Totals totals = report.totals();
        assertEquals("MIXED", totals.setBasis());
        assertEquals(12, totals.setPieces());
        assertEquals("rozen", totals.setUnitOther());
        assertEquals(300, totals.unitCostQuantity());
        assertEquals(new BigDecimal("13.7333"), totals.forecastExternalUnitEur(), "unchanged, in sales units");
        assertEquals(1400, totals.unitCostPieceQuantity());
        assertEquals(new BigDecimal("2.9429"), totals.forecastExternalPieceEur());
        Line bowl = report.lines().get(1);
        assertEquals("PIECE", bowl.salesUnit());
        assertEquals(1, bowl.piecesPerUnit());
        assertEquals(new BigDecimal("5.0000"), bowl.forecastExternalPieceEur());
        assertEquals(2, totals.productCount());
        assertFalse(totals.containerAverage(), "a set and a bowl: no average over both");
        assertTrue(report.notes().contains(ReconciliationUnits.perProductNote(2)), report.notes().toString());
        assertTrue(report.notes().stream().noneMatch(note -> note.contains("containergemiddelde")), report.notes().toString());
        for (int i = 0; i < 2; i++) {
            assertEquals(before.lines().get(i).unitCostQuantity(), report.lines().get(i).unitCostQuantity());
            assertEquals(before.lines().get(i).forecastExternalUnitEur(), report.lines().get(i).forecastExternalUnitEur());
        }
    }

    @Test
    void aPieceContainerReadsAsToday() {
        var before = report(false, line(2L, 200, 200, "1000.00", "1100.00"));
        var report = ReconciliationUnits.apply(before, Map.of(2L, BOWL));
        assertEquals("PIECE", report.totals().setBasis());
        assertNull(report.totals().setPieces());
        assertEquals(200, report.totals().unitCostPieceQuantity());
        assertEquals(before.totals().forecastExternalUnitEur(), report.totals().forecastExternalPieceEur());
        assertEquals(before.notes(), report.notes(), "no set note");
        assertEquals("stuk", report.lines().getFirst().unitOne());
        assertEquals("stuks", report.lines().getFirst().unitOther());
    }

    @Test
    void productsOfOneFamilySoldAlikeKeepTheContainerAverage() {
        Product red = family(product(5L, "Rose box red", BOX_OF_12), 77L);
        Product white = family(product(6L, "Rose box white", BOX_OF_12), 77L);
        Product six = family(product(7L, "Rose box six", new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 6,
                SalesUnit.DISPLAY, "roos")), 77L);
        Product other = family(product(8L, "Tulip box", BOX_OF_12), 78L);

        var alike = ReconciliationUnits.apply(report(false, line(5L, 10, 10, "312.00", "330.00"),
                line(6L, 20, 20, "624.00", "660.00")), Map.of(5L, red, 6L, white));
        assertEquals(2, alike.totals().productCount());
        assertTrue(alike.totals().containerAverage(), "one family, one set of 12 roses");
        assertEquals("DISPLAY", alike.totals().setBasis());
        assertTrue(alike.notes().stream().noneMatch(note -> note.startsWith("Deze container heeft")), alike.notes().toString());

        var sizes = ReconciliationUnits.apply(report(false, line(5L, 10, 10, "312.00", "312.00"),
                line(7L, 10, 10, "160.00", "160.00")), Map.of(5L, red, 7L, six));
        assertFalse(sizes.totals().containerAverage(), "sets of 12 and of 6 have no common average");

        var families = ReconciliationUnits.apply(report(false, line(5L, 10, 10, "312.00", "312.00"),
                line(8L, 10, 10, "300.00", "300.00")), Map.of(5L, red, 8L, other));
        assertFalse(families.totals().containerAverage(), "two families");

        var unknown = ReconciliationUnits.apply(report(false, line(1L, 10, 10, "100.00", "100.00"),
                line(2L, 10, 10, "50.00", "50.00")), Map.of(1L, BOWL, 2L, BOWL_TWO));
        assertFalse(unknown.totals().containerAverage(), "no family known: different products");
    }

    @Test
    void aProductSoldPerPieceInADisplayCountsOnePiecePerUnit() {
        Product perPiece = product(3L, "Roos per stuk in display",
                new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 12, SalesUnit.PIECE, "roos"));
        var report = ReconciliationUnits.apply(report(false, line(3L, 120, 120, "240.00", "240.00")), Map.of(3L, perPiece));
        Line line = report.lines().getFirst();
        assertEquals("PIECE", line.salesUnit());
        assertEquals(1, line.piecesPerUnit());
        assertEquals(120, line.pieceQuantity());
        assertEquals("rozen", line.unitOther());
        assertEquals("PIECE", report.totals().setBasis());
    }

    @Test
    void setsOfDifferentSizesHaveNoCommonSet() {
        Product six = product(4L, "Six roses display",
                new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 6, SalesUnit.DISPLAY, "roos"));
        var report = ReconciliationUnits.apply(report(false, line(1L, 10, 10, "312.00", "312.00"),
                line(4L, 10, 10, "100.00", "100.00")), Map.of(1L, ROSES, 4L, six));
        assertEquals("MIXED", report.totals().setBasis());
        assertNull(report.totals().setPieces());
        assertEquals("rozen", report.totals().setUnitOther(), "the piece name is still shared");
        assertEquals(180, report.totals().unitCostPieceQuantity());
    }

    @Test
    void aLineWithoutItsProductCountsAsPieces() {
        var report = ReconciliationUnits.apply(report(false, line(9L, 5, 5, "50.00", "50.00")), Map.of());
        assertEquals("PIECE", report.lines().getFirst().salesUnit());
        assertEquals("stuk", report.lines().getFirst().unitKey());
        assertEquals(new BigDecimal("10.0000"), report.lines().getFirst().forecastExternalPieceEur());
    }

    private static PurchaseReconciliation report(boolean received, Line... lines) {
        int ordered = 0;
        int usable = 0;
        BigDecimal forecast = BigDecimal.ZERO;
        BigDecimal pricing = BigDecimal.ZERO;
        for (Line line : lines) {
            ordered += line.orderedQuantity();
            usable += line.usableQuantity();
            forecast = forecast.add(line.forecastExternalEur());
            pricing = pricing.add(line.forecastPricingEur());
        }
        int quantity = received ? usable : ordered;
        Totals totals = new Totals(forecast, forecast, BigDecimal.ZERO, forecast, BigDecimal.ZERO,
                pricing.subtract(forecast), pricing, pricing, false, ordered, usable, 0, usable, quantity,
                received ? UnitCostBasis.USABLE_RECEIVED : UnitCostBasis.ORDERED, unit(forecast, quantity),
                unit(pricing, quantity), received, null);
        return new PurchaseReconciliation(List.of(), totals, List.of(lines), List.of("Bestaande notitie."));
    }

    private static Line line(Long productId, int ordered, int usable, String forecast, String pricing) {
        boolean received = usable != ordered;
        int quantity = received ? usable : ordered;
        BigDecimal external = new BigDecimal(forecast);
        BigDecimal priced = new BigDecimal(pricing);
        return new Line(productId, "Product " + productId, ordered, usable, 0, usable, quantity,
                received ? UnitCostBasis.USABLE_RECEIVED : UnitCostBasis.ORDERED, external, external, BigDecimal.ZERO,
                external, BigDecimal.ZERO, priced.subtract(external), priced, unit(external, quantity),
                unit(priced, quantity), "Naar goederenwaarde.", BigDecimal.ZERO.setScale(2));
    }

    private static BigDecimal unit(BigDecimal amount, int quantity) {
        return quantity == 0 ? null : amount.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
    }

    /** The same product in a family of products. */
    private static Product family(Product base, Long familyId) {
        return new Product(base.id(), base.sku(), base.name(), base.dimensions(), base.packaging(),
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.supplierNote(), base.active(),
                familyId, base.canonicalVariantKey(), base.canonicalBarcode(),
                base.variantPosition(), base.inventoryKnown(), base.familyKey(), base.publicHandle(),
                base.websiteStatus(), base.orderAppStatus(), base.barcodes(), base.hsCode(),
                base.carton(), base.exwPrice(), base.exwCurrency(), base.extraUnitCost(), base.landedCostEur(),
                base.landedCostSource(), base.markupPct(), base.fixedSalesPriceEur(),
                base.stockQuantity(), base.photos(), base.texts(), base.demo());
    }

    private static Product product(Long id, String name, Packaging packaging) {
        Product base = new Product(id, "SKU-" + id, name, Dimensions.empty(), null, null, 1L, 1L, true,
                Barcodes.none(), null, Carton.empty(), BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ONE, "test", new BigDecimal("45"), null, 0, List.of(), List.of());
        return new Product(base.id(), base.sku(), base.name(), base.dimensions(), packaging,
                base.colour(), base.variantSize(), base.colourHex(), base.description(),
                base.categoryId(), base.supplierId(), base.supplierNote(), base.active(),
                base.familyId(), base.canonicalVariantKey(), base.canonicalBarcode(),
                base.variantPosition(), base.inventoryKnown(), base.familyKey(), base.publicHandle(),
                base.websiteStatus(), base.orderAppStatus(), base.barcodes(), base.hsCode(),
                base.carton(), base.exwPrice(), base.exwCurrency(), base.extraUnitCost(), base.landedCostEur(),
                base.landedCostSource(), base.markupPct(), base.fixedSalesPriceEur(),
                base.stockQuantity(), base.photos(), base.texts(), base.demo());
    }
}
