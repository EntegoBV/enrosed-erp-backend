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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regressietest op de kostprijsberekening.
 *
 * De cijfers komen uit de Excel "Kostprijs Berekening" van de klant. Wijkt
 * deze test af, dan is de rekenmotor stuk - niet de test.
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
                1L, "PO-2026-002", 1L, LocalDate.of(2026, 7, 3),
                PurchaseOrderStatus.ONDERWEG, ContainerType.FORTY_HQ,
                new BigDecimal("0.1385"), RATE, RATE,
                new BigDecimal("3717"),
                /* FOB-leverancier: geen kosten aan onze kant in China. */
                BigDecimal.ZERO, Currency.USD,
                /* "Lokale kosten Rotterdam/magazijn" uit de Excel. */
                new BigDecimal("1155"),
                new BigDecimal("10"),
                new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Rotterdam", "", List.of(new PurchaseOrderLine(1L, 1L, 1968, null, null, null, 1968)));
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
    @DisplayName("lokale kosten aan de vertrekzijde tellen wel mee in de douanewaarde")
    void originCostsAreDutiable() {
        PurchaseOrder base = excelOrder();
        PurchaseOrder withOrigin = new PurchaseOrder(
                base.id(), base.number(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(),
                new BigDecimal("1000"), Currency.EUR,
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                "Rotterdam", base.notes(), base.lines());

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                withOrigin, Map.of(1L, preservedRose()));

        /* Douanewaarde stijgt met de volle 1000 ... */
        assertEquals(new BigDecimal("38813.07"), result.totals().customsValueEur());
        /* ... en het invoerrecht dus met 100. */
        assertEquals(new BigDecimal("3881.31"), result.totals().dutyEur());
    }

    @Test
    @DisplayName("lokale kosten aan de aankomstzijde blijven buiten de douanewaarde")
    void destinationCostsAreNotDutiable() {
        PurchaseOrder base = excelOrder();
        PurchaseOrder extraDestination = new PurchaseOrder(
                base.id(), base.number(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur().add(new BigDecimal("1000")),
                base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                "Rotterdam", base.notes(), base.lines());

        LandedCost result = calculator(new BigDecimal("10")).calculate(
                extraDestination, Map.of(1L, preservedRose()));

        /* Douanewaarde en invoerrecht blijven staan waar ze stonden ... */
        assertEquals(new BigDecimal("37813.07"), result.totals().customsValueEur());
        assertEquals(new BigDecimal("3781.31"), result.totals().dutyEur());
        /* ... alleen het eindtotaal stijgt met precies die 1000. */
        assertEquals(new BigDecimal("45749.38"), result.totals().totalEur());
    }
}
