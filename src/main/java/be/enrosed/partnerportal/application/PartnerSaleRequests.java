package be.enrosed.partnerportal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The body of a new melding (POST /api/partner-portal/reports, and POST /api/partner-sales/reports
 * where staff add customerId). Quantities arrive as numbers and must be whole: they are read as
 * BigDecimal so 2.5 is refused instead of silently becoming 2.
 */
public final class PartnerSaleRequests {

    private PartnerSaleRequests() {}

    public record Report(Long customerId, String channel, LocalDate soldOn, String clientRef, String note,
                         ExtraCost extraCost, List<Entry> entries) {}

    /** mode AMOUNT (euros for the whole melding) or PCT (percent of each sale entry's gross). */
    public record ExtraCost(String mode, BigDecimal value) {}

    public record Entry(Long productId, Part sale, Part buyback) {}

    /** Exactly one of unitPriceEur (per sales unit) and totalEur. */
    public record Part(BigDecimal quantity, BigDecimal unitPriceEur, BigDecimal totalEur) {}
}
