package be.enrosed.catalog.domain;

import java.util.List;

/**
 * What a stock change knows beyond the movement itself: the container to take from first, the sales document, the
 * invoice whose pieces come back on a credit note, the container line a receipt came on, or the stock movements
 * whose batch takes a partner buyback (or the undo of a partner sale) puts back.
 */
public record StockBatchContext(Long pinPurchaseOrderId, Long salesOrderId, Long returnOfSalesOrderId,
        BatchSource receipt, List<Long> returnOfMovementIds) {

    public static final StockBatchContext NONE = new StockBatchContext(null, null, null, null);

    public StockBatchContext {
        returnOfMovementIds = returnOfMovementIds == null ? List.of() : List.copyOf(returnOfMovementIds);
    }

    /** Every context from before partner returns existed. */
    public StockBatchContext(Long pinPurchaseOrderId, Long salesOrderId, Long returnOfSalesOrderId, BatchSource receipt) {
        this(pinPurchaseOrderId, salesOrderId, returnOfSalesOrderId, receipt, List.of());
    }

    /** Pieces back from a partner: the open takes of these movements (null ids skipped) are restored first. */
    public static StockBatchContext partnerReturn(List<Long> movementIds) {
        if (movementIds == null) return NONE;
        List<Long> ids = movementIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? NONE : new StockBatchContext(null, null, null, null, ids);
    }

    /** Take from this container's batch first (damage or shortage reported on it); NONE when null. */
    public static StockBatchContext pinned(Long purchaseOrderId) {
        return purchaseOrderId == null ? NONE : new StockBatchContext(purchaseOrderId, null, null, null);
    }

    /** A shipment of a sales document, optionally pinned to a container (container sale, partner settlement). */
    public static StockBatchContext sale(long salesOrderId, Long pinPurchaseOrderId) {
        return new StockBatchContext(pinPurchaseOrderId, salesOrderId, null, null);
    }

    /** Goods back on a credit note: the moves of the invoice are restored first. */
    public static StockBatchContext saleReturn(long creditNoteId, long invoiceId) {
        return new StockBatchContext(null, creditNoteId, invoiceId, null);
    }

    /** Pieces booked from a received container line. */
    public static StockBatchContext receipt(BatchSource source) {
        return source == null ? NONE : new StockBatchContext(null, null, null, source);
    }
}
