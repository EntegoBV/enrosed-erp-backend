package be.enrosed.catalog.domain;

import java.time.Instant;

/** The start of batches ("Batches opbouwen"); paused when {@code pausedAt} is set. */
public record StockBatchRun(Long id, Instant ranAt, String ranBy, int products, int batches, int openingPieces,
        int unassignedPieces, String note, Instant pausedAt, String pausedBy) {

    public boolean paused() {
        return pausedAt != null;
    }
}
