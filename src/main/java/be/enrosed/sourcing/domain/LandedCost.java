package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Uitkomst van de kostprijsberekening van een container.
 *
 * De volgorde van de velden volgt de weg die de goederen afleggen:
 * goederen, origin, zeevracht - samen de douanewaarde - dan het invoerrecht,
 * en pas daarna de kosten aan deze kant.
 */
public record LandedCost(List<Line> lines, Totals totals, ContainerFill containerFill) {

    public record Line(
            Long productId,
            String productName,
            int quantity,
            int cartons,
            BigDecimal cbm,

            BigDecimal goodsUsd,
            BigDecimal goodsEur,
            BigDecimal originEur,
            BigDecimal freightEur,
            BigDecimal customsValueEur,

            BigDecimal dutyRatePct,
            String dutySource,
            BigDecimal dutyEur,

            BigDecimal destinationEur,
            BigDecimal extraRevenueEur,

            BigDecimal totalEur,
            BigDecimal landedUnitEur,

            BigDecimal cbmShare,
            BigDecimal valueShare,
            BigDecimal pieceShare
    ) {}

    public record Totals(
            int pieces,
            int cartons,
            BigDecimal cbm,
            BigDecimal goodsUsd,
            BigDecimal goodsEur,
            BigDecimal originEur,
            BigDecimal freightEur,
            BigDecimal customsValueEur,
            BigDecimal dutyEur,
            BigDecimal destinationEur,
            BigDecimal extraRevenueEur,
            BigDecimal totalEur,
            BigDecimal averageUnitEur,
            BigDecimal effectiveDutyPct
    ) {}

    public record ContainerFill(
            String containerCode,
            BigDecimal capacityCbm,
            BigDecimal usedCbm,
            BigDecimal fillPercent,
            BigDecimal freeCbm,
            BigDecimal overflowCbm
    ) {}
}
