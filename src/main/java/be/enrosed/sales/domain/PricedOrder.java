package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

/** Volledig doorgerekende verkooporder. */
public record PricedOrder(List<Line> lines, Totals totals, Validation validation) {

    public record Line(
            Long productId,
            String sku,
            /** Description in our own language, for the screens here. */
            String description,
            /**
             * The same description in the customer's language, for the quote
             * and the portal. Without a translation it is simply identical.
             */
            String customerDescription,
            String photoUrl,
            int quantity,
            int cartons,
            int cartonsPerPallet,
            int pallets,
            BigDecimal cbm,
            BigDecimal weightKg,

            BigDecimal unitPrice,
            BigDecimal gross,
            BigDecimal tierPercent,
            BigDecimal manualPercent,
            BigDecimal discountPct,
            BigDecimal discountAmount,
            BigDecimal net,
            BigDecimal netUnitPrice,

            /* Internal side - does not belong on the customer document. */
            BigDecimal landedUnitCost,
            BigDecimal costTotal,
            BigDecimal marginEur,
            BigDecimal marginPct,

            Integer nextTierAtQuantity,
            BigDecimal nextTierPercent,

            /* Voorraad en levering. */
            int stockQuantity,
            boolean inStock,
            int shortfall,
            String deliveryDate,
            String deliveryWeek,
            String deliveryExplanation
    ) {}

    public record Totals(
            int pieces,
            int cartons,
            int palletsStrict,
            int palletsOptimised,
            BigDecimal cbm,
            BigDecimal weightKg,

            BigDecimal gross,
            BigDecimal lineDiscountTotal,
            BigDecimal subtotal,
            BigDecimal orderDiscountPercent,
            BigDecimal orderDiscountAmount,
            /* Loose extra discount, e.g. a fair discount. */
            BigDecimal extraDiscountPercent,
            String extraDiscountLabel,
            BigDecimal extraDiscountAmount,
            BigDecimal goodsTotal,

            BigDecimal freight,
            boolean freightIsMinimum,
            BigDecimal handling,
            BigDecimal shippingTotal,
            BigDecimal total,
            BigDecimal vatRatePct,
            BigDecimal vatAmount,
            BigDecimal totalInclVat,
            /* VAT regime and the sentence that belongs with it on the document. */
            VatTreatment vatTreatment,
            String vatLegalMention,
            String vatReason,

            BigDecimal costTotal,
            BigDecimal marginEur,
            BigDecimal marginPct,
            BigDecimal marginAfterFreightEur
    ) {}

    public record Validation(
            BigDecimal minOrderValue,
            boolean meetsMinimum,
            BigDecimal shortfall,
            boolean hasLines,
            boolean countrySelected,
            List<String> productsWithoutCost
    ) {}
}
