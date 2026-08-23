package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the supplier is paid over the life of a container.
 *
 * Each plan is a list of instalments: a share of the goods value and the
 * moment it falls due. The default is the one most factories ask for -
 * a third when ordering, a third when the container sails, a third when
 * it lands.
 */
public enum PaymentTerms {
    THIRDS, HALF_HALF, DEPOSIT_30_70, FULL_UPFRONT, FULL_ON_ARRIVAL, CUSTOM;

    public record Instalment(String label, BigDecimal share, Moment due) {}

    public enum Moment { ORDERED, SHIPPED, ARRIVED }

    public List<Instalment> instalments() {
        return switch (this) {
            case THIRDS -> List.of(
                    new Instalment("1/3 bij bestelling", frac(1, 3), Moment.ORDERED),
                    new Instalment("1/3 bij vertrek", frac(1, 3), Moment.SHIPPED),
                    new Instalment("1/3 bij aankomst", frac(1, 3), Moment.ARRIVED));
            case HALF_HALF -> List.of(
                    new Instalment("50% bij bestelling", frac(1, 2), Moment.ORDERED),
                    new Instalment("50% bij vertrek", frac(1, 2), Moment.SHIPPED));
            case DEPOSIT_30_70 -> List.of(
                    new Instalment("30% bij bestelling", new BigDecimal("0.30"), Moment.ORDERED),
                    new Instalment("70% bij vertrek", new BigDecimal("0.70"), Moment.SHIPPED));
            case FULL_UPFRONT -> List.of(new Instalment("100% bij bestelling", BigDecimal.ONE, Moment.ORDERED));
            case FULL_ON_ARRIVAL -> List.of(new Instalment("100% bij aankomst", BigDecimal.ONE, Moment.ARRIVED));
            case CUSTOM -> List.of();
        };
    }

    public String dutchLabel() {
        return switch (this) {
            case THIRDS -> "1/3 · 1/3 · 1/3 (bestelling, vertrek, aankomst)";
            case HALF_HALF -> "50% bij bestelling, 50% bij vertrek";
            case DEPOSIT_30_70 -> "30% bij bestelling, 70% bij vertrek";
            case FULL_UPFRONT -> "100% bij bestelling";
            case FULL_ON_ARRIVAL -> "100% bij aankomst";
            case CUSTOM -> "Anders (vrij)";
        };
    }

    private static BigDecimal frac(int a, int b) {
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 6, java.math.RoundingMode.HALF_UP);
    }
}
