package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * What one shipment took per product. {@code valueEur} and {@code valueExclEnrosedEur} sum the pieces that had a cost
 * (unrounded; null when none had one); {@code piecesWithoutCost} are the pieces without a batch or without a cost.
 */
public record StockBatchSaleCost(long productId, int pieces, BigDecimal valueEur, BigDecimal valueExclEnrosedEur,
        int piecesWithoutCost) {
}
