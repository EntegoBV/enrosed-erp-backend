package be.enrosed.sales.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Customer-safe wire contract for the public website quote builder. */
public final class PublicQuoteDtos {

    private PublicQuoteDtos() {}

    public record Destination(
            String countryCode,
            String postalCode,
            String city,
            String address
    ) {}

    /**
     * One product of a request, with exactly one of two quantities. {@code cartons} counts full
     * outer cartons (every client before order steps, and products sold per carton); the server
     * multiplies by the carton content. {@code quantity} counts sales units, for a product sold per
     * verpakking or per stuk (allowed for any product whose carton content is known when it sells
     * per carton); the server rounds it up to the product's order step. An absent field is not
     * written, so a carton item reads exactly as it always did.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemRequest(Long productId, Integer cartons, Integer quantity) {
        /** The carton contract of every client written before order steps. */
        public ItemRequest(Long productId, Integer cartons) {
            this(productId, cartons, null);
        }
    }

    public record PreviewRequest(
            String language,
            String fulfillment,
            String vatNumber,
            Destination destination,
            List<ItemRequest> items,
            Long pickupLocationId
    ) {
        /** Compatibility for clients written before selectable collection points. */
        public PreviewRequest(String language, String fulfillment, String vatNumber,
                              Destination destination, List<ItemRequest> items) {
            this(language, fulfillment, vatNumber, destination, items, null);
        }
    }

    public record SubmitRequest(
            String language,
            String fulfillment,
            String vatNumber,
            Destination destination,
            List<ItemRequest> items,
            /** Legal/home country of the buyer; distinct from a delivery destination. */
            String companyCountryCode,
            String companyName,
            String contactName,
            String email,
            String phone,
            String notes,
            Boolean privacyAccepted,
            /** Honeypot. Real clients leave this field empty. */
            String website,
            Long pickupLocationId,
            /** Server-minted proof that this form was open for a plausible amount of time. */
            String formToken,
            /** Cloudflare Turnstile token; required only when Turnstile is configured. */
            String challengeToken,
            /** Tick box "I would also like a login"; the answer never tells whether it was stored. */
            Boolean loginRequested
    ) {
        /** Compatibility for clients and tests written before the login tick box. */
        public SubmitRequest(String language, String fulfillment, String vatNumber,
                             Destination destination, List<ItemRequest> items,
                             String companyCountryCode, String companyName, String contactName,
                             String email, String phone, String notes, Boolean privacyAccepted,
                             String website, Long pickupLocationId, String formToken,
                             String challengeToken) {
            this(language, fulfillment, vatNumber, destination, items, companyCountryCode,
                    companyName, contactName, email, phone, notes, privacyAccepted, website,
                    pickupLocationId, formToken, challengeToken, null);
        }

        /** Compatibility for clients written before selectable collection points. */
        public SubmitRequest(String language, String fulfillment, String vatNumber,
                             Destination destination, List<ItemRequest> items,
                             String companyCountryCode, String companyName, String contactName,
                             String email, String phone, String notes, Boolean privacyAccepted,
                             String website) {
            this(language, fulfillment, vatNumber, destination, items, companyCountryCode,
                    companyName, contactName, email, phone, notes, privacyAccepted, website,
                    null, null, null);
        }

        /** Compatibility for clients/tests with collection points but before form protection. */
        public SubmitRequest(String language, String fulfillment, String vatNumber,
                             Destination destination, List<ItemRequest> items,
                             String companyCountryCode, String companyName, String contactName,
                             String email, String phone, String notes, Boolean privacyAccepted,
                             String website, Long pickupLocationId) {
            this(language, fulfillment, vatNumber, destination, items, companyCountryCode,
                    companyName, contactName, email, phone, notes, privacyAccepted, website,
                    pickupLocationId, null, null);
        }
    }

    public record ConfigurationResponse(
            String currency,
            String priceBasis,
            String quantityBasis,
            List<String> fulfillmentMethods,
            String disclaimerCode,
            List<CountryOption> countries,
            List<ProductPrice> products,
            List<PickupLocation> pickupLocations,
            boolean pricesVisible,
            /**
             * "PER_PRODUCT": each product publishes its own order step and a request may carry
             * {@code quantity} items. {@code quantityBasis} stays "FULL_CARTONS" for older sites.
             */
            String quantitySteps
    ) {
        public static final String PER_PRODUCT = "PER_PRODUCT";

        public ConfigurationResponse(String currency, String priceBasis, String quantityBasis,
                                     List<String> fulfillmentMethods, String disclaimerCode,
                                     List<CountryOption> countries, List<ProductPrice> products,
                                     List<PickupLocation> pickupLocations) {
            this(currency, priceBasis, quantityBasis, fulfillmentMethods, disclaimerCode,
                    countries, products, pickupLocations, true, PER_PRODUCT);
        }
        /** Compatibility for server/resource tests predating public pickup choices. */
        public ConfigurationResponse(String currency, String priceBasis, String quantityBasis,
                                     List<String> fulfillmentMethods, String disclaimerCode,
                                     List<CountryOption> countries, List<ProductPrice> products) {
            this(currency, priceBasis, quantityBasis, fulfillmentMethods, disclaimerCode,
                    countries, products, List.of());
        }
    }

    /** Public, customer-safe projection of one enabled stock location. */
    public record PickupLocation(
            Long id,
            String label,
            String address,
            String instructions,
            int position
    ) {}

    public record CountryOption(
            String code,
            String name,
            BigDecimal minimumOrderNet,
            int transitDays
    ) {}

    public record ProductPrice(
            Long productId,
            BigDecimal unitPriceNet,
            boolean priceAvailable,
            int piecesPerCarton,
            String salesUnit,
            Integer piecesPerDisplay,
            /* What one piece is called in the requested language; not a price, so never hidden. */
            be.enrosed.catalog.adapter.in.rest.UnitDto unit,
            /* "Verkocht per": CARTON, PACKAGE or UNIT, never null; not a price, so never hidden. */
            String orderStep,
            /* Sales units one order step moves; null for a carton step whose carton content is unknown. */
            Integer unitsPerOrderStep
    ) {
        public ProductPrice(Long productId, BigDecimal unitPriceNet, boolean priceAvailable, int piecesPerCarton,
                            String salesUnit, Integer piecesPerDisplay) {
            this(productId, unitPriceNet, priceAvailable, piecesPerCarton, salesUnit, piecesPerDisplay, null,
                    "CARTON", piecesPerCarton > 0 ? piecesPerCarton : null);
        }

        public ProductPrice(Long productId, BigDecimal unitPriceNet, boolean priceAvailable, int piecesPerCarton) {
            this(productId, unitPriceNet, priceAvailable, piecesPerCarton, "PIECE", null);
        }
    }

    public record EstimateResponse(
            String currency,
            String priceBasis,
            String fulfillment,
            PickupLocation pickupLocation,
            String estimateStatus,
            String disclaimerCode,
            List<LineEstimate> lines,
            ShippingEstimate shipping,
            TotalsEstimate totals,
            ValidationSummary validation,
            boolean pricesVisible
    ) {
        public EstimateResponse(String currency, String priceBasis, String fulfillment,
                                PickupLocation pickupLocation, String estimateStatus,
                                String disclaimerCode, List<LineEstimate> lines,
                                ShippingEstimate shipping, TotalsEstimate totals,
                                ValidationSummary validation) {
            this(currency, priceBasis, fulfillment, pickupLocation, estimateStatus,
                    disclaimerCode, lines, shipping, totals, validation, true);
        }
    }

    /**
     * {@code cartons} is what ships (a part carton is one carton), or the cartons asked for when
     * their content is still unknown; {@code quantityPieces} is the stored, rounded quantity in
     * sales units. {@code orderStep} names the product's step, {@code unitsPerOrderStep} the step
     * applied to this line (null for a carton step whose content is unknown).
     */
    public record LineEstimate(
            Long productId,
            String sku,
            int cartons,
            int quantityPieces,
            int piecesPerCarton,
            BigDecimal unitPriceNet,
            BigDecimal discountPct,
            BigDecimal lineTotalNet,
            boolean priceAvailable,
            String orderStep,
            Integer unitsPerOrderStep
    ) {}

    public record ShippingEstimate(
            String status,
            String source,
            BigDecimal freightNet,
            BigDecimal handlingNet,
            BigDecimal totalNet,
            int pallets,
            int cartons
    ) {}

    public record TotalsEstimate(
            BigDecimal goodsGrossNet,
            BigDecimal lineDiscountNet,
            BigDecimal goodsAfterLineDiscountNet,
            BigDecimal orderDiscountPct,
            BigDecimal orderDiscountNet,
            BigDecimal goodsNet,
            BigDecimal shippingNet,
            BigDecimal totalNet,
            BigDecimal vatRatePct,
            BigDecimal vatAmount,
            BigDecimal totalInclVat,
            String vatTreatment,
            boolean vatProvisional
    ) {}

    public record ValidationSummary(
            boolean canSubmit,
            boolean requiresReview,
            Boolean meetsMinimum,
            BigDecimal minimumOrderNet,
            BigDecimal minimumShortfallNet,
            List<String> messageCodes
    ) {}

    public record SubmissionResponse(
            String reference,
            String status,
            String bindingStatus,
            String disclaimerCode,
            EstimateResponse estimate
    ) {}

    public record ErrorResponse(
            String code,
            String message,
            Map<String, String> fieldErrors
    ) {}
}
