package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.sales.domain.PalletSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PalletCalculatorTest {

    private final PalletCalculator calculator = new PalletCalculator();

    @Test
    void mixedRowsFitFiveCartonsOnBlockPallet() {
        Carton carton = carton("58.5", "40", "34", "0");
        PalletSpec block = pallet("120", "100", "14.4", "260", "700");

        PalletCalculator.Fit fit = calculator.fit(carton, block);

        assertEquals(5, fit.perLayer());
        assertEquals(7, fit.layers());
        assertEquals(35, fit.cartonsPerPallet());
        assertEquals(new BigDecimal("252.4"), fit.fullPalletHeightCm());
        assertEquals("hoogte", fit.limitedBy());
    }

    @Test
    void lowerTotalHeightOverrideReducesLayerCount() {
        Carton carton = carton("58.5", "40", "34", "0");
        PalletSpec block = pallet("120", "100", "14.4", "220", "700");

        PalletCalculator.Fit fit = calculator.fit(carton, block);

        assertEquals(5, fit.perLayer());
        assertEquals(6, fit.layers());
        assertEquals(30, fit.cartonsPerPallet());
        assertEquals(new BigDecimal("218.4"), fit.fullPalletHeightCm());
    }

    @Test
    void totalHeightSubtractsPalletBaseBeforeCountingLayers() {
        Carton carton = carton("40", "40", "50", "0");
        PalletSpec pallet = pallet("120", "80", "14.4", "260", "700");

        PalletCalculator.Fit fit = calculator.fit(carton, pallet);

        assertEquals(6, fit.perLayer());
        assertEquals(4, fit.layers());
        assertEquals(24, fit.cartonsPerPallet());
        assertEquals(new BigDecimal("214.4"), fit.fullPalletHeightCm());
    }

    @Test
    void weightMayLimitCapacityInAPartialTopLayer() {
        Carton carton = carton("20", "20", "20", "1");
        PalletSpec pallet = pallet("120", "80", "14.4", "260", "95");

        PalletCalculator.Fit fit = calculator.fit(carton, pallet);

        assertEquals(24, fit.perLayer());
        assertEquals(4, fit.layers());
        assertEquals(95, fit.cartonsPerPallet());
        assertEquals(new BigDecimal("94.4"), fit.fullPalletHeightCm());
        assertEquals("gewicht", fit.limitedBy());
    }

    private static Carton carton(String length, String width, String height, String weight) {
        return new Carton(new Dimensions(decimal(length), decimal(width), decimal(height)),
                1, decimal(weight));
    }

    private static PalletSpec pallet(String length, String width, String base,
                                     String maxHeight, String maxWeight) {
        return new PalletSpec("test", decimal(length), decimal(width), decimal(base),
                decimal(maxHeight), decimal(maxWeight));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
