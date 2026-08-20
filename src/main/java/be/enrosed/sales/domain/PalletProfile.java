package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** The pallet footprint used by the stacking calculation. */
public enum PalletProfile {
    EURO_120X80("Europallet 120x80", 120, 80),
    BLOCK_120X100("Blokpallet 120x100", 120, 100),
    HALF_80X60("Halve pallet 80x60", 80, 60);

    private final String label;
    private final BigDecimal lengthCm;
    private final BigDecimal widthCm;

    PalletProfile(String label, int lengthCm, int widthCm) {
        this.label = label;
        this.lengthCm = BigDecimal.valueOf(lengthCm);
        this.widthCm = BigDecimal.valueOf(widthCm);
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
}
