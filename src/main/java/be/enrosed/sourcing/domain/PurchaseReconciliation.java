package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only reconciliation against the agreed, ordered-quantity container budget.
 * Paid amounts use the euro value persisted with each payment, never a new FX rate.
 * An unpaid balance stays in the forecast until its stream is explicitly settled;
 * only an explicitly settled shortfall is a saving. Overpayments remain visible
 * and provisional until explicitly settled (a refund or correction may follow).
 * OTHER fees have no agreed payable to settle: known payments are additional
 * actual costs, excluded from the overpayment amount, and need no final marker.
 * A concept order or an order without product lines is never a final settlement.
 * Pricing totals include the internal Enrosed markup separately from external cost.
 * The analytical unit prices include every external fee, including fees configured
 * outside the saved product price, and never modify product or inventory valuation.
 */
public record PurchaseReconciliation(
        List<Stream> streams,
        Totals totals,
        List<Line> lines,
        List<String> notes
) {
    public enum Status { PLANNED, UNPAID, PARTIAL, PAID, OVERPAID, SETTLED_LOWER, NOT_APPLICABLE, ADDITIONAL }

    public enum UnitCostBasis { ORDERED, USABLE_RECEIVED }

    public record Stream(
            PurchasePayment.Payee payee,
            String label,
            Status status,
            BigDecimal plannedEur,
            BigDecimal paidEur,
            BigDecimal remainingEur,
            BigDecimal forecastEur,
            BigDecimal varianceEur,
            BigDecimal overpaidEur,
            BigDecimal settledSavingEur,
            boolean explicitlySettled,
            boolean finalized,
            int paymentCount
    ) {}

    public record Totals(
            BigDecimal plannedExternalEur,
            BigDecimal paidEur,
            BigDecimal remainingEur,
            BigDecimal forecastExternalEur,
            BigDecimal varianceEur,
            BigDecimal internalMarkupEur,
            BigDecimal plannedPricingEur,
            BigDecimal forecastPricingEur,
            boolean finalized,
            int orderedQuantity,
            int receivedQuantity,
            int damagedQuantity,
            int usableQuantity,
            int unitCostQuantity,
            UnitCostBasis unitCostBasis,
            /** Null if there are no pieces over which to allocate the cost. */
            BigDecimal forecastExternalUnitEur,
            /** Null if there are no pieces over which to allocate the cost. */
            BigDecimal forecastPricingUnitEur,
            boolean receiptRecorded,
            /** Historical receipt header; informational only, never added to payments. */
            BigDecimal legacyPaidTotalEur
    ) {}

    public record Line(
            Long productId,
            String productName,
            int orderedQuantity,
            int receivedQuantity,
            int damagedQuantity,
            int usableQuantity,
            int unitCostQuantity,
            UnitCostBasis unitCostBasis,
            BigDecimal plannedExternalEur,
            BigDecimal paidEur,
            BigDecimal remainingEur,
            BigDecimal forecastExternalEur,
            BigDecimal varianceEur,
            BigDecimal internalMarkupEur,
            BigDecimal forecastPricingEur,
            BigDecimal forecastExternalUnitEur,
            BigDecimal forecastPricingUnitEur,
            String allocationBasis
    ) {}
}
