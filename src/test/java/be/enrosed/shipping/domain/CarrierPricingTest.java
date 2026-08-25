package be.enrosed.shipping.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staffel arithmetic against the ESTA Netherlands lane: zone from the
 * postcode (nearest when unlisted), the lowest rung that carries the
 * shipment, diesel on top.
 */
class CarrierPricingTest {

    private static Carrier esta() {
        List<CarrierZone> zones = List.of(
                new CarrierZone(null, "Zone 1", "10-15,18-44,46-59,65-73", 0),
                new CarrierZone(null, "Zone 2", "16-17,60-64,74-99", 1),
                new CarrierZone(null, "Zone 3", "45", 2));
        List<CarrierTier> tiers = List.of(
                tier("0.5", null, 350, List.of(52, 76, 94)),
                tier("1", null, 700, List.of(62, 83, 124)),
                tier(null, "1", 875, List.of(72, 105, 129)),
                tier("2", null, 1400, List.of(118, 133, 180)),
                tier("3", null, 2100, List.of(150, 165, 218)));
        CarrierLane lane = new CarrierLane(null, "NL", null, null, null, zones, tiers);
        return new Carrier(1L, "ESTA", null, true, new BigDecimal("20"), null, null, List.of(lane));
    }

    private static CarrierTier tier(String ep, String bp, int kg, List<Integer> prices) {
        return new CarrierTier(null,
                ep == null ? null : new BigDecimal(ep),
                bp == null ? null : new BigDecimal(bp),
                null, BigDecimal.valueOf(kg), 0,
                prices.stream().map(BigDecimal::valueOf).toList());
    }

    @Test
    void pricesTwoEuropalletsInZoneOneWithDiesel() {
        CarrierQuote quote = CarrierPricing.quote(esta(), "NL", "1082 XZ", 2,
                CarrierPricing.PalletKind.EUROPALLET, new BigDecimal("900"));
        assertEquals(new BigDecimal("118.00"), quote.baseEur());
        assertEquals(new BigDecimal("23.60"), quote.dieselEur());
        assertEquals(new BigDecimal("141.60"), quote.totalEur());
        assertEquals("Zone 1", quote.zoneName());
        assertTrue(quote.postcodeMatched());
    }

    @Test
    void weightPushesTheShipmentARungUp() {
        /* One europallet but 1.200 kg: the 700 kg rung cannot carry it. */
        CarrierQuote quote = CarrierPricing.quote(esta(), "NL", "1082", 1,
                CarrierPricing.PalletKind.EUROPALLET, new BigDecimal("1200"));
        assertEquals(new BigDecimal("118.00"), quote.baseEur());
    }

    @Test
    void blockpalletsUseTheirOwnRungs() {
        CarrierQuote quote = CarrierPricing.quote(esta(), "NL", "4500", 1,
                CarrierPricing.PalletKind.BLOCKPALLET, null);
        assertEquals(new BigDecimal("129.00"), quote.baseEur());
        assertEquals("Zone 3", quote.zoneName());
    }

    @Test
    void unlistedPostcodeTakesTheNearestPrefix() {
        /* No Dutch postcode starts with 00; prefix 10 is the closest listed. */
        CarrierQuote quote = CarrierPricing.quote(esta(), "NL", "0099", 1,
                CarrierPricing.PalletKind.EUROPALLET, null);
        assertEquals("Zone 1", quote.zoneName());
        assertFalse(quote.postcodeMatched());
    }

    @Test
    void aShipmentBeyondTheLadderYieldsNoPrice() {
        assertNull(CarrierPricing.quote(esta(), "NL", "1082", 40,
                CarrierPricing.PalletKind.EUROPALLET, null));
    }

    @Test
    void unknownLaneYieldsNoPrice() {
        assertNull(CarrierPricing.quote(esta(), "XX", "1082", 1,
                CarrierPricing.PalletKind.EUROPALLET, null));
    }
}
