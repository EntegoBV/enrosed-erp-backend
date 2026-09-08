package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** Economic recognition excludes advances; bank receipts remain separate events. All amounts exclude VAT. */
public record SalesAccounting(BigDecimal recognizedRevenueEur, BigDecimal recognizedCostEur,
                              BigDecimal recognizedProfitEur, int recognizedQuantity) {}
