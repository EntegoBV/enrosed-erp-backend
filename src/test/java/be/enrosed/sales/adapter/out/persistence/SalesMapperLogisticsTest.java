package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.domain.*;
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
                List.of(new SalesOrderLine(null, 1L, 3, null, null, null)), pallets);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
