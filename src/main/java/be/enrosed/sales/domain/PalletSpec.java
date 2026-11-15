package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Dimensions and limits of the pallet we ship on.
 * {@code maxHeightCm} is the total loaded height including
 * {@code baseHeightCm}; the calculator subtracts the wood before counting
 * carton layers. Legacy {@code lengthCm}/{@code widthCm} are displayed as
 * pallet Breedte/Diepte (B × D) without changing their value order.
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

    /**
     * The Deense kar (the CC Container of Container Centralen) as Palletberekening counts it:
     * omdozen on the base plate between the posts, 127 x 54,5 cm; the 16,5 cm under the inside
     * height counts as a pallet's wood; loaded at most 230 cm from the floor (Emre, 2026-11-15)
     * and 450 kg. Shelves are not counted. The same figures as karSpec(KAR_MAX_HEIGHT_CM) in the
     * ERP's pallet-calculator-model.ts.
     */
    public static PalletSpec deenseKar() {
        return new PalletSpec("Deense kar", BigDecimal.valueOf(127), new BigDecimal("54.5"),
                new BigDecimal("16.5"), BigDecimal.valueOf(230), BigDecimal.valueOf(450));
    }
}
