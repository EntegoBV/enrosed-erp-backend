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
    THIRDS, THIRD_TWO_THIRDS_SHIPPED, THIRD_TWO_THIRDS_ARRIVED, HALF_HALF, HALF_HALF_ARRIVED,
    DEPOSIT_30_70, DEPOSIT_30_70_ARRIVED, DEPOSIT_30_40_30, FULL_UPFRONT, FULL_ON_ARRIVAL, CUSTOM;

    public record Instalment(String label, BigDecimal share, Moment due) {}

    public enum Moment { ORDERED, SHIPPED, ARRIVED }

    public List<Instalment> instalments() {
        return switch (this) {
            case THIRDS -> List.of(
                    new Instalment("1/3 bij bestelling", frac(1, 3), Moment.ORDERED),
                    new Instalment("1/3 bij vertrek", frac(1, 3), Moment.SHIPPED),
                    new Instalment("1/3 bij aankomst", frac(1, 3), Moment.ARRIVED));
            case THIRD_TWO_THIRDS_SHIPPED -> List.of(
                    new Instalment("1/3 bij bestelling", frac(1, 3), Moment.ORDERED),
                    new Instalment("2/3 bij vertrek", frac(2, 3), Moment.SHIPPED));
            case THIRD_TWO_THIRDS_ARRIVED -> List.of(
                    new Instalment("1/3 bij bestelling", frac(1, 3), Moment.ORDERED),
                    new Instalment("2/3 bij aankomst", frac(2, 3), Moment.ARRIVED));
            case HALF_HALF -> List.of(
                    new Instalment("50% bij bestelling", frac(1, 2), Moment.ORDERED),
                    new Instalment("50% bij vertrek", frac(1, 2), Moment.SHIPPED));
            case HALF_HALF_ARRIVED -> List.of(
                    new Instalment("50% bij bestelling", frac(1, 2), Moment.ORDERED),
                    new Instalment("50% bij aankomst", frac(1, 2), Moment.ARRIVED));
            case DEPOSIT_30_70 -> List.of(
                    new Instalment("30% bij bestelling", new BigDecimal("0.30"), Moment.ORDERED),
                    new Instalment("70% bij vertrek", new BigDecimal("0.70"), Moment.SHIPPED));
            case DEPOSIT_30_70_ARRIVED -> List.of(
                    new Instalment("30% bij bestelling", new BigDecimal("0.30"), Moment.ORDERED),
                    new Instalment("70% bij aankomst", new BigDecimal("0.70"), Moment.ARRIVED));
            case DEPOSIT_30_40_30 -> List.of(
                    new Instalment("30% bij bestelling", new BigDecimal("0.30"), Moment.ORDERED),
                    new Instalment("40% bij vertrek", new BigDecimal("0.40"), Moment.SHIPPED),
                    new Instalment("30% bij aankomst", new BigDecimal("0.30"), Moment.ARRIVED));
            case FULL_UPFRONT -> List.of(new Instalment("100% bij bestelling", BigDecimal.ONE, Moment.ORDERED));
            case FULL_ON_ARRIVAL -> List.of(new Instalment("100% bij aankomst", BigDecimal.ONE, Moment.ARRIVED));
            case CUSTOM -> List.of();
        };
    }

    public String dutchLabel() {
        return switch (this) {
            case THIRDS -> "1/3 · 1/3 · 1/3 (bestelling, vertrek, aankomst)";
            case THIRD_TWO_THIRDS_SHIPPED -> "1/3 bij bestelling, 2/3 bij vertrek";
            case THIRD_TWO_THIRDS_ARRIVED -> "1/3 bij bestelling, 2/3 bij aankomst";
            case HALF_HALF -> "50% bij bestelling, 50% bij vertrek";
            case HALF_HALF_ARRIVED -> "50% bij bestelling, 50% bij aankomst";
            case DEPOSIT_30_70 -> "30% bij bestelling, 70% bij vertrek";
            case DEPOSIT_30_70_ARRIVED -> "30% bij bestelling, 70% bij aankomst";
            case DEPOSIT_30_40_30 -> "30% · 40% · 30% (bestelling, vertrek, aankomst)";
            case FULL_UPFRONT -> "100% bij bestelling";
            case FULL_ON_ARRIVAL -> "100% bij aankomst";
            case CUSTOM -> "Anders (vrij)";
        };
    }

    /**
     * A plan written as three percentages, at ordering, at departure and at
     * arrival; the moments with nothing to pay simply do not appear.
     */
    public static List<Instalment> split(BigDecimal ordered, BigDecimal shipped, BigDecimal arrived) {
        List<Instalment> result = new java.util.ArrayList<>();
        addShare(result, ordered, "bij bestelling", Moment.ORDERED);
        addShare(result, shipped, "bij vertrek", Moment.SHIPPED);
        addShare(result, arrived, "bij aankomst", Moment.ARRIVED);
        return List.copyOf(result);
    }

    private static void addShare(List<Instalment> into, BigDecimal pct, String when, Moment moment) {
        if (pct == null || pct.signum() <= 0) return;
        into.add(new Instalment(pct.stripTrailingZeros().toPlainString() + "% " + when,
                pct.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP), moment));
    }

    /** "30% · 50% · 20% (bestelling, vertrek, aankomst)" for a split of one's own. */
    public static String splitLabel(BigDecimal ordered, BigDecimal shipped, BigDecimal arrived) {
        return split(ordered, shipped, arrived).stream().map(Instalment::label)
                .reduce((a, b) -> a + ", " + b).orElse("Anders (vrij)");
    }

    private static BigDecimal frac(int a, int b) {
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 6, java.math.RoundingMode.HALF_UP);
    }
}
