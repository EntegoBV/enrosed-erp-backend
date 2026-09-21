package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** De omdoos: afmeting, inhoud en gewicht. */
public record Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg,
                     /** Hand-counted pieces per 40' HC; null = derive from the carton size. */
                     Integer piecesPerHc,
                     /** Manually confirmed product units per 20ft GP; null = calculate when possible. */
                     Integer piecesPer20Ft) {

    /** Compatibility for callers written before the independent 20ft count existed. */
    public Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg,
                  Integer piecesPerHc) {
        this(dimensions, piecesPerCarton, weightKg, piecesPerHc, null);
    }

    /** Compatibility for callers written before the HC count existed. */
    public Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg) {
        this(dimensions, piecesPerCarton, weightKg, null, null);
    }

    public static Carton empty() {
        return new Carton(Dimensions.empty(), 1, BigDecimal.ZERO);
    }

    // Practical ERP planning volumes, kept aligned with sourcing.domain.ContainerType.
    // These are not nominal internal volumes or a weight/physical loading guarantee.
    private static final BigDecimal HC_CBM = new BigDecimal("68");
    private static final BigDecimal GP_CBM = new BigDecimal("28");
    private static final GpCapacity UNKNOWN_GP = new GpCapacity(null, "UNKNOWN");

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

    /** Manual 20ft count, otherwise a planning estimate; never persisted as manual input. */
    public Integer gpCapacity() {
        return gpCapacityEstimate().value();
    }

    /** MANUAL, CARTON, HC_RATIO or UNKNOWN; CARTON and HC_RATIO identify estimates. */
    public String gpCapacitySource() {
        return gpCapacityEstimate().source();
    }

    private GpCapacity gpCapacityEstimate() {
        if (piecesPer20Ft != null && piecesPer20Ft > 0) {
            return new GpCapacity(piecesPer20Ft, "MANUAL");
        }
        BigDecimal volume = dimensions == null ? BigDecimal.ZERO : cbm();
        if (piecesPerCarton > 0 && volume.signum() > 0) {
            BigDecimal cartons = GP_CBM.divide(volume, 0, RoundingMode.DOWN);
            return gpResult(cartons.multiply(BigDecimal.valueOf(piecesPerCarton)), "CARTON");
        }
        // Only a confirmed HC count is a fallback; do not ratio an HC estimate
        // derived from the same missing or invalid carton dimensions.
        if (piecesPerHc != null && piecesPerHc > 0) {
            // With an unknown carton content, conservatively round to whole
            // product units rather than inventing a carton configuration.
            BigDecimal unit = BigDecimal.valueOf(piecesPerCarton > 0 ? piecesPerCarton : 1);
            BigDecimal cartons = BigDecimal.valueOf(piecesPerHc).multiply(GP_CBM)
                    .divide(HC_CBM.multiply(unit), 0, RoundingMode.DOWN);
            return gpResult(cartons.multiply(unit), "HC_RATIO");
        }
        return UNKNOWN_GP;
    }

    private static GpCapacity gpResult(BigDecimal pieces, String source) {
        try {
            // A genuine zero fit stays zero; an unrepresentable count is unknown,
            // never wrapped/truncated or silently replaced with a weaker estimate.
            return new GpCapacity(pieces.intValueExact(), source);
        } catch (ArithmeticException overflow) {
            return UNKNOWN_GP;
        }
    }

    private record GpCapacity(Integer value, String source) {}

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
