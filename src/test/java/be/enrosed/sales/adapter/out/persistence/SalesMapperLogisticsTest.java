package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.DeliveryCalculator;
import be.enrosed.sales.application.PalletCalculator;
import be.enrosed.sales.application.SalesPricingCalculator;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesMapperLogisticsTest {

    @Test
    void looseModeRoundTripPreservesHiddenManualPallets() {
        SalesOrder source = order(LoadMode.LOOSE_CARTONS, PalletProfile.BLOCK_120X100,
                decimal("245"), FreightPricingStrategy.PER_CBM, decimal("88"), null,
                List.of(new OrderPallet(null, "Bewaarde indeling", "Blokpallet", 210,
                        List.of(new OrderPallet.Item(1L, 3)))));
        SalesEntities.SalesOrderEntity entity = new SalesEntities.SalesOrderEntity();

        SalesMapper.apply(source, entity);
        SalesOrder restored = SalesMapper.toDomain(entity);

        assertEquals(LoadMode.LOOSE_CARTONS, restored.loadMode());
        assertEquals(PalletProfile.BLOCK_120X100, restored.palletProfile());
        assertEquals(decimal("245"), restored.maxPalletHeightCm());
        assertEquals(FreightPricingStrategy.PER_CBM, restored.freightPricingStrategy());
        assertEquals(decimal("88"), restored.freightRatePerCbmEur());
        assertEquals(1, restored.pallets().size());
        assertEquals(3, restored.pallets().getFirst().items().getFirst().cartons());
    }

    @Test
    void legacyNullColumnsResolveToOriginalBehaviour() {
        SalesEntities.SalesOrderEntity entity = new SalesEntities.SalesOrderEntity();
        entity.id = 1L;
        entity.manualFreightEur = decimal("75");

        SalesOrder restored = SalesMapper.toDomain(entity);

        assertEquals(LoadMode.PALLETS, restored.loadMode());
        assertEquals(PalletProfile.EURO_120X80, restored.palletProfile());
        assertEquals(FreightPricingStrategy.FIXED, restored.freightPricingStrategy());
    }

    @Test
    void legacyPalletTypeAxesDisplayAsBreedteByDiepte() {
        assertEquals("Blokpallet 120×100",
                new OrderPallet(null, null, "Blokpallet 100×120", null, null).type());
        assertEquals("Halve pallet 80×60",
                new OrderPallet(null, null, "Halve pallet 60×80", null, null).type());
        assertEquals("Europallet",
                new OrderPallet(null, null, null, null, null).type());
    }

    @Test
    void websiteUnknownCartonRequestRoundTripsWithoutFalsePiecePricing() {
        LocalDate today = LocalDate.now();
        SalesOrder source = new SalesOrder(7L, "ENR-2026-0007", 2L, "BE",
                today, today.plusDays(30), QuoteStatus.CONCEPT, "EXW", null, null,
                MarkupMode.PRODUCT, decimal("45"), null, null, null, null, null, 0,
                null, null, null,
                "[WEBSITE_AANVRAAG] ENR-2026-0007\n"
                        + "[DOOSINHOUD_TE_BEPALEN] productId=3; sku=NO-CARTON; cartons=4; "
                        + "quantityPieces=TE_BEPALEN",
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.PICKUP, null, null, null,
                DocumentType.OFFERTE, null, null, null, null,
                List.of(new SalesOrderLine(null, 3L, 0, decimal("10"), null, null)),
                List.of());
        SalesEntities.SalesOrderEntity entity = new SalesEntities.SalesOrderEntity();

        SalesMapper.apply(source, entity);
        SalesOrder restored = SalesMapper.toDomain(entity);

        assertEquals(0, restored.lines().getFirst().quantity());
        assertEquals(source.internalNotes(), restored.internalNotes());
        Product product = new Product(3L, "NO-CARTON", "No carton", Dimensions.empty(),
                null, null, 1L, 1L, true, Barcodes.none(), null, Carton.empty(),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, decimal("1"), "test",
                decimal("45"), decimal("10"), 0, List.of(), List.of());
        SalesPricingCalculator calculator = new SalesPricingCalculator(
                new PalletCalculator(), new DeliveryCalculator());
        PricedOrder priced = calculator.price(restored, java.util.Map.of(3L, product),
                new SalesPricingCalculator.Context(
                        new Country("BE", "België", BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO, decimal("21"), 0, true),
                        null, PalletSpec.euro(), List.of(), List.of(), null));
        assertEquals(0, priced.lines().getFirst().quantity());
        assertEquals(decimal("0.00"), priced.lines().getFirst().net());
        assertEquals(decimal("0.00"), priced.totals().totalInclVat());
    }

    private static SalesOrder order(LoadMode loadMode, PalletProfile profile,
                                    BigDecimal maxHeight, FreightPricingStrategy strategy,
                                    BigDecimal perCbm, BigDecimal fixed,
                                    List<OrderPallet> pallets) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "Q-MAP", 1L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, "", MarkupMode.PRODUCT, decimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, fixed,
                loadMode, profile, maxHeight, strategy, perCbm,
                null, null, null, null, null, null, null,
                List.of(new SalesOrderLine(null, 1L, 3, null, null, null)), pallets);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
