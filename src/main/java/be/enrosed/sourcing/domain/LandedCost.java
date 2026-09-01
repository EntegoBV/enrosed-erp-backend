package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a container's landed-cost calculation.
 *
 * The field order follows the road the goods travel: goods, origin, sea
 * freight - together the customs value - then the import duty, and only
 * after that the costs on this side.
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
            BigDecimal overflowCbm,
            /** Minimum number of this container type required by CBM alone. */
            int minimumContainerCount
    ) {}
}
