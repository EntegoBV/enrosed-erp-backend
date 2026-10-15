package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPurchaseRendererTest {

    @Test
    void missingProductMasterNeverInventsCartonDetails() {
        assertTrue(PdfPurchaseRenderer.productSpecs(null).isEmpty());
        assertNull(PdfPurchaseRenderer.piecesPerCarton(null));
    }

    @Test
    void pdfCanDistinguishUnifiedAndHistoricalRates() {
        assertTrue(PdfPurchaseRenderer.sameRate(order("0.90", "0.9000")));
        assertFalse(PdfPurchaseRenderer.sameRate(order("0.81", "0.93")));
    }

    @Test
    void layoutKeepsLandscapeAsBackwardCompatibleDefault() {
        assertEquals(PdfPurchaseRenderer.Layout.LANDSCAPE,
                PdfPurchaseRenderer.Layout.parse(null));
        assertEquals(PdfPurchaseRenderer.Layout.LANDSCAPE,
                PdfPurchaseRenderer.Layout.parse("  "));
        assertEquals(PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Layout.parse("portrait"));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Layout.parse("square"));
    }

    @Test
    void audienceKeepsStandardAsBackwardCompatibleDefaultAndValidatesSupplierPaper() {
        assertEquals(PdfPurchaseRenderer.Audience.STANDARD,
                PdfPurchaseRenderer.Audience.parse(null));
        assertEquals(PdfPurchaseRenderer.Audience.STANDARD,
                PdfPurchaseRenderer.Audience.parse("  "));
        assertEquals(PdfPurchaseRenderer.Audience.INTERNAL,
                PdfPurchaseRenderer.Audience.parse("internal"));
        assertEquals(PdfPurchaseRenderer.Audience.SUPPLIER,
                PdfPurchaseRenderer.Audience.parse(" supplier "));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Audience.parse("customer"));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Audience.SUPPLIER.validate(
                        PdfPurchaseRenderer.Layout.LANDSCAPE));
        PdfPurchaseRenderer.Audience.SUPPLIER.validate(PdfPurchaseRenderer.Layout.PORTRAIT);
    }

    @Test
    void portraitOptionsOnlyApplyToTheStandardPortraitContract() {
        var requested = new PdfPurchaseRenderer.PdfOptions(false, false, true, true, true);

        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(), requested.normalized(
                PdfPurchaseRenderer.Layout.LANDSCAPE, PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(), requested.normalized(
                PdfPurchaseRenderer.Layout.PORTRAIT, PdfPurchaseRenderer.Audience.SUPPLIER));
        assertEquals(new PdfPurchaseRenderer.PdfOptions(false, false, false, false, false),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
    }

    @Test
    void combinedCostCanStandAloneAndSuppressesLegacyFreightFlags() {
        var requested = new PdfPurchaseRenderer.PdfOptions(
                false, false, true, true, true, true);

        assertEquals(new PdfPurchaseRenderer.PdfOptions(
                        false, false, false, false, false, true),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.LANDSCAPE,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.SUPPLIER));
    }

    @Test
    void unitPriceCanBeHiddenWithoutRemovingLineAndOrderTotals() {
        var requested = new PdfPurchaseRenderer.PdfOptions(
                true, true, false, false, false, false, false, false);

        var portrait = requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD);
        assertTrue(portrait.showPrices());
        assertFalse(portrait.includeUnitPrice());

        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.LANDSCAPE,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.SUPPLIER));
    }

    @Test
    void eurOnlyWinsFromTheSubtleEquivalentAndStaysInStandardPortrait() {
        var requested = new PdfPurchaseRenderer.PdfOptions(
                true, true, true, true, true, true, false);

        assertEquals(new PdfPurchaseRenderer.PdfOptions(
                        true, true, false, true, false, false, false),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.LANDSCAPE,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.SUPPLIER));

        var hiddenPrices = new PdfPurchaseRenderer.PdfOptions(
                true, false, false, true, false, false, false);
        assertFalse(hiddenPrices.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD).eurOnly());
    }

    @Test
    void supplierEanPrefersCanonicalButFallsBackToEditablePieceBarcode() {
        Product legacy = new Product(
                1L, "SKU-EAN", "EAN product", Dimensions.empty(), null, null,
                null, null, true, new Barcodes(" 5410000000019 ", "15410000000016"),
                null, Carton.empty(), BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());

        assertEquals("5410000000019", PdfPurchaseRenderer.ean(legacy));
        assertEquals("8710000000010", PdfPurchaseRenderer.ean(
                legacy.withCanonicalIdentity(null, null, " 8710000000010 ", 0, true)));
    }

    @Test
    void agreedUnitPriceTreatsAmountAndCurrencyAsOnePair() {
        Product product = new Product(
                1L, "SKU-PRICE", "Price product", Dimensions.empty(), null, null,
                null, null, true, Barcodes.none(), null, Carton.empty(),
                new BigDecimal("10"), Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());

        var inherited = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, null, null, BigDecimal.ZERO, null),
                product);
        assertEquals(new BigDecimal("10"), inherited.amount());
        assertEquals(Currency.USD, inherited.currency());

        var explicit = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, new BigDecimal("12"), Currency.EUR,
                        BigDecimal.ZERO, null), product);
        assertEquals(new BigDecimal("12"), explicit.amount());
        assertEquals(Currency.EUR, explicit.currency());

        var currencyOnly = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, null, Currency.EUR,
                        BigDecimal.ZERO, null), product);
        assertFalse(currencyOnly.available(),
                "a line currency must never be combined with the product amount");
        assertNull(currencyOnly.amount());
        assertNull(currencyOnly.currency());

        var amountOnly = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, new BigDecimal("12"), null,
                        BigDecimal.ZERO, null), product);
        assertFalse(amountOnly.available(),
                "a line amount must never be combined with the product currency");
        assertNull(amountOnly.amount());
        assertNull(amountOnly.currency());
    }

    @Test
    void aCifContainerPrintsItsOwnBasisAndItsFreightAsATermBeforeArrival() {
        var base = order("0.90", "0.90");
        var exwLine = new PurchaseOrderLine(1L, 1L, 10, new BigDecimal("10"), Currency.USD, null, 10);
        var ddpLine = new PurchaseOrderLine(2L, 2L, 10, new BigDecimal("10"), Currency.USD, null, 10,
                be.enrosed.sourcing.domain.PriceBasis.DDP);
        var exw = base.withReceipt(be.enrosed.sourcing.domain.PurchaseOrderStatus.ONDERWEG, null, null, false,
                null, List.of(exwLine));
        var cif = exw.withFreightViaSupplier(true);
        assertEquals("Per productregel", PdfPurchaseRenderer.priceBasis(base));
        assertEquals("EXW", PdfPurchaseRenderer.priceBasis(exw), "the supplier's incoterm on paper is not the container's");
        assertEquals("CIF", PdfPurchaseRenderer.priceBasis(cif));
        assertEquals("DDP", PdfPurchaseRenderer.priceBasis(cif.withReceipt(cif.status(), null, null, false, null,
                List.of(ddpLine))));
        assertEquals("Per productregel", PdfPurchaseRenderer.priceBasis(cif.withReceipt(cif.status(), null, null,
                false, null, List.of(exwLine, ddpLine))));

        var payable = new be.enrosed.sourcing.application.PurchaseOrderService.Payable(new BigDecimal("1200.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, true, false, new BigDecimal("300.00"));
        var schedule = PdfPurchaseRenderer.schedule(cif, payable);
        assertEquals(List.of("1/3 bij bestelling", "1/3 bij vertrek", "Zeevracht (CIF)", "1/3 bij aankomst"),
                schedule.stream().map(PdfPurchaseRenderer.ScheduleRow::label).toList());
        String third = be.enrosed.shared.DocumentFormat.eur(new BigDecimal("300.00"));
        assertEquals(List.of(third, third, third, third),
                schedule.stream().map(PdfPurchaseRenderer.ScheduleRow::amount).toList(),
                "the thirds split the goods; the freight is its own amount");
        assertEquals(List.of(true, true, true, false),
                schedule.stream().map(PdfPurchaseRenderer.ScheduleRow::done).toList(), "the freight falls due at departure");
        assertTrue(PdfPurchaseRenderer.payableView(List.of(), payable).cif());
        assertFalse(PdfPurchaseRenderer.payableView(List.of(), new be.enrosed.sourcing.application.PurchaseOrderService.Payable(
                new BigDecimal("1200.00"), BigDecimal.ZERO, BigDecimal.ZERO, true, true)).cif(), "DDP is no CIF");

        /* The inspection paid to the supplier in a goods transfer (or not on the bank yet); a lab fee on its own transfer is not. */
        var day = LocalDate.of(2026, 9, 3);
        var goods = new be.enrosed.sourcing.domain.PurchasePayment(11L, 1L, day, new BigDecimal("10000"), Currency.EUR,
                new BigDecimal("10000"), "Goederen", null, null, be.enrosed.sourcing.domain.PurchasePayment.Payee.SUPPLIER,
                false, null, 610L);
        var inspection = new be.enrosed.sourcing.domain.PurchasePayment(12L, 1L, day, new BigDecimal("300"), Currency.EUR,
                new BigDecimal("300"), "Inspectie", null, null, be.enrosed.sourcing.domain.PurchasePayment.Payee.SEPARATE,
                false, null, 610L);
        var lab = new be.enrosed.sourcing.domain.PurchasePayment(13L, 1L, day, new BigDecimal("50"), Currency.EUR,
                new BigDecimal("50"), "Labo", null, null, be.enrosed.sourcing.domain.PurchasePayment.Payee.SEPARATE,
                false, null, 611L);
        var ledger = List.of(goods, inspection, lab);
        assertEquals(be.enrosed.shared.DocumentFormat.eur(new BigDecimal("300")),
                PdfPurchaseRenderer.payableView(ledger, payable, null, true).paidInspectionViaSupplier());
        assertNull(PdfPurchaseRenderer.payableView(ledger, payable, null, false).paidInspectionViaSupplier());
        assertNull(PdfPurchaseRenderer.payableView(List.of(goods, lab), payable, null, true).paidInspectionViaSupplier(),
                "no inspection travelled with the goods");

        var freight = new be.enrosed.sourcing.domain.PurchasePayment(1L, 1L, LocalDate.of(2026, 9, 1),
                new BigDecimal("300"), Currency.EUR, new BigDecimal("300"), "Zeevracht", null, null,
                be.enrosed.sourcing.domain.PurchasePayment.Payee.SUPPLIER, false,
                be.enrosed.sourcing.domain.PaymentTerms.Moment.FREIGHT);
        assertEquals("Termijn: Zeevracht (CIF)", PdfPurchaseRenderer.paymentScopeLabel(freight));
    }

    /**
     * Round-2 screen check: no average over different products. One product
     * keeps its figure (a set per set and per stuk); several show the range
     * of their own figures, per stuk when sets sit next to pieces.
     */
    @Test
    void theRowsUnderTheLandedTotalNeverAverageDifferentProducts() {
        var costing = PdfPurchaseRendererRenderTest.costing(2, 1L);
        var first = costing.lines().get(0);
        var second = costing.lines().get(1);
        Product roses = withPackaging(1L, new be.enrosed.catalog.domain.Packaging(
                be.enrosed.catalog.domain.PackagingKind.DISPLAY, Dimensions.empty(), null, 12,
                be.enrosed.catalog.domain.SalesUnit.DISPLAY, "roos"));
        Product six = withPackaging(2L, new be.enrosed.catalog.domain.Packaging(
                be.enrosed.catalog.domain.PackagingKind.DISPLAY, Dimensions.empty(), null, 6,
                be.enrosed.catalog.domain.SalesUnit.DISPLAY, "roos"));
        Product bowl = withPackaging(2L, be.enrosed.catalog.domain.Packaging.none());

        var pieces = PdfPurchaseRenderer.unitRows(costing, java.util.Map.of(), 2, false);
        assertEquals(1, pieces.size());
        assertEquals("Per stuk", pieces.getFirst().label());
        assertEquals("2 producten", pieces.getFirst().note());
        assertEquals(PdfPurchaseRenderer.rangeText(first.landedUnitEur().min(second.landedUnitEur()),
                first.landedUnitEur().max(second.landedUnitEur())), pieces.getFirst().value());
        assertTrue(pieces.getFirst().value().contains(" – "), pieces.getFirst().value());

        BigDecimal rosePiece = PdfPurchaseRenderer.landedPieceEur(first, roses);
        assertEquals(first.totalEur().divide(BigDecimal.valueOf(first.quantity() * 12L), 4,
                java.math.RoundingMode.HALF_UP), rosePiece);
        var setAndBowl = PdfPurchaseRenderer.unitRows(costing, java.util.Map.of(1L, roses, 2L, bowl), 2, false);
        assertEquals(1, setAndBowl.size());
        assertEquals("2 producten · sets per stuk", setAndBowl.getFirst().note());
        assertEquals(PdfPurchaseRenderer.rangeText(rosePiece.min(second.landedUnitEur()),
                rosePiece.max(second.landedUnitEur())), setAndBowl.getFirst().value());

        var twoSets = PdfPurchaseRenderer.unitRows(costing, java.util.Map.of(1L, roses, 2L, six), 2, false);
        assertEquals(List.of("Per set", "Per stuk"), twoSets.stream().map(PdfPurchaseRenderer.UnitRow::label).toList());
        BigDecimal sixPiece = PdfPurchaseRenderer.landedPieceEur(second, six);
        assertEquals(PdfPurchaseRenderer.rangeText(rosePiece.min(sixPiece), rosePiece.max(sixPiece)),
                twoSets.get(1).value());

        var one = PdfPurchaseRendererRenderTest.costing(1, 1L);
        var piece = PdfPurchaseRenderer.unitRows(one, java.util.Map.of(), 1, true);
        assertEquals(List.of(new PdfPurchaseRenderer.UnitRow("Gemiddeld per stuk", null,
                be.enrosed.shared.DocumentFormat.unit(one.totals().averageUnitEur()))), piece);
        var set = PdfPurchaseRenderer.unitRows(one, java.util.Map.of(1L, roses), 1, true);
        assertEquals(new PdfPurchaseRenderer.UnitRow("Per set", null,
                be.enrosed.shared.DocumentFormat.unit(one.totals().averageUnitEur())), set.get(0));
        assertEquals("12 rozen per set", set.get(1).note());

        assertEquals("4,00 EUR", PdfPurchaseRenderer.rangeText(new BigDecimal("4.0000"), new BigDecimal("4.0000")),
                "equal ends: one figure");
        assertNull(PdfPurchaseRenderer.landedPieceEur(first, bowl), "a piece has no second figure");
    }

    /** Different duty rates are named as a range; one effective rate over them would be an average. */
    @Test
    void differentDutyRatesAreARangeNotAnAverage() {
        var alike = PdfPurchaseRendererRenderTest.costing(2, 1L);
        assertNull(PdfPurchaseRenderer.dutyRateRange(alike), "one rate for every line: the effective rate stays");
        var lines = new java.util.ArrayList<>(alike.lines());
        var second = lines.get(1);
        lines.set(1, new be.enrosed.sourcing.domain.LandedCost.Line(second.productId(), second.productName(),
                second.quantity(), second.cartons(), second.cbm(), second.goodsUsd(), second.goodsEur(),
                second.originEur(), second.freightEur(), second.customsValueEur(), new BigDecimal("12.0"),
                second.dutySource(), second.dutyEur(), second.destinationEur(), second.extraRevenueEur(),
                second.totalEur(), second.landedUnitEur(), second.cbmShare(), second.valueShare(),
                second.pieceShare(), second.separateEur()));
        var mixed = new be.enrosed.sourcing.domain.LandedCost(lines, alike.totals(), alike.containerFill());
        assertEquals("5 % – 12 % per product", PdfPurchaseRenderer.dutyRateRange(mixed).replace('\u00A0', ' '));
    }

    private static Product withPackaging(long id, be.enrosed.catalog.domain.Packaging packaging) {
        return new Product(id, "SKU-" + id, "Product " + id, Dimensions.empty(), packaging,
                null, null, null, null, null, 7L, true,
                null, null, null, 0, true,
                null, null, be.enrosed.catalog.domain.PublicationState.DRAFT,
                be.enrosed.catalog.domain.PublicationState.DRAFT,
                Barcodes.none(), null, Carton.empty(), BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of(), false);
    }

    private static be.enrosed.sourcing.domain.PurchaseOrder order(String goods, String transport) {
        return new be.enrosed.sourcing.domain.PurchaseOrder(
                1L, "PO-PDF", null, 1L, LocalDate.now(),
                be.enrosed.sourcing.domain.PurchaseOrderStatus.CONCEPT,
                be.enrosed.sourcing.domain.ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal(goods), new BigDecimal(transport),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
    }
}
