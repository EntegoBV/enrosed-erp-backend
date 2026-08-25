package be.enrosed.shipping.domain;

import java.math.BigDecimal;
import java.util.List;

/** One destination country within a carrier's tariff. */
public record CarrierLane(
        Long id,
        String countryCode,
        /** Extra percentage on this lane only (e.g. a seasonal surcharge). */
        BigDecimal surchargePct,
        /** Fixed extra per shipment on this lane (e.g. customs admin). */
        BigDecimal surchargeFixedEur,
        String surchargeNote,
        List<CarrierZone> zones,
        List<CarrierTier> tiers
) {
    public List<CarrierZone> zones() {
        return zones == null ? List.of() : zones;
    }

    public List<CarrierTier> tiers() {
        return tiers == null ? List.of() : tiers;
    }
}
