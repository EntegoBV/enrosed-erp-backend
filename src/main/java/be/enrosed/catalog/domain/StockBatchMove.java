package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One signed change of a batch; {@code batchId} null = pieces no batch could give ("zonder batch"). {@code linkMoveId}
 * means, by shape: a return restoring a take (batch set, pieces &gt; 0), a later batch settling an unassigned take
 * (batch set, pieces &lt; 0) or an unassigned take resolved without a batch (no batch, pieces &gt; 0).
 */
public record StockBatchMove(Long id, Long batchId, long productId, Long stockMovementId, Long linkMoveId, String kind,
        int pieces, int unitPieces, BigDecimal unitCostEur, BigDecimal unitCostExclEnrosedEur, Long salesOrderId,
        Long ticaSaleId, Long purchaseOrderId, boolean pinned, String reference, Instant at, String actor) {
}
