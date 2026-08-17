package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** Afmetingen en grenzen van de pallet waarop we verzenden. */
public record PalletSpec(
        String name,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal baseHeightCm,
        BigDecimal maxHeightCm,
        BigDecimal maxWeightKg
) {
    public static PalletSpec euro() {
        return new PalletSpec("Euro-pallet", BigDecimal.valueOf(120), BigDecimal.valueOf(80),
                new BigDecimal("14.4"), BigDecimal.valueOf(180), BigDecimal.valueOf(700));
    }
}
