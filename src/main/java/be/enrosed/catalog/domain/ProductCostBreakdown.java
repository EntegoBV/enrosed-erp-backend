package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Stream;

/**
 * What one unit of a landed cost was made of, in euro, the way the
 * container calculation built it: goods (the extra unit cost included),
 * origin, sea freight, import duty, costs on this side, the Enrosed kost and
 * the inspection and other named costs when a key spread them into the
 * piece price.
 *
 * The parts are rounded to four decimals each, so together they can differ
 * from the stored cost by a fraction of a cent.
 */
public record ProductCostBreakdown(
        BigDecimal goodsEur,
        BigDecimal originEur,
        BigDecimal freightEur,
        BigDecimal dutyEur,
        BigDecimal destinationEur,
        BigDecimal enrosedEur,
        BigDecimal separateEur
) {
    private static final int SCALE = 4;

    /** A container line's parts divided by the quantity it carried; null when it carried nothing. */
    public static ProductCostBreakdown perUnit(int quantity, BigDecimal goodsEur, BigDecimal originEur,
                                               BigDecimal freightEur, BigDecimal dutyEur,
                                               BigDecimal destinationEur, BigDecimal enrosedEur,
                                               BigDecimal separateEur) {
        if (quantity <= 0) return null;
        BigDecimal pieces = BigDecimal.valueOf(quantity);
        return new ProductCostBreakdown(each(goodsEur, pieces), each(originEur, pieces), each(freightEur, pieces),
                each(dutyEur, pieces), each(destinationEur, pieces), each(enrosedEur, pieces),
                each(separateEur, pieces));
    }

    /** True when no part is known, as on every row written before the parts were kept. */
    public boolean isEmpty() {
        return Stream.of(goodsEur, originEur, freightEur, dutyEur, destinationEur, enrosedEur, separateEur)
                .allMatch(part -> part == null);
    }

    private static BigDecimal each(BigDecimal total, BigDecimal pieces) {
        return total == null ? null : total.divide(pieces, SCALE, RoundingMode.HALF_UP);
    }
}
