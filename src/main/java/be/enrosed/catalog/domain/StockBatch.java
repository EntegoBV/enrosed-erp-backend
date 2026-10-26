package be.enrosed.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A batch as the services pass it around: pieces that entered stock together with one cost. Quantities are physical
 * pieces; the cost is per selling unit of the batch ({@code unitPieces} pieces, frozen when the batch was born), so a
 * batch shows exactly the figure the product card shows.
 *
 * <p>{@code soldPieces}, {@code lostPieces}, {@code earlierPieces} and {@code otherPieces} are the screens' buckets of
 * what left the batch (a return subtracts from the bucket of the take it restores).
 */
public record StockBatch(Long id, long productId, Kind kind, Owner owner, Long purchaseOrderId, Long purchaseOrderLineId,
        String orderNumber, Long supplierId, Long productSupplierId, LocalDate receivedOn, Instant bookedAt,
        int unitPieces, int piecesIn, int piecesLeft, CostState costState, BigDecimal unitCostEur,
        BigDecimal unitCostExclEnrosedEur, ProductCostBreakdown parts, Instant costAppliedAt, String costAppliedBy,
        String note, int soldPieces, int lostPieces, int earlierPieces, int otherPieces) {

    /** RECEIPT: one booked purchase-order line · OPENING: "Beginvoorraad" · COUNT: "Telling". */
    public enum Kind { RECEIPT, OPENING, COUNT }

    /** PARTNER when the container is a partner container; partner pieces are not ours. */
    public enum Owner { OWN, PARTNER }

    /** Toegepast · Berekend (the container's calculation) · Van de productkaart · Kost onbekend. */
    public enum CostState { APPLIED, CALCULATED, CARD, NONE }

    /** piecesLeft × unitCostEur / unitPieces, 2 decimals HALF_UP; null without a cost. */
    public BigDecimal valueLeftEur() {
        if (unitCostEur == null) return null;
        return unitCostEur.multiply(BigDecimal.valueOf(piecesLeft))
                .divide(BigDecimal.valueOf(Math.max(1, unitPieces)), 2, RoundingMode.HALF_UP);
    }
}
