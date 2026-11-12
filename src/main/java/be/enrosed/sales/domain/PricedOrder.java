package be.enrosed.sales.domain;

import be.enrosed.catalog.domain.OrderStep;
import com.fasterxml.jackson.annotation.JsonInclude;

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

    /**
     * One pallet of the automatic layout, bottom to top. Each product lies in whole layers of its
     * own; a short one (its last, or one the weight cut) is as high as a full one. The height
     * includes the pallet base; the weight is 0 when none of its cartons has one.
     */
    public record AutoPallet(List<Item> items, BigDecimal heightCm, BigDecimal weightKg) {
        public AutoPallet {
            items = items == null ? List.of() : List.copyOf(items);
        }

        /** A product's cartons on this pallet and the layers they take. */
        public record Item(long productId, int cartons, int layers) {}
    }

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
            /** Product margin after all line and order discounts, excluding freight and VAT. */
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
            String deliveryExplanation,
            boolean unavailable, Integer requestedQuantity,

            /** The product's own "Verkocht per", for wording only. */
            OrderStep orderStep,
            /**
             * The step this line was rounded to, in sales units: the one the line remembers or the
             * product's, and 1 where the document keeps exact pieces (partner deal, container cost,
             * credit note, a split part). A client snaps to this and copies no exemption rules.
             */
            int unitsPerOrderStep,

            /**
             * The cost the line expects (the quote snapshot, the split allocation or the card): what
             * {@code landedUnitCost} was before the goods left. Internal side.
             */
            BigDecimal expectedUnitCost,
            /**
             * The batch cost of the pieces that left at shipment when the margin uses it; null before shipment and
             * where the document keeps its own rule (credit note, partner deal, container cost, split part).
             * {@code landedUnitCost} is what the margin uses. Internal side.
             */
            BigDecimal stockUnitCost,

            /* Staff side only, read-only, left out of the JSON while null so an older document reads as
               before; never on a customer document, the PDF, the portal or the website. */
            /** The line's own {@code SalesOrderLine.priceSource}: CUSTOMER, STANDARD, MANUAL or null. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String priceSource,
            /**
             * What the line would cost on the catalogue price of today (same sales unit), on the one
             * document view where it is worth a look; null elsewhere.
             */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            BigDecimal standardUnitPriceEur,
            /** The customer's active agreed price of today for this product, there too; null when none. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            BigDecimal customerUnitPriceEur,
            /** The line's own {@code SalesOrderLine.perPiece}: TRUE where staff chose per piece, else left out. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Boolean perPiece,
            /**
             * TRUE where this line may be switched between its product's order step and per piece: the
             * document rounds and the product's step of today is above 1. Left out elsewhere.
             */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Boolean perPieceChoice
    ) {
        /** A line priced without the staff figures on where its price comes from. */
        public Line(Long productId, String sku, String description, String customerDescription, String photoUrl,
                    int quantity, int cartons, int cartonsPerPallet, int pallets, int cartonsPerLayer,
                    int palletLayers, BigDecimal calculatedPalletHeightCm, BigDecimal cbm, BigDecimal weightKg,
                    BigDecimal unitPrice, BigDecimal gross, BigDecimal tierPercent, BigDecimal manualPercent,
                    BigDecimal discountPct, BigDecimal discountAmount, BigDecimal net, BigDecimal netUnitPrice,
                    BigDecimal landedUnitCost, BigDecimal costTotal, BigDecimal marginEur, BigDecimal marginPct,
                    Integer nextTierAtQuantity, BigDecimal nextTierPercent, Integer stockQuantity,
                    boolean inventoryKnown, boolean inStock, Integer shortfall, String deliveryDate,
                    String deliveryWeek, String deliveryExplanation, boolean unavailable, Integer requestedQuantity,
                    OrderStep orderStep, int unitsPerOrderStep, BigDecimal expectedUnitCost, BigDecimal stockUnitCost) {
            this(productId, sku, description, customerDescription, photoUrl, quantity, cartons, cartonsPerPallet,
                    pallets, cartonsPerLayer, palletLayers, calculatedPalletHeightCm, cbm, weightKg, unitPrice, gross,
                    tierPercent, manualPercent, discountPct, discountAmount, net, netUnitPrice, landedUnitCost,
                    costTotal, marginEur, marginPct, nextTierAtQuantity, nextTierPercent, stockQuantity,
                    inventoryKnown, inStock, shortfall, deliveryDate, deliveryWeek, deliveryExplanation, unavailable,
                    requestedQuantity, orderStep, unitsPerOrderStep, expectedUnitCost, stockUnitCost, null, null, null,
                    null, null);
        }
        /** A line priced without a stock cost: the expected cost is the one the margin uses. */
        public Line(Long productId, String sku, String description, String customerDescription, String photoUrl,
                    int quantity, int cartons, int cartonsPerPallet, int pallets, int cartonsPerLayer,
                    int palletLayers, BigDecimal calculatedPalletHeightCm, BigDecimal cbm, BigDecimal weightKg,
                    BigDecimal unitPrice, BigDecimal gross, BigDecimal tierPercent, BigDecimal manualPercent,
                    BigDecimal discountPct, BigDecimal discountAmount, BigDecimal net, BigDecimal netUnitPrice,
                    BigDecimal landedUnitCost, BigDecimal costTotal, BigDecimal marginEur, BigDecimal marginPct,
                    Integer nextTierAtQuantity, BigDecimal nextTierPercent, Integer stockQuantity,
                    boolean inventoryKnown, boolean inStock, Integer shortfall, String deliveryDate,
                    String deliveryWeek, String deliveryExplanation, boolean unavailable, Integer requestedQuantity,
                    OrderStep orderStep, int unitsPerOrderStep) {
            this(productId, sku, description, customerDescription, photoUrl, quantity, cartons, cartonsPerPallet,
                    pallets, cartonsPerLayer, palletLayers, calculatedPalletHeightCm, cbm, weightKg, unitPrice, gross,
                    tierPercent, manualPercent, discountPct, discountAmount, net, netUnitPrice, landedUnitCost,
                    costTotal, marginEur, marginPct, nextTierAtQuantity, nextTierPercent, stockQuantity,
                    inventoryKnown, inStock, shortfall, deliveryDate, deliveryWeek, deliveryExplanation, unavailable,
                    requestedQuantity, orderStep, unitsPerOrderStep, landedUnitCost, null);
        }

        public Line withFinalMargin(BigDecimal allocatedCost, BigDecimal discountedNet) {
            BigDecimal margin = discountedNet.subtract(allocatedCost);
            BigDecimal percent = discountedNet.signum() > 0
                    ? margin.multiply(be.enrosed.shared.Money.HUNDRED).divide(discountedNet, 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new Line(productId, sku, description, customerDescription, photoUrl, quantity,
                    cartons, cartonsPerPallet, pallets, cartonsPerLayer, palletLayers, calculatedPalletHeightCm,
                    cbm, weightKg, unitPrice, gross, tierPercent, manualPercent, discountPct, discountAmount,
                    net, netUnitPrice, landedUnitCost, allocatedCost, margin, percent, nextTierAtQuantity,
                    nextTierPercent, stockQuantity, inventoryKnown, inStock, shortfall, deliveryDate,
                    deliveryWeek, deliveryExplanation, unavailable, requestedQuantity, orderStep, unitsPerOrderStep,
                    expectedUnitCost, stockUnitCost, priceSource, standardUnitPriceEur, customerUnitPriceEur,
                    perPiece, perPieceChoice);
        }

        public Line(Long productId, String sku, String description, /** * The same description in the customer's language, for the quote * and the portal. Without a translation it is simply identical. */ String customerDescription, String photoUrl, int quantity, int cartons, int cartonsPerPallet, int pallets, int cartonsPerLayer, int palletLayers, BigDecimal calculatedPalletHeightCm, BigDecimal cbm, BigDecimal weightKg, BigDecimal unitPrice, BigDecimal gross, BigDecimal tierPercent, BigDecimal manualPercent, BigDecimal discountPct, BigDecimal discountAmount, BigDecimal net, BigDecimal netUnitPrice, BigDecimal landedUnitCost, BigDecimal costTotal, BigDecimal marginEur, BigDecimal marginPct, Integer nextTierAtQuantity, BigDecimal nextTierPercent, Integer stockQuantity, boolean inventoryKnown, boolean inStock, Integer shortfall, String deliveryDate, String deliveryWeek, String deliveryExplanation) {
            this(productId, sku, description, customerDescription, photoUrl, quantity, cartons, cartonsPerPallet, pallets, cartonsPerLayer, palletLayers, calculatedPalletHeightCm, cbm, weightKg, unitPrice, gross, tierPercent, manualPercent, discountPct, discountAmount, net, netUnitPrice, landedUnitCost, costTotal, marginEur, marginPct, nextTierAtQuantity, nextTierPercent, stockQuantity, inventoryKnown, inStock, shortfall, deliveryDate, deliveryWeek, deliveryExplanation, false, null, OrderStep.CARTON, 1);
        }

        /** Never null: a line from an older caller reads as the full carton. */
        public OrderStep orderStep() {
            return orderStep == null ? OrderStep.CARTON : orderStep;
        }

        /**
         * The step in the customer's words: a line that counts per piece reads as UNIT, whatever its
         * product says, so a part carton is never told as a whole one. {@code orderStep} stays the
         * product's own setting for the staff screens.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public OrderStep customerOrderStep() {
            return Boolean.TRUE.equals(perPiece) ? OrderStep.UNIT : orderStep();
        }
    }

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
            BigDecimal extraLinesTotal,

            /** The rule this document counts its automatic pallets by; it never changes for a document. */
            PalletCountRule palletRule,
            /**
             * The automatic pallet count under that rule, 0 when not palletised. Freight, the PDF,
             * the portal and the website quote bill it unless hand-built pallets exist.
             */
            int palletsAutomatic,
            /** The automatic layout under that rule; empty when not palletised or nothing fits. */
            List<AutoPallet> autoPallets
    ) {
        public Totals {
            palletRule = palletRule == null ? PalletCountRule.STRICT : palletRule;
            autoPallets = autoPallets == null ? List.of() : List.copyOf(autoPallets);
        }

        /** Compatibility for callers written before the pallet count rule: their count is the strict one. */
        public Totals(int pieces, int cartons, int palletsStrict, int palletsOptimised, int palletsManual,
                      int unassignedCartons, BigDecimal palletBaseHeightCm, BigDecimal palletMaxHeightCm,
                      BigDecimal cbm, BigDecimal weightKg, BigDecimal gross, BigDecimal lineDiscountTotal,
                      BigDecimal subtotal, BigDecimal orderDiscountPercent, BigDecimal orderDiscountAmount,
                      BigDecimal extraDiscountPercent, String extraDiscountLabel, BigDecimal extraDiscountAmount,
                      BigDecimal goodsTotal, BigDecimal freight, boolean freightIsMinimum, BigDecimal handling,
                      BigDecimal shippingTotal, BigDecimal total, BigDecimal vatRatePct, BigDecimal vatAmount,
                      BigDecimal totalInclVat, VatTreatment vatTreatment, String vatLegalMention, String vatReason,
                      BigDecimal costTotal, BigDecimal marginEur, BigDecimal marginPct,
                      BigDecimal marginAfterFreightEur, BigDecimal extraLinesTotal) {
            this(pieces, cartons, palletsStrict, palletsOptimised, palletsManual, unassignedCartons,
                    palletBaseHeightCm, palletMaxHeightCm, cbm, weightKg, gross, lineDiscountTotal, subtotal,
                    orderDiscountPercent, orderDiscountAmount, extraDiscountPercent, extraDiscountLabel,
                    extraDiscountAmount, goodsTotal, freight, freightIsMinimum, handling, shippingTotal, total,
                    vatRatePct, vatAmount, totalInclVat, vatTreatment, vatLegalMention, vatReason, costTotal,
                    marginEur, marginPct, marginAfterFreightEur, extraLinesTotal,
                    PalletCountRule.STRICT, palletsStrict, List.of());
        }

        /**
         * The automatic pallets that carry this product: its pallet positions on the customer
         * documents while no hand layout exists. Under the strict rule exactly its own pallets.
         */
        public int autoPalletsCarrying(long productId) {
            return (int) autoPallets.stream()
                    .filter(pallet -> pallet.items().stream().anyMatch(item -> item.productId() == productId))
                    .count();
        }

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
