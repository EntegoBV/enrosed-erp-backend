package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Dimensions and limits of the pallet we ship on.
 * {@code maxHeightCm} is the total loaded height including
 * {@code baseHeightCm}; the calculator subtracts the wood before counting
 * carton layers.
 */
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
                new BigDecimal("14.4"), BigDecimal.valueOf(260), BigDecimal.valueOf(700));
    }
}
