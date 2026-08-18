package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** De omdoos: afmeting, inhoud en gewicht. */
public record Carton(Dimensions dimensions, int piecesPerCarton, BigDecimal weightKg) {

    public static Carton empty() {
        return new Carton(Dimensions.empty(), 1, BigDecimal.ZERO);
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
