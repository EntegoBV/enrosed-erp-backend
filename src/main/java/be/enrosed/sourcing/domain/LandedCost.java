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
            BigDecimal pieceShare,
            /** This line's share of the inspection and the other named costs; inside totalEur and the piece price. */
            BigDecimal separateEur
    ) {
        /** Compatibility for callers written before the separate costs joined the piece price. */
        public Line(Long productId, String productName, int quantity, int cartons, BigDecimal cbm,
                    BigDecimal goodsUsd, BigDecimal goodsEur, BigDecimal originEur, BigDecimal freightEur,
                    BigDecimal customsValueEur, BigDecimal dutyRatePct, String dutySource, BigDecimal dutyEur,
                    BigDecimal destinationEur, BigDecimal extraRevenueEur, BigDecimal totalEur, BigDecimal landedUnitEur,
                    BigDecimal cbmShare, BigDecimal valueShare, BigDecimal pieceShare) {
            this(productId, productName, quantity, cartons, cbm, goodsUsd, goodsEur, originEur, freightEur, customsValueEur,
                    dutyRatePct, dutySource, dutyEur, destinationEur, extraRevenueEur, totalEur, landedUnitEur,
                    cbmShare, valueShare, pieceShare, BigDecimal.ZERO);
        }
    }

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
            /** Factory inspection, shown by name; spread over the lines, so it sits inside totalEur and every piece price. */
            BigDecimal inspectionEur,
            /** The charged other costs by name, for the sheets; spread the same way as the inspection. */
            List<OtherCost> otherCosts,
            /** The other costs added up. */
            BigDecimal otherCostsEur,
            /** Inspection plus other costs: what the container cost on top of goods, freight, duty and handling. */
            BigDecimal separateCostsEur,
            /** totalEur plus the separate costs when they sit apart; equal to totalEur when a key spread them. */
            BigDecimal totalWithSeparateCostsEur,
            /** True when a key spread the inspection and other costs into the piece prices; false when they sit apart. */
            boolean separateCostsInPiecePrice
    ) {
        /** Compatibility for callers written before the inspection had a key of its own: apart. */
        public Totals(int pieces, int cartons, BigDecimal cbm, BigDecimal goodsUsd, BigDecimal goodsEur,
                      BigDecimal originEur, BigDecimal freightEur, BigDecimal customsValueEur, BigDecimal dutyEur,
                      BigDecimal destinationEur, BigDecimal extraRevenueEur, BigDecimal totalEur,
                      BigDecimal averageUnitEur, BigDecimal effectiveDutyPct, BigDecimal inspectionEur,
                      List<OtherCost> otherCosts, BigDecimal otherCostsEur, BigDecimal separateCostsEur,
                      BigDecimal totalWithSeparateCostsEur) {
            this(pieces, cartons, cbm, goodsUsd, goodsEur, originEur, freightEur, customsValueEur, dutyEur,
                    destinationEur, extraRevenueEur, totalEur, averageUnitEur, effectiveDutyPct, inspectionEur,
                    otherCosts, otherCostsEur, separateCostsEur, totalWithSeparateCostsEur, false);
        }

        /** Compatibility for callers written before the separate cost lines existed. */
        public Totals(int pieces, int cartons, BigDecimal cbm, BigDecimal goodsUsd, BigDecimal goodsEur,
                      BigDecimal originEur, BigDecimal freightEur, BigDecimal customsValueEur, BigDecimal dutyEur,
                      BigDecimal destinationEur, BigDecimal extraRevenueEur, BigDecimal totalEur,
                      BigDecimal averageUnitEur, BigDecimal effectiveDutyPct) {
            this(pieces, cartons, cbm, goodsUsd, goodsEur, originEur, freightEur, customsValueEur, dutyEur,
                    destinationEur, extraRevenueEur, totalEur, averageUnitEur, effectiveDutyPct,
                    BigDecimal.ZERO, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, totalEur);
        }

        /** True when the sheets have an inspection or other named cost to show inside the landed total. */
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
