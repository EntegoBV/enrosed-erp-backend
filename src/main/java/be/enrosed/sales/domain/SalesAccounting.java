package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Economic recognition excludes advances; bank receipts remain separate events. All amounts exclude VAT.
 * {@code recognizedEnrosedEur} is the Enrosed kost inside {@code recognizedCostEur} (signed like it): Enrosed pays
 * it to nobody, so Resultaat counts it as revenue (Emre, 2026-11-06); the margin here keeps it as cost.
 */
public record SalesAccounting(BigDecimal recognizedRevenueEur, BigDecimal recognizedCostEur,
                              BigDecimal recognizedProfitEur, int recognizedQuantity, BigDecimal recognizedEnrosedEur) {}
