package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;

import java.math.BigDecimal;

/**
 * Line on a purchase order. Price fields may be empty; the product's own
 * price then applies.
 */
public record PurchaseOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,
        /**
         * The quantity at the moment the order was placed with the supplier.
         *
         * Snapshotted when the status leaves concept. Containers regularly
         * arrive with less than was ordered; from then on the line can say
         * "ordered 96, received 90" instead of silently forgetting what was
         * agreed. Null for lines added after ordering.
         */
        Integer orderedQuantity,
        /** What the agreed price covers; null means EXW, as every line was before. */
        PriceBasis priceBasis,
        /** Pieces that arrived broken; they are in {@code quantity} but never in stock. */
        Integer damagedQuantity,
        /**
         * Purchase value of one piece in euro, snapshotted at receipt time.
         *
         * <p>Unlike the live landed-cost calculation this value must not move
         * when a price or exchange rate is corrected later: historical
         * shortage and damage metrics need the value that was attached to the
         * receipt. Null means that no reliable value is known yet.</p>
         */
        BigDecimal receiptUnitValueEur,
        /** What was wrong on arrival, in our own words: "glass domes cracked, inner box too thin". */
        String issueNote
) {
    /** Compatibility for callers written before the arrival note existed. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity,
                             PriceBasis priceBasis, Integer damagedQuantity, BigDecimal receiptUnitValueEur) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost, orderedQuantity, priceBasis,
                damagedQuantity, receiptUnitValueEur, null);
    }

    /** Compatibility for callers written before DDP prices existed. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost,
                orderedQuantity, null, null, null);
    }

    /** Compatibility for callers written before damage was counted. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity,
                             PriceBasis priceBasis) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost,
                orderedQuantity, priceBasis, null, null);
    }

    /** Compatibility for callers written before receipt values were snapshotted. */
    public PurchaseOrderLine(Long id, Long productId, int quantity, BigDecimal exwPrice,
                             Currency exwCurrency, BigDecimal extraUnitCost, Integer orderedQuantity,
                             PriceBasis priceBasis, Integer damagedQuantity) {
        this(id, productId, quantity, exwPrice, exwCurrency, extraUnitCost,
                orderedQuantity, priceBasis, damagedQuantity, null);
    }

    public int ordered() {
        return Math.max(0, orderedQuantity == null ? quantity : orderedQuantity);
    }

    public int received() {
        return Math.max(0, quantity);
    }

    public int missing() {
        return Math.max(0, ordered() - received());
    }

    public int overReceived() {
        return Math.max(0, received() - ordered());
    }

    public int damaged() {
        return damagedQuantity == null || damagedQuantity < 0 ? 0 : damagedQuantity;
    }

    /** What goes on the shelf: arrived minus broken. */
    public int usable() {
        return Math.max(0, received() - damaged());
    }

    public int lost() {
        return missing() + damaged();
    }

    public BigDecimal missingValueEur() {
        return valueFor(missing());
    }

    public BigDecimal damagedValueEur() {
        return valueFor(damaged());
    }

    public BigDecimal totalLossValueEur() {
        BigDecimal missingValue = missingValueEur();
        BigDecimal damagedValue = damagedValueEur();
        return missingValue == null || damagedValue == null
                ? null : Money.money(missingValue.add(damagedValue));
    }

    public boolean valuationComplete() {
        return lost() == 0 || receiptUnitValueEur != null;
    }

    private BigDecimal valueFor(int pieces) {
        if (pieces == 0) return Money.money(BigDecimal.ZERO);
        return receiptUnitValueEur == null
                ? null
                : Money.money(receiptUnitValueEur.multiply(BigDecimal.valueOf(pieces)));
    }

    public PriceBasis priceBasis() {
        return priceBasis == null ? PriceBasis.EXW : priceBasis;
    }

    public boolean deliveredDutyPaid() {
        return priceBasis() == PriceBasis.DDP;
    }
}
