package be.enrosed.sales.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.LandedCost;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The agreed purchase pricing total, distinct from external-cost reconciliation and auction profit. */
public final class PartnerAdvanceBasis {
    private PartnerAdvanceBasis() {}

    public enum Kind { EXTERNAL_FORECAST, PURCHASE_TOTAL_WITH_SEPARATE_COSTS }

    public static BigDecimal total(LandedCost costing) {
        if (costing == null || costing.totals() == null || costing.totals().totalWithSeparateCostsEur() == null)
            throw new BusinessRuleException("Bereken eerst het inkooptotaal inclusief aparte kosten voor het partnervoorschot");
        return Money.money(costing.totals().totalWithSeparateCostsEur());
    }

    public static BigDecimal amount(LandedCost costing, BigDecimal financingPct) {
        return amount(total(costing), financingPct);
    }

    public static BigDecimal amount(BigDecimal total, BigDecimal financingPct) {
        if (financingPct == null || financingPct.signum() < 0 || financingPct.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new BusinessRuleException("De financiering ligt tussen 0 en 100 procent");
        return total.multiply(financingPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
