package be.enrosed.catalog.domain;

/**
 * The invariant per product: batch pieces minus the open pieces no batch could give must equal the pieces on every
 * location (negatives included).
 */
public record StockBatchTotals(long productId, int stockPieces, int batchPieces, int unassignedPieces) {

    public int differencePieces() {
        return batchPieces - unassignedPieces - stockPieces;
    }
}
