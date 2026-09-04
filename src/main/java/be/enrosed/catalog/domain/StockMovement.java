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
        String actor,
        /**
         * The container the pieces came on, when damage or a shortage is
         * reported after receipt; the order's dossier and the next supplier
         * order read it back. Null for every other line.
         */
        Long purchaseOrderId
) {
    public enum Kind {
        PURCHASE_RECEIPT, MANUAL_CORRECTION, TRANSFER_OUT, TRANSFER_IN, STOCKTAKE, SALE, DAMAGED, DEMO, SHORTAGE;

        public String dutchLabel() {
            return switch (this) {
                case PURCHASE_RECEIPT -> "Inkooporder ontvangen";
                case MANUAL_CORRECTION -> "Manueel gezet";
                case TRANSFER_OUT -> "Verplaatst naar";
                case TRANSFER_IN -> "Ontvangen uit";
                case STOCKTAKE -> "Telling";
                case SALE -> "Verkocht";
                case DAMAGED -> "Beschadigd";
                case DEMO -> "Demo weggegeven";
                case SHORTAGE -> "Te weinig geleverd";
            };
        }

        /** Whether this kind is a complaint the supplier may hear about. */
        public boolean isReceiptIssue() {
            return this == DAMAGED || this == SHORTAGE;
        }
    }

    /** Compatibility for lines written before a report could name its container. */
    public StockMovement(Long id, long productId, Long locationId, Instant at, int delta, int quantityAfter,
                         Kind kind, String reference, String actor) {
        this(id, productId, locationId, at, delta, quantityAfter, kind, reference, actor, null);
    }

    /** Compatibility for lines written before locations existed. */
    public StockMovement(Long id, long productId, Instant at, int delta, int quantityAfter,
                         Kind kind, String reference, String actor) {
        this(id, productId, null, at, delta, quantityAfter, kind, reference, actor, null);
    }
}
