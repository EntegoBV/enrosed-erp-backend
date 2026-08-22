package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.StockMovement;

import java.util.List;

/** The stock book: append-only, newest first when read. */
public interface StockLedger {

    void record(StockMovement movement);

    List<StockMovement> forProduct(long productId);

    /** For pure unit tests: nothing is kept. */
    StockLedger NONE = new StockLedger() {
        @Override public void record(StockMovement movement) {}
        @Override public List<StockMovement> forProduct(long productId) { return List.of(); }
    };
}
