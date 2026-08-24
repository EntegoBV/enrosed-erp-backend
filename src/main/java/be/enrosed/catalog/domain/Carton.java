package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** De omdoos: afmeting, inhoud en gewicht. */
public record Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg,
                     /** Hand-counted pieces per 40' HC; null = derive from the carton size. */
                     Integer piecesPerHc) {

    /** Compatibility for callers written before the HC count existed. */
    public Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg) {
        this(dimensions, piecesPerCarton, weightKg, null);
    }

    public static Carton empty() {
        return new Carton(Dimensions.empty(), 1, BigDecimal.ZERO);
    }

    /** A 40' High Cube holds about this much load floor-to-ceiling. */
    private static final BigDecimal HC_CBM = new BigDecimal("68");

    /**
     * Pieces that fit a 40' HC: the hand-counted figure when given,
     * otherwise full cartons by volume times the carton's content.
     */
    public Integer hcCapacity() {
        if (piecesPerHc != null && piecesPerHc > 0) return piecesPerHc;
        BigDecimal volume = cbm();
        if (volume == null || volume.signum() <= 0) return null;
        int cartons = HC_CBM.divide(volume, 0, RoundingMode.DOWN).intValue();
        if (cartons <= 0) return 0;
        return cartons * Math.max(1, piecesPerCarton);
    }

    public BigDecimal cbm() {
        return dimensions.cbm();
    }

    /** Volume of one piece: the carton divided by its content. */
    public BigDecimal pieceCbm() {
        int pieces = Math.max(1, piecesPerCarton);
        return cbm().divide(BigDecimal.valueOf(pieces), 8, RoundingMode.HALF_UP);
    }

    /** Cartons for a piece count - shipping happens in full cartons. */
    public int cartonsFor(int quantity) {
        if (quantity <= 0) return 0;
        int pieces = Math.max(1, piecesPerCarton);
        return (quantity + pieces - 1) / pieces;
    }
}
