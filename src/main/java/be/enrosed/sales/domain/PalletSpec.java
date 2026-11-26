package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Dimensions and limits of the pallet we ship on.
 * {@code maxHeightCm} is the total loaded height including
 * {@code baseHeightCm}; the calculator subtracts the wood before counting
 * carton layers. Legacy {@code lengthCm}/{@code widthCm} are displayed as
 * pallet Breedte/Diepte (B × D) without changing their value order.
 *
 * Spill (Emre, 2026-11-26: "Je mag ook standaard maximaal 2cm spill doen", 2 cm per kant): an omdoos
 * may stick out past an edge of the floor by at most the spill of that edge. {@code lengthCm} and
 * {@code widthCm} stay the NOMINAL floor, the one screens and documents show (120 × 80, never
 * 124 × 84). The counts read the LOAD floor instead ({@link #loadLengthCm()}, {@link #loadWidthCm()}):
 * the nominal floor plus the spill at both ends of that axis. Without a spill the load floor is the
 * nominal floor itself, so every figure is what it was before the spill existed. The height, the base
 * and the weight limit never take a spill.
 */
public record PalletSpec(
        String name,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal baseHeightCm,
        BigDecimal maxHeightCm,
        BigDecimal maxWeightKg,
        /** Cm an omdoos may pass each END of the length; null or 0: none (the Deense kar: posts at its short ends). */
        BigDecimal lengthSpillCm,
        /** Cm an omdoos may pass each SIDE of the width; null or 0: none. */
        BigDecimal widthSpillCm
) {
    /** A spec without spill: loaded on its nominal floor. */
    public PalletSpec(String name, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal baseHeightCm,
                      BigDecimal maxHeightCm, BigDecimal maxWeightKg) {
        this(name, lengthCm, widthCm, baseHeightCm, maxHeightCm, maxWeightKg, null, null);
    }

    public static PalletSpec euro() {
        return new PalletSpec("Euro-pallet", BigDecimal.valueOf(120), BigDecimal.valueOf(80),
                new BigDecimal("14.4"), BigDecimal.valueOf(260), BigDecimal.valueOf(700));
    }

    /**
     * The Deense kar (the CC Container of Container Centralen) as Palletberekening counts it:
     * omdozen on the base plate between the posts, 127 x 54,5 cm; the 16,5 cm under the inside
     * height counts as a pallet's wood; loaded at most 230 cm from the floor (Emre, 2026-11-15)
     * and 450 kg. Shelves are not counted. The same figures as karSpec(KAR_MAX_HEIGHT_CM) in the
     * ERP's pallet-calculator-model.ts. Without a spill: the standard kar that counts with the
     * standard spill is SalesSettings.standardKar().
     */
    public static PalletSpec deenseKar() {
        return new PalletSpec("Deense kar", BigDecimal.valueOf(127), new BigDecimal("54.5"),
                new BigDecimal("16.5"), BigDecimal.valueOf(230), BigDecimal.valueOf(450));
    }

    /** The length the counts load on: the nominal length plus the spill at both ends. */
    public BigDecimal loadLengthCm() {
        return loaded(lengthCm, lengthSpillCm);
    }

    /** The width the counts load on: the nominal width plus the spill at both sides. */
    public BigDecimal loadWidthCm() {
        return loaded(widthCm, widthSpillCm);
    }

    /**
     * The spill this spec counts with, in cm per edge; 0 without one. The width carries it on every
     * carrier (a Deense kar spills across its width only), so it is the width's.
     */
    public BigDecimal spillCm() {
        return spills(widthSpillCm) ? widthSpillCm : BigDecimal.ZERO;
    }

    /**
     * The same spec with this spill per edge: on the width always, on the length only when
     * {@code alongLength}. Null, zero or less gives a spec without spill.
     */
    public PalletSpec withSpill(BigDecimal spillCm, boolean alongLength) {
        BigDecimal spill = spills(spillCm) ? spillCm : null;
        return new PalletSpec(name, lengthCm, widthCm, baseHeightCm, maxHeightCm, maxWeightKg,
                alongLength ? spill : null, spill);
    }

    private static boolean spills(BigDecimal spillCm) {
        return spillCm != null && spillCm.signum() > 0;
    }

    /* Without a spill the nominal size itself, not nominal plus zero: nothing about a count without spill changes. */
    private static BigDecimal loaded(BigDecimal nominalCm, BigDecimal spillCm) {
        return spills(spillCm) ? nominalCm.add(spillCm).add(spillCm) : nominalCm;
    }
}
