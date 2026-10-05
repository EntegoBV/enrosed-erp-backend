package be.enrosed.sales.adapter.in.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.*;

/** Apply at the HTTP boundary, including already-persisted idempotency responses. */
final class PublicQuotePriceVisibility {
    private PublicQuotePriceVisibility() {}

    static ConfigurationResponse apply(ConfigurationResponse value, boolean visible) {
        if (value == null || visible) return value;
        return new ConfigurationResponse(value.currency(), value.priceBasis(), value.quantityBasis(),
                value.fulfillmentMethods(), value.disclaimerCode(),
                value.countries().stream().map(country -> new CountryOption(country.code(),
                        country.name(), null, country.transitDays())).toList(),
                value.products().stream().map(product -> new ProductPrice(product.productId(),
                        null, false, product.piecesPerCarton(), product.salesUnit(), product.piecesPerDisplay(),
                        product.unit())).toList(),
                value.pickupLocations(), false);
    }

    static EstimateResponse apply(EstimateResponse value, boolean visible) {
        if (value == null) return null;
        if (visible) {
            // Older persisted idempotency responses predate pricesVisible. Jackson
            // defaults their absent boolean to false; the current policy is authoritative.
            return value.pricesVisible() ? value : new EstimateResponse(value.currency(), value.priceBasis(),
                    value.fulfillment(), value.pickupLocation(), value.estimateStatus(), value.disclaimerCode(),
                    value.lines(), value.shipping(), value.totals(), value.validation(), true);
        }
        ShippingEstimate shipping = value.shipping();
        TotalsEstimate totals = value.totals();
        ValidationSummary validation = value.validation();
        List<String> messages = new ArrayList<>();
        messages.add("PRICES_ON_REQUEST");
        if (validation != null && validation.messageCodes() != null) {
            validation.messageCodes().stream()
                    .filter(code -> !Set.of("PRICE_TO_CONFIRM", "MINIMUM_NOT_MET", "PRICES_ON_REQUEST").contains(code))
                    .forEach(messages::add);
        }
        return new EstimateResponse(value.currency(), value.priceBasis(), value.fulfillment(),
                value.pickupLocation(), value.estimateStatus(), value.disclaimerCode(),
                value.lines().stream().map(line -> new LineEstimate(line.productId(), line.sku(),
                        line.cartons(), line.quantityPieces(), line.piecesPerCarton(),
                        null, null, null, false)).toList(),
                shipping == null ? null : new ShippingEstimate(shipping.status(), shipping.source(),
                        null, null, null, shipping.pallets(), shipping.cartons()),
                totals == null ? null : new TotalsEstimate(null, null, null, null, null, null,
                        null, null, null, null, null, totals.vatTreatment(), totals.vatProvisional()),
                validation == null ? null : new ValidationSummary(validation.canSubmit(), true,
                        null, null, null, List.copyOf(messages)), false);
    }

    static SubmissionResponse apply(SubmissionResponse value, boolean visible) {
        if (value == null) return null;
        return new SubmissionResponse(value.reference(), value.status(), value.bindingStatus(),
                value.disclaimerCode(), apply(value.estimate(), visible));
    }
}
