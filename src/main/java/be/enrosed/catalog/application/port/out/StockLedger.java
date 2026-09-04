package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.StockMovement;

import java.util.List;

/** The stock book: append-only, newest first when read. */
public interface StockLedger {

    void record(StockMovement movement);

    List<StockMovement> forProduct(long productId);

    /** Every line that names this container: the damage and shortages reported after receipt. */
    List<StockMovement> forPurchaseOrder(long purchaseOrderId);

    /**
     * Strikes one line from the book; the stock figure itself stays. For
     * cleaning up a mistaken entry, not for changing history.
     *
     * @return whether the line existed on that product
     */
    boolean delete(long productId, long movementId);

    /** For pure unit tests: nothing is kept. */
    StockLedger NONE = new StockLedger() {
        @Override public void record(StockMovement movement) {}
        @Override public List<StockMovement> forProduct(long productId) { return List.of(); }
        @Override public List<StockMovement> forPurchaseOrder(long purchaseOrderId) { return List.of(); }
        @Override public boolean delete(long productId, long movementId) { return false; }
    };
}
