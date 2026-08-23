package be.enrosed.catalog.domain;

import java.time.Instant;

/**
 * One line in the stock book: what changed, to what, why and by whom.
 *
 * Stock used to be a bare number that only grew on receipt; the moment a
 * count can be corrected by hand, the question "where does this figure
 * come from" needs an answer.
 */
public record StockMovement(
        Long id,
        long productId,
        /** Where it happened; null only on lines booked before locations existed. */
        Long locationId,
        Instant at,
        /** Pieces added (positive) or removed (negative). */
        int delta,
        int quantityAfter,
        Kind kind,
        /** The purchase order number, or empty for a manual count. */
        String reference,
        /** Who did it: the signed-in user, or "systeem". */
        String actor
) {
    public enum Kind {
        PURCHASE_RECEIPT, MANUAL_CORRECTION, TRANSFER_OUT, TRANSFER_IN, STOCKTAKE, SALE;

        public String dutchLabel() {
            return switch (this) {
                case PURCHASE_RECEIPT -> "Inkooporder ontvangen";
                case MANUAL_CORRECTION -> "Manueel gezet";
                case TRANSFER_OUT -> "Verplaatst naar";
                case TRANSFER_IN -> "Ontvangen uit";
                case STOCKTAKE -> "Telling";
                case SALE -> "Verkocht";
            };
        }
    }

    /** Compatibility for lines written before locations existed. */
    public StockMovement(Long id, long productId, Instant at, int delta, int quantityAfter,
                         Kind kind, String reference, String actor) {
        this(id, productId, null, at, delta, quantityAfter, kind, reference, actor);
    }
}
