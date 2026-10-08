package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.StockMovement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** Every line booked in a stretch of time, oldest first: what the year-end inventory rolls a count with. */
    default List<StockMovement> between(Instant fromInclusive, Instant toExclusive) {
        return List.of();
    }

    /** The last line of one product at one location before a moment: the figure a later line started from. */
    default Optional<StockMovement> lastBefore(long productId, long locationId, Instant before) {
        return Optional.empty();
    }

    /** For pure unit tests: nothing is kept. */
    StockLedger NONE = new StockLedger() {
        @Override public void record(StockMovement movement) {}
        @Override public List<StockMovement> forProduct(long productId) { return List.of(); }
        @Override public List<StockMovement> forPurchaseOrder(long purchaseOrderId) { return List.of(); }
        @Override public boolean delete(long productId, long movementId) { return false; }
    };
}
