package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * What a document's omdozen go on, the footprint the stacking calculation uses: a pallet of one of
 * three types, or the Deense kar (the CC Container, since 2026-11-25: Emre, "Daarna moet ik ook hier
 * deense kar kunnen selecteren"). The kar takes every figure from {@link PalletSpec#deenseKar()}:
 * its floor here, and its base, standard height and weight in SalesSettings.pallet.
 */
public enum PalletProfile {
    EURO_120X80("Europallet B × D: 120 × 80 cm", 120, 80),
    BLOCK_120X100("Blokpallet B × D: 120 × 100 cm", 120, 100),
    HALF_80X60("Halve pallet B × D: 80 × 60 cm", 80, 60),
    /**
     * The Deense kar, 127 × 54,5 cm. Its freight is filled in by hand (Vast bedrag): the country
     * tariff and the carrier staffels price per pallet and know Euro, Blok and Half only.
     */
    DEENSE_KAR("Deense kar", PalletSpec.deenseKar());

    private final String label;
    private final BigDecimal lengthCm;
    private final BigDecimal widthCm;

    PalletProfile(String label, int lengthCm, int widthCm) {
        this.label = label;
        this.lengthCm = BigDecimal.valueOf(lengthCm);
        this.widthCm = BigDecimal.valueOf(widthCm);
    }

    /** A profile whose floor is its spec's, which need not be whole centimetres (54,5 cm). */
    PalletProfile(String name, PalletSpec spec) {
        this.label = name + " B × D: " + centimetres(spec.lengthCm()) + " × " + centimetres(spec.widthCm()) + " cm";
        this.lengthCm = spec.lengthCm();
        this.widthCm = spec.widthCm();
    }

    private static String centimetres(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    public String label() {
        return label;
    }

    public BigDecimal lengthCm() {
        return lengthCm;
    }

    public BigDecimal widthCm() {
        return widthCm;
    }

    /** The Deense kar rather than a pallet: other figures, other words, freight by hand. */
    public boolean isKar() {
        return this == DEENSE_KAR;
    }

    /**
     * Whether a spill also counts along the length. On a pallet an omdoos may stick out past every
     * edge; on the Deense kar only across the width, past the open long sides, never along the length:
     * the posts stand at the short ends (Emre, 2026-11-26: "alleen lange zijden").
     */
    public boolean spillsAlongLength() {
        return !isKar();
    }
}
