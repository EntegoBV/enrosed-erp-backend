package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.HsCodeService;
import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test on the landed-cost calculation.
 *
 * The figures come from the user's "Kostprijs Berekening" Excel. When this
 * test deviates, the calculation engine is broken - not the test.
 */
class LandedCostCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("0.89");

    private LandedCostCalculator calculator(BigDecimal dutyRate) {
        HsCodeService hsCodes = mock(HsCodeService.class);
        when(hsCodes.dutyRateFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(dutyRate);
        return new LandedCostCalculator(new CurrencyConverter(), hsCodes);
    }

    private Product preservedRose() {
        return new Product(
                1L, "ENR-P11", "Preserved rose with stem",
                new Dimensions(new BigDecimal("31.5"), new BigDecimal("23.3"), new BigDecimal("36.5")),
                "Red", null, 1L, 1L, true,
                Barcodes.none(), "0603.90.00",
                new Carton(new Dimensions(new BigDecimal("68"), new BigDecimal("50"), new BigDecimal("40")),
                        4, new BigDecimal("10")),
                new BigDecimal("19.2"), Currency.USD, new BigDecimal("0.5"),
                null, null, new BigDecimal("35"), null, 0, List.of(), List.of());
    }

    private PurchaseOrder excelOrder() {
        return new PurchaseOrder(
                1L, "PO-2026-002", null, 1L, LocalDate.of(2026, 7, 3),
                PurchaseOrderStatus.ONDERWEG, ContainerType.FORTY_HQ,
                new BigDecimal("0.1385"), RATE, RATE,
                new BigDecimal("3717"),
                /* FOB supplier: no costs on our side in China. */
                BigDecimal.ZERO, Currency.USD,
                /* "Local costs Rotterdam/warehouse" from the Excel. */
                new BigDecimal("1155"),
                new BigDecimal("10"),
                new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", "",
                List.of(new PurchaseOrderLine(1L, 1L, 1968, null, null, null, 1968)));
    }

    @Test
    @DisplayName("reproduceert de Excel-kostprijsberekening tot op de cent")
    void reproducesExcel() {
        LandedCost result = calculator(new BigDecimal("10")).calculate(
                excelOrder(), Map.of(1L, preservedRose()));

        LandedCost.Totals totals = result.totals();

        /* Excel G14: 1968 x (19,2 + 0,5) = 38.769,60 USD */
        assertEquals(new BigDecimal("38769.60"), totals.goodsUsd());
        /* Excel C19: goederen x koers */
        assertEquals(new BigDecimal("34504.94"), totals.goodsEur());
        /* Excel C20: 3717 x 0,89 */
        assertEquals(new BigDecimal("3308.13"), totals.freightEur());
        /* Excel C21: basis invoerrechten */
        assertEquals(new BigDecimal("37813.07"), totals.customsValueEur());
        /* Excel C22: basis x 10 % */
        assertEquals(new BigDecimal("3781.31"), totals.dutyEur());
        /* Excel C25: alles samen */
        assertEquals(new BigDecimal("44749.38"), totals.totalEur());
        /* Excel C26 / I14: prijs per set */
        assertEquals(new BigDecimal("22.7385"), totals.averageUnitEur());

        assertEquals(1968, totals.pieces());
        assertEquals(492, totals.cartons());
    }

    @Test
    @DisplayName("20 ft, 40 ft en 40 ft HQ gebruiken hun eigen laadruimte zonder kosten te schalen")
    void selectedContainerControlsFillButNeverInventsExtraFreight() {
        PurchaseOrder base = excelOrder();
        LandedCost twenty = calculator(new BigDecimal("10")).calculate(
                withContainerType(base, ContainerType.TWENTY_GP), Map.of(1L, preservedRose()));
        LandedCost forty = calculator(new BigDecimal("10")).calculate(
                withContainerType(base, ContainerType.FORTY_GP), Map.of(1L, preservedRose()));
        LandedCost highCube = calculator(new BigDecimal("10")).calculate(
                withContainerType(base, ContainerType.FORTY_HQ), Map.of(1L, preservedRose()));

        assertEquals(new BigDecimal("66.912"), twenty.containerFill().usedCbm());
        assertEquals(new BigDecimal("28"), twenty.containerFill().capacityCbm());
        assertEquals(new BigDecimal("239.0"), twenty.containerFill().fillPercent());
        assertEquals(new BigDecimal("38.912"), twenty.containerFill().overflowCbm());
        assertEquals(3, twenty.containerFill().minimumContainerCount());

        assertEquals(new BigDecimal("58"), forty.containerFill().capacityCbm());
        assertEquals(new BigDecimal("115.4"), forty.containerFill().fillPercent());
        assertEquals(new BigDecimal("8.912"), forty.containerFill().overflowCbm());
        assertEquals(2, forty.containerFill().minimumContainerCount());

        assertEquals(new BigDecimal("68"), highCube.containerFill().capacityCbm());
        assertEquals(new BigDecimal("98.4"), highCube.containerFill().fillPercent());
        assertEquals(new BigDecimal("1.088"), highCube.containerFill().freeCbm());
        assertEquals(1, highCube.containerFill().minimumContainerCount());

        assertEquals(twenty.totals().freightEur(), forty.totals().freightEur());
        assertEquals(forty.totals().freightEur(), highCube.totals().freightEur());
        assertEquals(twenty.totals().totalEur(), highCube.totals().totalEur(),
                "the entered quote is a fixed order cost, not a volume-derived estimate");
    }

    @Test
    @DisplayName("bestelhoeveelheden herverdelen vaste orderkosten zonder ze op te schalen")
    void orderedQuantityBasisRecalculatesWholeOrderWithoutScalingFixedCosts() {
        PurchaseOrder base = excelOrder();
        Product first = preservedRose();
        Product second = new Product(
                2L, "ENR-P12", first.name(), first.dimensions(), first.colour(),
                first.description(), first.categoryId(), first.supplierId(), first.active(),
                first.barcodes(), first.hsCode(), first.carton(), first.exwPrice(),
                first.exwCurrency(), first.extraUnitCost(), first.landedCostEur(),
                first.landedCostSource(), first.markupPct(), first.fixedSalesPriceEur(),
                first.stockQuantity(), first.photos(), first.texts());
        List<PurchaseOrderLine> receivedShort = List.of(
                new PurchaseOrderLine(1L, 1L, 5, null, null, null, 10),
                new PurchaseOrderLine(2L, 2L, 5, null, null, null, 20));
        PurchaseOrder order = orderWith(base, receivedShort, false);
        LandedCostCalculator calculator = calculator(new BigDecimal("10"));

        LandedCost received = calculator.calculate(order, Map.of(1L, first, 2L, second));
        LandedCost ordered = calculator.calculateForOrderedQuantities(
                order, Map.of(1L, first, 2L, second));

        assertEquals(List.of(10, 20),
                ordered.lines().stream().map(LandedCost.Line::quantity).toList());
        assertEquals(30, ordered.totals().pieces());
        assertEquals(received.totals().originEur(), ordered.totals().originEur());
        assertEquals(received.totals().freightEur(), ordered.totals().freightEur());
        assertEquals(received.totals().destinationEur(), ordered.totals().destinationEur());
        assertEquals(received.totals().extraRevenueEur(), ordered.totals().extraRevenueEur());
        assertEquals(ordered.totals().freightEur(), ordered.lines().stream()
                .map(LandedCost.Line::freightEur).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal visibleDestination = ordered.lines().stream()
                .map(LandedCost.Line::destinationEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(visibleDestination.subtract(ordered.totals().destinationEur()).abs()
                        .compareTo(new BigDecimal("0.01")) <= 0,
                "alleen een cent afrondingsverschil tussen zichtbare regels en het vaste totaal");
        assertNotEquals(received.totals().totalEur().multiply(new BigDecimal("3.00")),
                ordered.totals().totalEur(),
                "vaste vracht en orderkosten mogen niet met 30/10 worden vermenigvuldigd");
    }

    @Test
    @DisplayName("lokale kosten aan de vertrekzijde tellen wel mee in de douanewaarde")
    void originCostsAreDutiable() {
        PurchaseOrder base = excelOrder();
        PurchaseOrder withOrigin = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(),
                new BigDecimal("1000"), Currency.EUR,
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), "Rotterdam", base.notes(), base.lines());

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                withOrigin, Map.of(1L, preservedRose()));

        /* Customs value rises by the full 1000 ... */
        assertEquals(new BigDecimal("38813.07"), result.totals().customsValueEur());
        /* ... and the import duty therefore by 100. */
        assertEquals(new BigDecimal("3881.31"), result.totals().dutyEur());
    }

    @Test
    @DisplayName("een DDP-regel krijgt geen rechten en geen deel van de containerkosten")
    void deliveredDutyPaidLineTakesNoRoadCosts() {
        PurchaseOrder base = excelOrder();
        /* The same article twice: one EXW line, one delivered duty paid. */
        Product exw = preservedRose();
        /* The calculator keys lines by product id, so the second article only needs another id. */
        Product ddpProduct = new Product(
                2L, "ENR-P12", exw.name(), exw.dimensions(), exw.colour(), exw.description(),
                exw.categoryId(), exw.supplierId(), exw.active(), exw.barcodes(), exw.hsCode(), exw.carton(),
                exw.exwPrice(), exw.exwCurrency(), exw.extraUnitCost(), exw.landedCostEur(), exw.landedCostSource(),
                exw.markupPct(), exw.fixedSalesPriceEur(), exw.stockQuantity(), exw.photos(), exw.texts());
        PurchaseOrder order = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.notes(),
                List.of(new PurchaseOrderLine(1L, 1L, 1000, null, null, null, 1000),
                        new PurchaseOrderLine(2L, 2L, 1000, null, null, null, 1000, PriceBasis.DDP)));

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                order, Map.of(1L, exw, 2L, ddpProduct));

        LandedCost.Line exwLine = result.lines().get(0);
        LandedCost.Line ddpLine = result.lines().get(1);
        assertEquals(BigDecimal.ZERO.setScale(2), ddpLine.dutyEur());
        assertEquals(BigDecimal.ZERO.setScale(2), ddpLine.freightEur());
        assertEquals(BigDecimal.ZERO.setScale(2), ddpLine.destinationEur());
        assertEquals("DDP - inbegrepen", ddpLine.dutySource());
        /* The whole container goes to the EXW line ... */
        assertEquals(result.totals().freightEur(), exwLine.freightEur());
        assertEquals(result.totals().destinationEur(), exwLine.destinationEur());
        /* ... while the Enrosed kost is still spread over both. */
        assertEquals(new BigDecimal("1000.00"), ddpLine.extraRevenueEur());
        /* A DDP piece costs its goods price plus its share of the Enrosed kost, nothing more. */
        assertEquals(ddpLine.goodsEur().add(ddpLine.extraRevenueEur()), ddpLine.totalEur());
        /* The goods total is what the factory is owed: both lines, DDP or not. */
        assertEquals(exwLine.goodsEur().add(ddpLine.goodsEur()), result.totals().goodsEur());
    }

    @Test
    @DisplayName("varianten van één reeks landen op dezelfde stukprijs; de schakelaar zet dat uit")
    void variantsOfOneSeriesShareOneUnitCost() {
        PurchaseOrder base = excelOrder();
        Product red = preservedRose();
        /* Same series (family 7), other colour, dearer at the factory and fewer pieces. */
        Product white = new Product(
                2L, "ENR-P12", red.name(), red.dimensions(), red.packaging(), "Wit", red.variantSize(),
                red.colourHex(), red.description(), red.categoryId(), red.supplierId(), red.active(),
                7L, null, null, 1, true, red.familyKey(), null, red.websiteStatus(), red.orderAppStatus(),
                red.barcodes(), red.hsCode(), red.carton(), new BigDecimal("25"), Currency.USD,
                red.extraUnitCost(), red.landedCostEur(), red.landedCostSource(), red.markupPct(),
                red.fixedSalesPriceEur(), red.stockQuantity(), red.photos(), red.texts());
        Product redInSeries = red.withCanonicalIdentity(7L, null, null, 0, true);
        List<PurchaseOrderLine> lines = List.of(
                new PurchaseOrderLine(1L, 1L, 1000, null, null, null, 1000),
                new PurchaseOrderLine(2L, 2L, 200, null, null, null, 200));
        Map<Long, Product> products = Map.of(1L, redInSeries, 2L, white);

        LandedCost grouped = calculator(new BigDecimal("10")).calculate(
                orderWith(base, lines, null), products);
        LandedCost apart = calculator(new BigDecimal("10")).calculate(
                orderWith(base, lines, false), products);

        assertEquals(grouped.lines().get(0).landedUnitEur(), grouped.lines().get(1).landedUnitEur(),
                "one series, one unit cost");
        assertEquals(grouped.totals().totalEur(), apart.totals().totalEur(),
                "grouping moves cost between variants, it never changes the container total");
        assertTrue(apart.lines().get(1).landedUnitEur().compareTo(apart.lines().get(0).landedUnitEur()) > 0,
                "switched off, the dearer variant stays dearer");
    }

    @Test
    @DisplayName("kostregels volgen de vaste variantpositie, niet de invoervolgorde")
    void costingLinesFollowCanonicalVariantPosition() {
        PurchaseOrder base = excelOrder();
        Product first = preservedRose().withCanonicalIdentity(7L, "red", null, 0, true);
        Product second = new Product(
                2L, "ENR-P12", first.name(), first.dimensions(), first.packaging(), "White",
                first.variantSize(), first.colourHex(), first.description(), first.categoryId(),
                first.supplierId(), first.active(), 7L, "white", null, 1, true,
                first.familyKey(), first.publicHandle(), first.websiteStatus(), first.orderAppStatus(),
                first.barcodes(), first.hsCode(), first.carton(), first.exwPrice(), first.exwCurrency(),
                first.extraUnitCost(), first.landedCostEur(), first.landedCostSource(), first.markupPct(),
                first.fixedSalesPriceEur(), first.stockQuantity(), first.photos(), first.texts());
        List<PurchaseOrderLine> reversed = List.of(
                new PurchaseOrderLine(2L, 2L, 100, null, null, null, 100),
                new PurchaseOrderLine(1L, 1L, 100, null, null, null, 100));

        LandedCost result = calculator(BigDecimal.TEN).calculate(
                orderWith(base, reversed, false), Map.of(1L, first, 2L, second));

        assertEquals(List.of(1L, 2L), result.lines().stream().map(LandedCost.Line::productId).toList());
    }

    @Test
    @DisplayName("de verdeelsleutel van de Enrosed kost verdeelt echt anders")
    void enrosedKostFollowsItsOwnAllocationKey() {
        PurchaseOrder base = excelOrder();
        Product rose = preservedRose();
        /* A second, standalone article: same carton, far fewer pieces. */
        Product other = new Product(
                2L, "ENR-P13", rose.name(), rose.dimensions(), rose.colour(), rose.description(),
                rose.categoryId(), rose.supplierId(), rose.active(), rose.barcodes(), rose.hsCode(), rose.carton(),
                rose.exwPrice(), rose.exwCurrency(), rose.extraUnitCost(), rose.landedCostEur(), rose.landedCostSource(),
                rose.markupPct(), rose.fixedSalesPriceEur(), rose.stockQuantity(), rose.photos(), rose.texts());
        List<PurchaseOrderLine> lines = List.of(
                new PurchaseOrderLine(1L, 1L, 1000, null, null, null, 1000),
                new PurchaseOrderLine(2L, 2L, 100, null, null, null, 100));
        Map<Long, Product> products = Map.of(1L, rose, 2L, other);

        LandedCost byPieces = calculator(BigDecimal.TEN).calculate(
                allocExtra(base, lines, Allocation.PIECES), products);
        LandedCost byValue = calculator(BigDecimal.TEN).calculate(
                allocExtra(base, lines, Allocation.VALUE), products);

        /* 2000 by pieces: 1000/1100 and 100/1100. */
        assertEquals(new BigDecimal("1818.18"), byPieces.lines().get(0).extraRevenueEur());
        assertEquals(new BigDecimal("181.82"), byPieces.lines().get(1).extraRevenueEur());
        /* Same goods price, so by value the split is the same - the key is read, not ignored. */
        assertEquals(byPieces.lines().get(0).extraRevenueEur(), byValue.lines().get(0).extraRevenueEur());
    }

    private static PurchaseOrder orderWith(PurchaseOrder base, List<PurchaseOrderLine> lines, Boolean groupVariants) {
        return new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.receivingLocationId(), groupVariants,
                base.notes(), lines);
    }

    private static PurchaseOrder withContainerType(PurchaseOrder base, ContainerType type) {
        return new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                type, base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.receivingLocationId(), base.groupVariants(),
                base.notes(), base.lines());
    }

    private static PurchaseOrder allocExtra(PurchaseOrder base, List<PurchaseOrderLine> lines, Allocation extra) {
        return new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), extra,
                base.departurePort(), base.destinationPort(), base.receivingLocationId(), false,
                base.notes(), lines);
    }

    @Test
    @DisplayName("lokale kosten aan de aankomstzijde blijven buiten de douanewaarde")
    void destinationCostsAreNotDutiable() {
        PurchaseOrder base = excelOrder();
        PurchaseOrder extraDestination = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur().add(new BigDecimal("1000")),
                base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), "Rotterdam", base.notes(), base.lines());

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                extraDestination, Map.of(1L, preservedRose()));

        /* Douanewaarde en invoerrecht blijven staan waar ze stonden ... */
        assertEquals(new BigDecimal("37813.07"), result.totals().customsValueEur());
        assertEquals(new BigDecimal("3781.31"), result.totals().dutyEur());
        /* ... only the grand total rises by exactly that 1000. */
        assertEquals(new BigDecimal("45749.38"), result.totals().totalEur());
    }

    @Test
    void untouchedHistoricalOrderKeepsDistinctGoodsAndTransportRates() {
        PurchaseOrder base = excelOrder();
        PurchaseOrder historical = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), new BigDecimal("0.80"), new BigDecimal("0.90"),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), BigDecimal.ZERO,
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.notes(), base.lines());

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                historical, Map.of(1L, preservedRose()));

        assertEquals(new BigDecimal("31015.68"), result.totals().goodsEur());
        assertEquals(new BigDecimal("3345.30"), result.totals().freightEur());
    }
}
