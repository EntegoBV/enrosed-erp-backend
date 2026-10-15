package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only reconciliation against the agreed, ordered-quantity container budget.
 * Paid amounts use the euro value persisted with each payment, never a new FX rate.
 * An unpaid balance stays in the forecast until its group or milestone is explicitly settled;
 * only an explicitly settled shortfall is a saving. Supplier milestone savings
 * may be confirmed while the rest of the supplier group is still open. Overpayments remain visible
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
        List<String> notes,
        List<SupplierInstalment> supplierInstalments
) {
    public PurchaseReconciliation(List<Stream> streams, Totals totals, List<Line> lines, List<String> notes) {
        this(streams, totals, lines, notes, List.of());
    }

    /**
     * One term of the payee's plan. paidEur is what was booked on it, gross;
     * remainingEur and overpaidEur are after the payee's terms were netted:
     * a surplus on one term covers a shortfall on another.
     *
     * @param offsetEur what that netting moved: above zero another term's
     *                  surplus covered this shortfall, below zero this
     *                  surplus covered another term. Zero for a term settled
     *                  on its own, which stays out of the netting.
     */
    public record SupplierInstalment(
            PaymentTerms.Moment due, String label, BigDecimal plannedEur, BigDecimal paidEur,
            BigDecimal remainingEur, BigDecimal settledSavingEur, BigDecimal overpaidEur,
            boolean explicitlySettled, boolean finalized, BigDecimal offsetEur
    ) {
        /** Compatibility for callers written before the terms were netted: nothing moved. */
        public SupplierInstalment(PaymentTerms.Moment due, String label, BigDecimal plannedEur, BigDecimal paidEur,
                                  BigDecimal remainingEur, BigDecimal settledSavingEur, BigDecimal overpaidEur,
                                  boolean explicitlySettled, boolean finalized) {
            this(due, label, plannedEur, paidEur, remainingEur, settledSavingEur, overpaidEur,
                    explicitlySettled, finalized, BigDecimal.ZERO.setScale(2));
        }

        /**
         * The term's own difference as shown: paid minus Afspraak, with what
         * is still open left out (open money is not a difference). An unpaid
         * future term reads zero; the payee's terms add up to overpaid
         * minus saving.
         */
        public BigDecimal differenceEur() {
            return paidEur.subtract(plannedEur).add(remainingEur);
        }
    }

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
            int paymentCount,
            /**
             * Credits the supplier owes on this order, whatever their status;
             * zero for every other payee. forecastEur = paid + open - credit.
             */
            BigDecimal creditEur
    ) {
        /** Compatibility for callers written before supplier credits existed. */
        public Stream(PurchasePayment.Payee payee, String label, Status status, BigDecimal plannedEur,
                      BigDecimal paidEur, BigDecimal remainingEur, BigDecimal forecastEur, BigDecimal varianceEur,
                      BigDecimal overpaidEur, BigDecimal settledSavingEur, boolean explicitlySettled,
                      boolean finalized, int paymentCount) {
            this(payee, label, status, plannedEur, paidEur, remainingEur, forecastEur, varianceEur, overpaidEur,
                    settledSavingEur, explicitlySettled, finalized, paymentCount, BigDecimal.ZERO.setScale(2));
        }
    }

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
            BigDecimal legacyPaidTotalEur,
            /** Every supplier credit on the order; it lowers forecastExternalEur. */
            BigDecimal supplierCreditEur,
            /** The part of those credits still to receive. */
            BigDecimal supplierCreditOpenEur,
            /**
             * What one sales unit of the container is: PIECE when no line is
             * a set, DISPLAY when every line is a set of the same size and
             * piece name, MIXED otherwise (sets of different sizes, or sets
             * next to pieces, have no common "per set").
             */
            String setBasis,
            /** The pieces in a set, shared by every set line; null when none or not shared. */
            Integer setPieces,
            /** The plural piece name shared by every set line ("rozen"); null when none or not shared. */
            String setUnitOther,
            /** The quantities counted in pieces: a set counts its pieces. */
            int orderedPieceQuantity,
            int usablePieceQuantity,
            int unitCostPieceQuantity,
            /** The container average per piece; null when there are no pieces. */
            BigDecimal forecastExternalPieceEur,
            BigDecimal forecastPricingPieceEur,
            /** The different products on the container (lines of one product count once). */
            int productCount,
            /**
             * Whether a container-wide figure per stuk or per set means
             * anything: only for a container of one product. With several
             * products (variants of one family too) the screens and the PDFs
             * show the cost per product, or the range of those figures, and
             * never an average over them.
             */
            boolean containerAverage
    ) {
        /** Compatibility for callers written before the container average was limited to one product. */
        public Totals(BigDecimal plannedExternalEur, BigDecimal paidEur, BigDecimal remainingEur,
                      BigDecimal forecastExternalEur, BigDecimal varianceEur, BigDecimal internalMarkupEur,
                      BigDecimal plannedPricingEur, BigDecimal forecastPricingEur, boolean finalized,
                      int orderedQuantity, int receivedQuantity, int damagedQuantity, int usableQuantity,
                      int unitCostQuantity, UnitCostBasis unitCostBasis, BigDecimal forecastExternalUnitEur,
                      BigDecimal forecastPricingUnitEur, boolean receiptRecorded, BigDecimal legacyPaidTotalEur,
                      BigDecimal supplierCreditEur, BigDecimal supplierCreditOpenEur, String setBasis,
                      Integer setPieces, String setUnitOther, int orderedPieceQuantity, int usablePieceQuantity,
                      int unitCostPieceQuantity, BigDecimal forecastExternalPieceEur,
                      BigDecimal forecastPricingPieceEur) {
            this(plannedExternalEur, paidEur, remainingEur, forecastExternalEur, varianceEur, internalMarkupEur,
                    plannedPricingEur, forecastPricingEur, finalized, orderedQuantity, receivedQuantity,
                    damagedQuantity, usableQuantity, unitCostQuantity, unitCostBasis, forecastExternalUnitEur,
                    forecastPricingUnitEur, receiptRecorded, legacyPaidTotalEur, supplierCreditEur, supplierCreditOpenEur,
                    setBasis, setPieces, setUnitOther, orderedPieceQuantity, usablePieceQuantity, unitCostPieceQuantity,
                    forecastExternalPieceEur, forecastPricingPieceEur, 0, true);
        }

        /**
         * Compatibility for callers written before sets were counted in
         * pieces: every sales unit is one piece.
         */
        public Totals(BigDecimal plannedExternalEur, BigDecimal paidEur, BigDecimal remainingEur,
                      BigDecimal forecastExternalEur, BigDecimal varianceEur, BigDecimal internalMarkupEur,
                      BigDecimal plannedPricingEur, BigDecimal forecastPricingEur, boolean finalized,
                      int orderedQuantity, int receivedQuantity, int damagedQuantity, int usableQuantity,
                      int unitCostQuantity, UnitCostBasis unitCostBasis, BigDecimal forecastExternalUnitEur,
                      BigDecimal forecastPricingUnitEur, boolean receiptRecorded, BigDecimal legacyPaidTotalEur,
                      BigDecimal supplierCreditEur, BigDecimal supplierCreditOpenEur) {
            this(plannedExternalEur, paidEur, remainingEur, forecastExternalEur, varianceEur, internalMarkupEur,
                    plannedPricingEur, forecastPricingEur, finalized, orderedQuantity, receivedQuantity,
                    damagedQuantity, usableQuantity, unitCostQuantity, unitCostBasis, forecastExternalUnitEur,
                    forecastPricingUnitEur, receiptRecorded, legacyPaidTotalEur, supplierCreditEur, supplierCreditOpenEur,
                    "PIECE", null, null, orderedQuantity, usableQuantity, unitCostQuantity,
                    forecastExternalUnitEur, forecastPricingUnitEur);
        }

        /** Compatibility for callers written before supplier credits existed. */
        public Totals(BigDecimal plannedExternalEur, BigDecimal paidEur, BigDecimal remainingEur,
                      BigDecimal forecastExternalEur, BigDecimal varianceEur, BigDecimal internalMarkupEur,
                      BigDecimal plannedPricingEur, BigDecimal forecastPricingEur, boolean finalized,
                      int orderedQuantity, int receivedQuantity, int damagedQuantity, int usableQuantity,
                      int unitCostQuantity, UnitCostBasis unitCostBasis, BigDecimal forecastExternalUnitEur,
                      BigDecimal forecastPricingUnitEur, boolean receiptRecorded, BigDecimal legacyPaidTotalEur) {
            this(plannedExternalEur, paidEur, remainingEur, forecastExternalEur, varianceEur, internalMarkupEur,
                    plannedPricingEur, forecastPricingEur, finalized, orderedQuantity, receivedQuantity,
                    damagedQuantity, usableQuantity, unitCostQuantity, unitCostBasis, forecastExternalUnitEur,
                    forecastPricingUnitEur, receiptRecorded, legacyPaidTotalEur,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }

        /** The same totals with their piece view. */
        public Totals withPieces(String basis, Integer pieces, String unitOther, int orderedPieces, int usablePieces,
                                 int unitCostPieces, BigDecimal externalPieceEur, BigDecimal pricingPieceEur) {
            return new Totals(plannedExternalEur, paidEur, remainingEur, forecastExternalEur, varianceEur,
                    internalMarkupEur, plannedPricingEur, forecastPricingEur, finalized, orderedQuantity,
                    receivedQuantity, damagedQuantity, usableQuantity, unitCostQuantity, unitCostBasis,
                    forecastExternalUnitEur, forecastPricingUnitEur, receiptRecorded, legacyPaidTotalEur,
                    supplierCreditEur, supplierCreditOpenEur, basis, pieces, unitOther, orderedPieces, usablePieces,
                    unitCostPieces, externalPieceEur, pricingPieceEur, productCount, containerAverage);
        }

        /** The same totals saying how many products they hold and whether an average over them means anything. */
        public Totals withAverage(int products, boolean average) {
            return new Totals(plannedExternalEur, paidEur, remainingEur, forecastExternalEur, varianceEur,
                    internalMarkupEur, plannedPricingEur, forecastPricingEur, finalized, orderedQuantity,
                    receivedQuantity, damagedQuantity, usableQuantity, unitCostQuantity, unitCostBasis,
                    forecastExternalUnitEur, forecastPricingUnitEur, receiptRecorded, legacyPaidTotalEur,
                    supplierCreditEur, supplierCreditOpenEur, setBasis, setPieces, setUnitOther, orderedPieceQuantity,
                    usablePieceQuantity, unitCostPieceQuantity, forecastExternalPieceEur, forecastPricingPieceEur,
                    products, average);
        }
    }

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
            String allocationBasis,
            /** This product's share of the supplier credits; forecastExternalEur = paid + open - credit. */
            BigDecimal creditEur,
            /**
             * PIECE or DISPLAY: what one unit of this line is. The quantities
             * and unit figures above stay in sales units (per set for a set),
             * because sales and partner settlements multiply them with sales
             * quantities; the piece figures below sit next to them.
             */
            String salesUnit,
            /** The pieces in one sales unit: the set size for a set, else 1. */
            int piecesPerUnit,
            /** The piece's unit key and its Dutch names ("roos", "rozen"). */
            String unitKey,
            String unitOne,
            String unitOther,
            /** unitCostQuantity counted in pieces. */
            int pieceQuantity,
            /** The line's cost per piece; null when there are no pieces. */
            BigDecimal forecastExternalPieceEur,
            BigDecimal forecastPricingPieceEur
    ) {
        /** Compatibility for callers written before sets were counted in pieces: every unit is one piece. */
        public Line(Long productId, String productName, int orderedQuantity, int receivedQuantity,
                    int damagedQuantity, int usableQuantity, int unitCostQuantity, UnitCostBasis unitCostBasis,
                    BigDecimal plannedExternalEur, BigDecimal paidEur, BigDecimal remainingEur,
                    BigDecimal forecastExternalEur, BigDecimal varianceEur, BigDecimal internalMarkupEur,
                    BigDecimal forecastPricingEur, BigDecimal forecastExternalUnitEur,
                    BigDecimal forecastPricingUnitEur, String allocationBasis, BigDecimal creditEur) {
            this(productId, productName, orderedQuantity, receivedQuantity, damagedQuantity, usableQuantity,
                    unitCostQuantity, unitCostBasis, plannedExternalEur, paidEur, remainingEur, forecastExternalEur,
                    varianceEur, internalMarkupEur, forecastPricingEur, forecastExternalUnitEur,
                    forecastPricingUnitEur, allocationBasis, creditEur, "PIECE", 1, "stuk", "stuk", "stuks",
                    unitCostQuantity, forecastExternalUnitEur, forecastPricingUnitEur);
        }

        /** The same line with its piece view. */
        public Line withPieces(String unit, int pieces, String key, String one, String other, int quantityInPieces,
                               BigDecimal externalPieceEur, BigDecimal pricingPieceEur) {
            return new Line(productId, productName, orderedQuantity, receivedQuantity, damagedQuantity, usableQuantity,
                    unitCostQuantity, unitCostBasis, plannedExternalEur, paidEur, remainingEur, forecastExternalEur,
                    varianceEur, internalMarkupEur, forecastPricingEur, forecastExternalUnitEur,
                    forecastPricingUnitEur, allocationBasis, creditEur, unit, pieces, key, one, other,
                    quantityInPieces, externalPieceEur, pricingPieceEur);
        }

        /** Compatibility for callers written before supplier credits existed. */
        public Line(Long productId, String productName, int orderedQuantity, int receivedQuantity,
                    int damagedQuantity, int usableQuantity, int unitCostQuantity, UnitCostBasis unitCostBasis,
                    BigDecimal plannedExternalEur, BigDecimal paidEur, BigDecimal remainingEur,
                    BigDecimal forecastExternalEur, BigDecimal varianceEur, BigDecimal internalMarkupEur,
                    BigDecimal forecastPricingEur, BigDecimal forecastExternalUnitEur,
                    BigDecimal forecastPricingUnitEur, String allocationBasis) {
            this(productId, productName, orderedQuantity, receivedQuantity, damagedQuantity, usableQuantity,
                    unitCostQuantity, unitCostBasis, plannedExternalEur, paidEur, remainingEur, forecastExternalEur,
                    varianceEur, internalMarkupEur, forecastPricingEur, forecastExternalUnitEur,
                    forecastPricingUnitEur, allocationBasis, BigDecimal.ZERO.setScale(2));
        }
    }
}
