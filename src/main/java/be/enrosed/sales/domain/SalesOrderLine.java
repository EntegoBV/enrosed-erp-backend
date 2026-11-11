package be.enrosed.sales.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Quote line.
 *
 * {@code unitPriceEur} and {@code manualDiscountPct} are manual overrides;
 * left empty, the pricing engine computes them itself.
 */
public record SalesOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal unitPriceEur,
        BigDecimal manualDiscountPct,

        /**
         * Hand-picked delivery week, e.g. "2026-W34". Optional: when empty,
         * the estimate from stock and transit time is used.
         */
        String deliveryWeek,

        /**
         * What one piece cost us when this line was written: the container's
         * landed cost for a quote made from it, otherwise the product's cost
         * of that day. Fixed, so an old quote's margin never drifts when a
         * later container lands cheaper or dearer.
         */
        BigDecimal unitCostEur,
        Boolean unavailable,
        Integer requestedQuantity,

        /**
         * The order step (sales units) this line was last rounded to, written by the server
         * when the document is saved; never taken from a client. A later change of the
         * product's step or carton content therefore never re-prices a sent or paid document.
         * Null on lines from before the step was remembered: they follow the product's step.
         * 1 on a line that counts per piece ({@code perPiece}).
         */
        Integer unitsPerOrderStep,

        /**
         * What the pieces cost that left stock for this line at shipment (FIFO over the batches); written by the
         * server only, null before shipment or before batches started.
         */
        BigDecimal stockCostEur,

        /**
         * Where {@code unitPriceEur} comes from: {@link #PRICE_CUSTOMER} (the customer's agreed price, pinned
         * when the line was added), {@link #PRICE_STANDARD} (staff chose the catalogue price although the
         * customer has one; no unit price, so it follows the catalogue) or {@link #PRICE_MANUAL} (typed).
         * Null on every older line and on a line that follows the catalogue by default. Left out of the
         * JSON while null, so an older document reads exactly as before.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String priceSource,

        /**
         * Staff chose that this line counts per piece: its quantity is never rounded up to the
         * product's order step, whoever writes the document, and the step it remembers is 1. A part
         * carton still ships and counts as one full carton. TRUE or null, never false once settled:
         * null is every line that rounds as its product says, so every line from before the choice.
         * Left out of the JSON while null. In a request an absent or null value keeps what is stored
         * on the same line, true or false sets it (SalesOrderService.withPerPieceChoices).
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean perPiece
) {
    public static final String PRICE_CUSTOMER = "CUSTOMER";
    public static final String PRICE_STANDARD = "STANDARD";
    public static final String PRICE_MANUAL = "MANUAL";

    /*
     * No form with a price source and without the per-piece choice, on purpose: whoever rebuilds a
     * whole line says what it carries, and the compiler lists every place that does.
     */
    /** A line without a price source or a per-piece choice: every caller from before customer prices, and a new line of the catalogue. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur,
                          Boolean unavailable, Integer requestedQuantity, Integer unitsPerOrderStep,
                          BigDecimal stockCostEur) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur,
                unavailable, requestedQuantity, unitsPerOrderStep, stockCostEur, null, null);
    }
    /** A line without a stock cost (before shipment, or as a client sends it: the server never takes it from one). */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur,
                          Boolean unavailable, Integer requestedQuantity, Integer unitsPerOrderStep) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur,
                unavailable, requestedQuantity, unitsPerOrderStep, null);
    }
    /** A line written before its order step was remembered; it follows the product's step. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur,
                          Boolean unavailable, Integer requestedQuantity) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur,
                unavailable, requestedQuantity, null);
    }
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek, BigDecimal unitCostEur) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, null, null);
    }
    /** A line written before the cost was remembered on it. */
    public SalesOrderLine(Long id, Long productId, int quantity, BigDecimal unitPriceEur,
                          BigDecimal manualDiscountPct, String deliveryWeek) {
        this(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, null);
    }

    public boolean hasUnitCost() {
        return unitCostEur != null && unitCostEur.signum() > 0;
    }

    public SalesOrderLine withUnitCost(BigDecimal unitCost) {
        return new SalesOrderLine(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCost, unavailable, requestedQuantity, unitsPerOrderStep, stockCostEur, priceSource, perPiece);
    }
    public boolean isUnavailable() { return Boolean.TRUE.equals(unavailable); }
    public SalesOrderLine withAvailability(boolean value, int activeQuantity, Integer remembered) {
        return new SalesOrderLine(id, productId, activeQuantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, value, remembered, unitsPerOrderStep, stockCostEur, priceSource, perPiece);
    }
    /** The same line rounded to, and remembering, an order step; null forgets the step. */
    public SalesOrderLine withOrderStep(int roundedQuantity, Integer step) {
        return new SalesOrderLine(id, productId, roundedQuantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, unavailable, requestedQuantity, step, stockCostEur, priceSource, perPiece);
    }
    /** The same line at this unit price, from this source; a null price follows the catalogue. */
    public SalesOrderLine withPrice(BigDecimal unitPrice, String source) {
        return new SalesOrderLine(id, productId, quantity, unitPrice, manualDiscountPct, deliveryWeek, unitCostEur, unavailable, requestedQuantity, unitsPerOrderStep, stockCostEur, source, perPiece);
    }
    /** Whether staff chose per piece for this line. Not named is… or get… on purpose: it is no JSON property. */
    public boolean countsPerPiece() { return Boolean.TRUE.equals(perPiece); }
    /** The same line with the per-piece choice set or cleared; anything but true clears it. */
    public SalesOrderLine withPerPiece(Boolean value) {
        return new SalesOrderLine(id, productId, quantity, unitPriceEur, manualDiscountPct, deliveryWeek, unitCostEur, unavailable, requestedQuantity, unitsPerOrderStep, stockCostEur, priceSource,
                Boolean.TRUE.equals(value) ? Boolean.TRUE : null);
    }
    /** Whether the line carries a price of its own: a typed or pinned one above zero; without one it follows the catalogue. */
    public boolean hasUnitPrice() {
        return unitPriceEur != null && unitPriceEur.signum() > 0;
    }
}
