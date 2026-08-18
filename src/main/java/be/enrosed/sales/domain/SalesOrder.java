package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales order, doubling as the quote document that goes to the customer.
 *
 * {@code portalToken} is the key the customer opens the quote with, no
 * account needed. It is only created on sending and is long and random;
 * whoever holds it may view the quote, propose changes and sign.
 */
public record SalesOrder(
        Long id,
        String number,
        Long customerId,
        String countryCode,
        LocalDate orderDate,
        LocalDate validUntil,
        QuoteStatus status,
        String incoterm,
        /**
         * Payment terms for this specific order; empty means the customer's
         * default applies. A fair deal sometimes needs its own terms without
         * rewriting the customer record.
         */
        String paymentTerms,
        String notes,

        MarkupMode markupMode,
        BigDecimal orderMarkupPct,

        /**
         * Extra discount on top of the tiers, for instance a fair discount.
         * Optional: empty or zero means no extra discount.
         */
        BigDecimal extraDiscountPct,
        /** Why that discount exists; appears verbatim on the quote. */
        String extraDiscountLabel,

        String portalToken,
        Instant sentAt,
        Instant viewedAt,
        /** How many times the customer opened the quote. */
        int viewCount,
        Instant decidedAt,
        /* Name the customer types when accepting - the signature. */
        String signedByName,
        String customerMessage,
        /** Notes for ourselves; never appear on the customer document. */
        String internalNotes,

        /**
         * Whether a delivery term was still owed, and whether it has been
         * filled in. Drives what the customer reads in the portal and mail.
         */
        DeliveryTermsState deliveryTerms,

        /**
         * Whether the freight still has to be determined, and whether that
         * happened. Works just like {@link #deliveryTerms}.
         */
        FreightState freight,

        /**
         * Freight we fill in ourselves instead of using the country rate.
         *
         * Empty means: charge the destination country's rate. While the
         * freight is "to be determined", nothing counts towards the total.
         */
        BigDecimal manualFreightEur,

        List<SalesOrderLine> lines,

        /**
         * Hand-built pallet layout; empty means the calculator's stacking
         * applies. Once pallets exist the freight counts them instead.
         */
        List<OrderPallet> pallets
) {
    public List<SalesOrderLine> lines() {
        return lines == null ? List.of() : lines;
    }

    public List<OrderPallet> pallets() {
        return pallets == null ? List.of() : pallets;
    }

    public DeliveryTermsState deliveryTerms() {
        return deliveryTerms == null ? DeliveryTermsState.VOLLEDIG : deliveryTerms;
    }

    /** The terms that actually apply: the order's own, or the customer's. */
    public String paymentTermsOr(String customerDefault) {
        return paymentTerms == null || paymentTerms.isBlank() ? customerDefault : paymentTerms;
    }

    public FreightState freight() {
        return freight == null ? FreightState.BEREKEND : freight;
    }

    /**
     * The stored value without fallback.
     *
     * Needed on update: there, null means "the form did not send this
     * field", which is different from "set it to calculated".
     */
    public FreightState freightOrNull() {
        return freight;
    }
}
