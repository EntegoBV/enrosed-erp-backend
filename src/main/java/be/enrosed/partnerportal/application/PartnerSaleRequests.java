package be.enrosed.partnerportal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The body of a new melding (POST /api/partner-portal/reports, and POST /api/partner-sales/reports
 * where staff add customerId). Quantities arrive as numbers and must be whole: they are read as
 * BigDecimal so 2.5 is refused instead of silently becoming 2.
 *
 * <p>Two shapes are accepted side by side (an older frontend keeps working during a deploy):
 * <ul>
 * <li>round 1: a part is {@code quantity} in sales units plus exactly one of {@code unitPriceEur}
 *     (per sales unit) and {@code totalEur}; the melding has one optional {@code extraCost};</li>
 * <li>round 2: a part is {@code enteredUnit} (STUK | VERPAKKING | OMDOOS), {@code enteredCount} and
 *     {@code piecePriceEur} (per piece); the server converts to sales units and refuses a count that
 *     is not whole in them. Mixing the two in one part is refused. The melding has
 *     {@code extraCosts} (0..5 lines; {@code extraCost} must then be null), {@code buyerCustomerId}
 *     (direct sales only), {@code apiVersion} 2, and on an edit {@code expectedToken} (the
 *     editToken the client loaded).</li>
 * </ul>
 */
public final class PartnerSaleRequests {

    private PartnerSaleRequests() {}

    public record Report(Long customerId, String channel, LocalDate soldOn, String clientRef, String note,
                         ExtraCost extraCost, List<Entry> entries, List<CostLine> extraCosts, Long buyerCustomerId,
                         Integer apiVersion, String expectedToken) {

        /** A request of a round-2 client: apiVersion 2 or more. */
        public boolean roundTwo() {
            return apiVersion != null && apiVersion >= 2;
        }
    }

    /** Round 1: mode AMOUNT (euros for the whole melding) or PCT (percent of each sale entry's gross). */
    public record ExtraCost(String mode, BigDecimal value) {}

    /** Round 2: one extra cost line; description optional (at most 80 characters). */
    public record CostLine(String description, String mode, BigDecimal value) {}

    public record Entry(Long productId, Part sale, Part buyback) {}

    /**
     * Round 1: quantity (sales units) with exactly one of unitPriceEur (per sales unit) and totalEur.
     * Round 2: enteredUnit, enteredCount and piecePriceEur (per piece), none of the round-1 fields.
     */
    public record Part(BigDecimal quantity, BigDecimal unitPriceEur, BigDecimal totalEur, String enteredUnit,
                       BigDecimal enteredCount, BigDecimal piecePriceEur) {

        boolean roundTwo() {
            return enteredUnit != null || enteredCount != null || piecePriceEur != null;
        }

        boolean roundOne() {
            return quantity != null || unitPriceEur != null || totalEur != null;
        }
    }
}
