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

        /** Palletised by default; loose cartons keep their outer-carton CBM but use no pallet positions. */
        LoadMode loadMode,
        /** Footprint used by the automatic stacking calculation. */
        PalletProfile palletProfile,
        /** Optional total pallet height override in cm, including the wooden pallet itself. */
        BigDecimal maxPalletHeightCm,
        /** Tariff basis underneath the optional {@link FreightState#TE_BEPALEN} overlay. */
        FreightPricingStrategy freightPricingStrategy,
        /** Own EUR/m3 rate when {@code freightPricingStrategy == PER_CBM}. */
        BigDecimal freightRatePerCbmEur,

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

    /**
     * Customer-facing pallet positions containing one product. Automatic
     * stacking is the fallback; once a manual layout exists, that physical
     * layout is the truth. A mixed pallet may therefore count for more than
     * one product row without increasing the order's total pallet positions.
     */
    public int palletPositionsForProduct(long productId, int calculatedPallets) {
        if (loadMode() == LoadMode.LOOSE_CARTONS) return 0;
        if (pallets().isEmpty()) return Math.max(0, calculatedPallets);
        return (int) pallets().stream()
                .filter(pallet -> pallet != null && pallet.items().stream()
                        .anyMatch(item -> item != null && item.productId() == productId))
                .count();
    }

    /** Existing rows and older clients predate an explicit load mode. */
    public LoadMode loadMode() {
        return loadMode == null ? LoadMode.PALLETS : loadMode;
    }

    /** Raw value lets update code distinguish an omitted legacy JSON field. */
    public LoadMode loadModeOrNull() {
        return loadMode;
    }

    /** Existing palletised orders used the B × D: 120 × 80 cm footprint. */
    public PalletProfile palletProfile() {
        return palletProfile == null ? PalletProfile.EURO_120X80 : palletProfile;
    }

    /** Raw value lets update code distinguish an omitted legacy JSON field. */
    public PalletProfile palletProfileOrNull() {
        return palletProfile;
    }

    /**
     * Existing orders with an own total already behaved as fixed freight;
     * all other legacy rows used the destination-country pallet tariff.
     */
    public FreightPricingStrategy freightPricingStrategy() {
        if (freightPricingStrategy != null) return freightPricingStrategy;
        return manualFreightEur != null
                ? FreightPricingStrategy.FIXED
                : FreightPricingStrategy.COUNTRY_PALLET;
    }

    /** Raw value lets update code distinguish an omitted legacy JSON field. */
    public FreightPricingStrategy freightPricingStrategyOrNull() {
        return freightPricingStrategy;
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
