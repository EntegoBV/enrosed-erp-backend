package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

/** Agreed commercial amounts allocated once; current stock and delivery estimates remain live. */
public record SalesSplitPricing(List<Line> lines, BigDecimal orderTierPercent,
        BigDecimal orderDiscountAmount, BigDecimal extraDiscountPercent, BigDecimal extraDiscountAmount,
        BigDecimal freight, BigDecimal handling, boolean sourceMeetsMinimum,
        BigDecimal vatRatePct, VatTreatment vatTreatment, BigDecimal goodsTotal,
        List<UnavailableReference> unavailableReferences) {
    public SalesSplitPricing(List<Line> lines, BigDecimal orderTierPercent, BigDecimal orderDiscountAmount,
                             BigDecimal extraDiscountPercent, BigDecimal extraDiscountAmount, BigDecimal freight,
                             BigDecimal handling, boolean sourceMeetsMinimum, BigDecimal vatRatePct,
                             VatTreatment vatTreatment, BigDecimal goodsTotal) {
        this(lines, orderTierPercent, orderDiscountAmount, extraDiscountPercent, extraDiscountAmount,
                freight, handling, sourceMeetsMinimum, vatRatePct, vatTreatment, goodsTotal, List.of());
    }
    public SalesSplitPricing { unavailableReferences = unavailableReferences == null ? List.of() : List.copyOf(unavailableReferences); }
    public record UnavailableReference(Line line, BigDecimal orderDiscountAmount) {}
    public record Line(long productId, int quantity, BigDecimal unitPrice, BigDecimal tierPercent,
            BigDecimal manualPercent, BigDecimal gross, BigDecimal discountAmount, BigDecimal net,
            BigDecimal landedUnitCost, BigDecimal costTotal) {}
}
