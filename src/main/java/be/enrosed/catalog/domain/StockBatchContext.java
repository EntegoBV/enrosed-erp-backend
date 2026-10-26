package be.enrosed.catalog.domain;

/**
 * What a stock change knows beyond the movement itself: the container to take from first, the sales document, the
 * invoice whose pieces come back on a credit note, or the container line a receipt came on.
 */
public record StockBatchContext(Long pinPurchaseOrderId, Long salesOrderId, Long returnOfSalesOrderId,
        BatchSource receipt) {

    public static final StockBatchContext NONE = new StockBatchContext(null, null, null, null);

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
