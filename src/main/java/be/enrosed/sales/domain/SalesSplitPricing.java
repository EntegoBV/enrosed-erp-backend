package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

/** Agreed commercial amounts allocated once; current stock and delivery estimates remain live. */
public record SalesSplitPricing(List<Line> lines, BigDecimal orderTierPercent,
        BigDecimal orderDiscountAmount, BigDecimal extraDiscountPercent, BigDecimal extraDiscountAmount,
        BigDecimal freight, BigDecimal handling, boolean sourceMeetsMinimum,
        BigDecimal vatRatePct, VatTreatment vatTreatment, BigDecimal goodsTotal) {
    public record Line(long productId, int quantity, BigDecimal unitPrice, BigDecimal tierPercent,
            BigDecimal manualPercent, BigDecimal gross, BigDecimal discountAmount, BigDecimal net,
            BigDecimal landedUnitCost, BigDecimal costTotal) {}
}
