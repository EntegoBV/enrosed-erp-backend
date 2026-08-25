package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SalesPricingCalculatorTest {

    private final SalesPricingCalculator calculator =
            new SalesPricingCalculator(new PalletCalculator(), new DeliveryCalculator());

    @Test
    void looseCartonsKeepOuterCartonCbmAndIgnoreStoredPallets() {
        Product product = product(1L, "SKU-1", carton("10", "10", "10", 10, "2"));
        SalesOrder order = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.PER_CBM,
                null, decimal("100"), FreightState.BEREKEND,
                List.of(new SalesOrderLine(null, 1L, 20, null, null, null)),
                List.of(new OrderPallet(null, "Bewaarde indeling", "Europallet", 100,
                        List.of(new OrderPallet.Item(1L, 2)))));

        PricedOrder priced = price(order, Map.of(1L, product));

        assertEquals(decimal("0.002"), priced.totals().cbm());
        assertEquals(decimal("0.20"), priced.totals().freight());
        assertEquals(0, priced.totals().palletsStrict());
        assertEquals(0, priced.totals().palletsManual());
        assertEquals(0, priced.lines().getFirst().pallets());
        assertEquals(0, priced.lines().getFirst().palletLayers());
        assertNull(priced.validation().freightPricingIssue());
    }

    @Test
    void perCbmFreightUsesExactSumBeforeDisplayRounding() {
        Product first = product(1L, "SMALL-1", carton("7.4", "7.4", "7.4", 1, "0"));
        Product second = product(2L, "SMALL-2", carton("7.4", "7.4", "7.4", 1, "0"));
        SalesOrder order = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.PER_CBM,
                null, decimal("10000"), FreightState.BEREKEND,
                List.of(new SalesOrderLine(null, 1L, 1, null, null, null),
                        new SalesOrderLine(null, 2L, 1, null, null, null)),
                List.of());

        PricedOrder priced = price(order, Map.of(1L, first, 2L, second));

        assertEquals(decimal("0.000"), priced.lines().get(0).cbm());
        assertEquals(decimal("0.000"), priced.lines().get(1).cbm());
        assertEquals(decimal("0.001"), priced.totals().cbm());
        assertEquals(decimal("8.10"), priced.totals().freight());
    }

    @Test
    void countryPalletFixedAndPendingStrategiesRemainDistinct() {
        Product product = product(1L, "PALLET", carton("40", "40", "50", 1, "1"));
        List<SalesOrderLine> lines = List.of(
                new SalesOrderLine(null, 1L, 24, null, null, null));

        PricedOrder country = price(order(LoadMode.PALLETS,
                FreightPricingStrategy.COUNTRY_PALLET, null, null,
                FreightState.BEREKEND, lines, List.of()), Map.of(1L, product));
        assertEquals(1, country.totals().palletsStrict());
        assertEquals(decimal("250.00"), country.totals().freight());
        assertEquals(decimal("35.00"), country.totals().handling());

        PricedOrder fixed = price(order(LoadMode.PALLETS,
                FreightPricingStrategy.FIXED, decimal("123"), null,
                FreightState.BEREKEND, lines, List.of()), Map.of(1L, product));
        assertEquals(decimal("123.00"), fixed.totals().freight());
        assertFalse(fixed.totals().freightIsMinimum());

        PricedOrder pending = price(order(LoadMode.PALLETS,
                FreightPricingStrategy.COUNTRY_PALLET, null, null,
                FreightState.TE_BEPALEN, lines, List.of()), Map.of(1L, product));
        assertEquals(decimal("0.00"), pending.totals().freight());
        assertEquals(decimal("0.00"), pending.totals().handling());
    }

    @Test
    void nullFieldsKeepLegacyLoadAndFreightSemantics() {
        SalesOrder country = orderWithRawLogistics(null, null, null, null);
        assertEquals(LoadMode.PALLETS, country.loadMode());
        assertEquals(PalletProfile.EURO_120X80, country.palletProfile());
        assertEquals(FreightPricingStrategy.COUNTRY_PALLET, country.freightPricingStrategy());

        SalesOrder fixed = orderWithRawLogistics(null, null, null, decimal("75"));
        assertEquals(FreightPricingStrategy.FIXED, fixed.freightPricingStrategy());
    }

    @Test
    void customerLinePalletCountFollowsManualPhysicalLayout() {
        List<OrderPallet> layout = List.of(
                new OrderPallet(null, "P1", "Europallet", 100,
                        List.of(new OrderPallet.Item(1L, 2), new OrderPallet.Item(2L, 1))),
                new OrderPallet(null, "P2", "Europallet", 100,
                        List.of(new OrderPallet.Item(1L, 1))));
        SalesOrder manual = order(LoadMode.PALLETS, FreightPricingStrategy.FIXED,
                BigDecimal.ZERO, null, FreightState.BEREKEND, List.of(), layout);

        assertEquals(2, manual.palletPositionsForProduct(1L, 7));
        assertEquals(1, manual.palletPositionsForProduct(2L, 7));

        SalesOrder automatic = order(LoadMode.PALLETS, FreightPricingStrategy.FIXED,
                BigDecimal.ZERO, null, FreightState.BEREKEND, List.of(), List.of());
        assertEquals(7, automatic.palletPositionsForProduct(1L, 7));

        SalesOrder loose = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.FIXED,
                BigDecimal.ZERO, null, FreightState.BEREKEND, List.of(), layout);
        assertEquals(0, loose.palletPositionsForProduct(1L, 7));
    }

    @Test
    void unknownInventoryDoesNotPretendZeroStockOrAQuantityShortfall() {
        Product product = product(1L, "UNKNOWN-STOCK", carton("10", "10", "10", 10, "2"))
                .withCanonicalIdentity(null, null, null, 0, false);
        SalesOrder order = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.FIXED,
                BigDecimal.ZERO, null, FreightState.BEREKEND,
                List.of(new SalesOrderLine(null, 1L, 20, null, null, null)), List.of());

        PricedOrder.Line line = price(order, Map.of(1L, product)).lines().getFirst();

        assertFalse(line.inventoryKnown());
        assertFalse(line.inStock());
        assertNull(line.stockQuantity());
        assertNull(line.shortfall());
        assertNull(line.deliveryDate());
        assertNull(line.deliveryWeek());
        assertEquals("Voorraad nog niet bevestigd", line.deliveryExplanation());
    }

    @Test
    void internalLineShowsPlainNameWhileCustomerLineKeepsFullDescription() {
        Product product = new Product(
                1L, "SIZE-XL", "Roos",
                new Dimensions(decimal("12"), decimal("8"), decimal("25")),
                "Rood", "Beschrijving", 1L, 1L, true,
                Barcodes.none(), null, carton("40", "30", "20", 1, "1"),
                BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                decimal("1"), "test", decimal("45"), null, 1000,
                List.of(), List.of())
                .withVariantAttributes("Rood", "XL", "#A91F32");
        SalesOrder order = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.FIXED,
                BigDecimal.ZERO, null, FreightState.BEREKEND,
                List.of(new SalesOrderLine(null, product.id(), 1, null, null, null)), List.of());

        PricedOrder.Line line = price(order, Map.of(product.id(), product)).lines().getFirst();

        /* Internal screens read the short label; dimensions have their own
           columns there. The quote and the portal keep the full description. */
        assertEquals("Roos - Rood", line.description());
        assertEquals("Roos - B × D × H: 12 × 8 × 25 cm - Rood - XL", line.customerDescription());
    }

    private PricedOrder price(SalesOrder order, Map<Long, Product> products) {
        Country country = new Country("BE", "België", BigDecimal.ZERO,
                decimal("90"), decimal("250"), decimal("35"), decimal("21"), 1, true);
        return calculator.price(order, products, new SalesPricingCalculator.Context(
                country, null, PalletSpec.euro(), List.of(), List.of(), null));
    }

    private static SalesOrder order(LoadMode loadMode, FreightPricingStrategy strategy,
                                    BigDecimal fixed, BigDecimal perCbm, FreightState freight,
                                    List<SalesOrderLine> lines, List<OrderPallet> pallets) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "Q-1", 1L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, "", MarkupMode.PRODUCT, decimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, freight, fixed,
                loadMode, PalletProfile.EURO_120X80, null, strategy, perCbm,
                lines, pallets);
    }

    private static SalesOrder orderWithRawLogistics(LoadMode loadMode, PalletProfile profile,
                                                     FreightPricingStrategy strategy,
                                                     BigDecimal manualFreight) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "Q-LEGACY", 1L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, "", MarkupMode.PRODUCT, decimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, manualFreight,
                loadMode, profile, null, strategy, null, List.of(), List.of());
    }

    private static Product product(long id, String sku, Carton carton) {
        return new Product(id, sku, sku, Dimensions.empty(), null, null,
                1L, 1L, true, Barcodes.none(), null, carton,
                BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                decimal("1"), "test", decimal("45"), null, 1000,
                List.of(), List.of());
    }

    private static Carton carton(String length, String width, String height,
                                 int pieces, String weight) {
        return new Carton(new Dimensions(decimal(length), decimal(width), decimal(height)),
                pieces, decimal(weight));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
