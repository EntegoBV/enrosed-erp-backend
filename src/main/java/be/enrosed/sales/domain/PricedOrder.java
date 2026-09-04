package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

/** Volledig doorgerekende verkooporder. */
public record PricedOrder(List<Line> lines, Totals totals, Validation validation,
                          /** The free lines as priced: description, quantity, unit price and their total. */
                          List<ExtraLine> extraLines) {

    /** Compatibility for callers written before the free lines existed. */
    public PricedOrder(List<Line> lines, Totals totals, Validation validation) {
        this(lines, totals, validation, List.of());
    }

    public PricedOrder {
        extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
    }

    /** One free line on the document: no product, no cartons, no tier. */
    public record ExtraLine(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {}

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
            /** Explainable stacking result; zero for loose-carton transport. */
            int cartonsPerLayer,
            int palletLayers,
            /** Tallest calculated pallet for this line, including the pallet base. */
            BigDecimal calculatedPalletHeightCm,
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
            Integer stockQuantity,
            boolean inventoryKnown,
            boolean inStock,
            Integer shortfall,
            String deliveryDate,
            String deliveryWeek,
            String deliveryExplanation
    ) {}

    public record Totals(
            int pieces,
            int cartons,
            int palletsStrict,
            int palletsOptimised,
            /** Hand-built pallets; 0 means the calculator's stacking applies. */
            int palletsManual,
            /** Cartons not on any hand-built pallet; only meaningful when palletsManual > 0. */
            int unassignedCartons,
            /** Effective pallet limits used for this calculation. */
            BigDecimal palletBaseHeightCm,
            BigDecimal palletMaxHeightCm,
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
            BigDecimal marginAfterFreightEur,

            /** The free lines added up; inside {@code total}, outside {@code goodsTotal} and the margin. */
            BigDecimal extraLinesTotal
    ) {
        /** Compatibility for callers written before the free lines existed. */
        public Totals(int pieces, int cartons, int palletsStrict, int palletsOptimised, int palletsManual,
                      int unassignedCartons, BigDecimal palletBaseHeightCm, BigDecimal palletMaxHeightCm,
                      BigDecimal cbm, BigDecimal weightKg, BigDecimal gross, BigDecimal lineDiscountTotal,
                      BigDecimal subtotal, BigDecimal orderDiscountPercent, BigDecimal orderDiscountAmount,
                      BigDecimal extraDiscountPercent, String extraDiscountLabel, BigDecimal extraDiscountAmount,
                      BigDecimal goodsTotal, BigDecimal freight, boolean freightIsMinimum, BigDecimal handling,
                      BigDecimal shippingTotal, BigDecimal total, BigDecimal vatRatePct, BigDecimal vatAmount,
                      BigDecimal totalInclVat, VatTreatment vatTreatment, String vatLegalMention, String vatReason,
                      BigDecimal costTotal, BigDecimal marginEur, BigDecimal marginPct,
                      BigDecimal marginAfterFreightEur) {
            this(pieces, cartons, palletsStrict, palletsOptimised, palletsManual, unassignedCartons,
                    palletBaseHeightCm, palletMaxHeightCm, cbm, weightKg, gross, lineDiscountTotal, subtotal,
                    orderDiscountPercent, orderDiscountAmount, extraDiscountPercent, extraDiscountLabel,
                    extraDiscountAmount, goodsTotal, freight, freightIsMinimum, handling, shippingTotal, total,
                    vatRatePct, vatAmount, totalInclVat, vatTreatment, vatLegalMention, vatReason, costTotal,
                    marginEur, marginPct, marginAfterFreightEur, BigDecimal.ZERO);
        }
    }

    public record Validation(
            BigDecimal minOrderValue,
            boolean meetsMinimum,
            BigDecimal shortfall,
            boolean hasLines,
            boolean countrySelected,
            List<String> productsWithoutCost,
            /** Products whose outer carton cannot produce weight/volume. */
            List<String> productsWithoutCartonDimensions,
            /** Palletised products that cannot fit within the selected profile and limits. */
            List<String> productsWithoutPalletFit,
            /** Empty when the selected freight strategy has every amount it needs. */
            String freightPricingIssue
    ) {}
}
