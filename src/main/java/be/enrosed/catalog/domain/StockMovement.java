package be.enrosed.catalog.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;

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
         * order read it back. A partner portal line (PARTNER_SALE,
         * PARTNER_BUYBACK) names the partner container of its melding part,
         * null for own stock. Null for every other line.
         */
        Long purchaseOrderId,
        Long ticaSaleId,
        Long ticaRevisionId,
        LocalDateTime sourceSoldAt,
        int looseDelta,
        int loosePiecesAfter,
        Integer unitPieces,
        BigDecimal sourceQuantity,
        String sourceUnit
) {
    public enum Kind {
        PURCHASE_RECEIPT, MANUAL_CORRECTION, TRANSFER_OUT, TRANSFER_IN, STOCKTAKE, SALE, DAMAGED, DEMO, SHORTAGE, SALE_RETURN,
        /** Pieces a partner reported sold through the portal (always negative); also the undo of a buyback. */
        PARTNER_SALE,
        /** Pieces a partner bought back on the clock (always positive); also the undo of a sale. */
        PARTNER_BUYBACK;

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
                case SALE_RETURN -> "Retour van klant";
                case PARTNER_SALE -> "Verkocht via partner";
                case PARTNER_BUYBACK -> "Teruggekocht door partner";
            };
        }

        /**
         * A line of a partner portal melding: it belongs to the melding (undone by withdrawing it,
         * never deleted from the book) and carries the melding's sold_on as sourceSoldAt.
         */
        public boolean isPartnerReport() {
            return this == PARTNER_SALE || this == PARTNER_BUYBACK;
        }

        /** Whether this kind is a complaint the supplier may hear about. */
        public boolean isReceiptIssue() {
            return this == DAMAGED || this == SHORTAGE;
        }
    }

    public StockMovement(Long id, long productId, Long locationId, Instant at, int delta, int quantityAfter,
                         Kind kind, String reference, String actor, Long purchaseOrderId) {
        this(id, productId, locationId, at, delta, quantityAfter, kind, reference, actor, purchaseOrderId,
                null, null, null, 0, 0, null, null, null);
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
