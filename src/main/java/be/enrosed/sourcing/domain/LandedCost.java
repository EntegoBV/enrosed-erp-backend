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
            BigDecimal effectiveDutyPct,
            /** Factory inspection: its own line, never inside totalEur or a piece price. */
            BigDecimal inspectionEur,
            /** The charged other costs by name, for the sheets; same rule as the inspection. */
            List<OtherCost> otherCosts,
            /** The other costs added up. */
            BigDecimal otherCostsEur,
            /** Inspection plus other costs: everything booked apart from the piece price. */
            BigDecimal separateCostsEur,
            /** totalEur plus the separate costs, for the bottom line of the internal sheets. */
            BigDecimal totalWithSeparateCostsEur
    ) {
        /** Compatibility for callers written before the separate cost lines existed. */
        public Totals(int pieces, int cartons, BigDecimal cbm, BigDecimal goodsUsd, BigDecimal goodsEur,
                      BigDecimal originEur, BigDecimal freightEur, BigDecimal customsValueEur, BigDecimal dutyEur,
                      BigDecimal destinationEur, BigDecimal extraRevenueEur, BigDecimal totalEur,
                      BigDecimal averageUnitEur, BigDecimal effectiveDutyPct) {
            this(pieces, cartons, cbm, goodsUsd, goodsEur, originEur, freightEur, customsValueEur, dutyEur,
                    destinationEur, extraRevenueEur, totalEur, averageUnitEur, effectiveDutyPct,
                    BigDecimal.ZERO, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, totalEur);
        }

        /** True when the sheets have something to print under the landed total. */
        public boolean hasSeparateCosts() {
            return separateCostsEur != null && separateCostsEur.signum() > 0;
        }
    }

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
