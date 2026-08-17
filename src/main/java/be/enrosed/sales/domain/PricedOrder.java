package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

/** Volledig doorgerekende verkooporder. */
public record PricedOrder(List<Line> lines, Totals totals, Validation validation) {

    public record Line(
            Long productId,
            String sku,
            /** Omschrijving in onze eigen taal, voor de schermen hier. */
            String description,
            /**
             * Dezelfde omschrijving in de taal van de klant, voor de offerte en
             * het portaal. Zonder vertaling is dit gewoon hetzelfde.
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

            /* Interne kant - hoort niet op het klantdocument. */
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
            /* Losse extra korting, bv. een beurskorting. */
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
            /* BTW-regime en de zin die daarbij op het document hoort. */
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
