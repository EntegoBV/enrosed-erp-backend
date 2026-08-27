package be.enrosed.sales.adapter.in.rest;

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

    /** Public quantities are cartons; the server derives pieces from catalogue packaging. */
    public record ItemRequest(Long productId, Integer cartons) {}

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
            Long pickupLocationId
    ) {
        /** Compatibility for clients written before selectable collection points. */
        public SubmitRequest(String language, String fulfillment, String vatNumber,
                             Destination destination, List<ItemRequest> items,
                             String companyCountryCode, String companyName, String contactName,
                             String email, String phone, String notes, Boolean privacyAccepted,
                             String website) {
            this(language, fulfillment, vatNumber, destination, items, companyCountryCode,
                    companyName, contactName, email, phone, notes, privacyAccepted, website, null);
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
            List<PickupLocation> pickupLocations
    ) {
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
            int piecesPerCarton
    ) {}

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
            ValidationSummary validation
    ) {}

    public record LineEstimate(
            Long productId,
            String sku,
            int cartons,
            int quantityPieces,
            int piecesPerCarton,
            BigDecimal unitPriceNet,
            BigDecimal discountPct,
            BigDecimal lineTotalNet,
            boolean priceAvailable
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
            boolean meetsMinimum,
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
