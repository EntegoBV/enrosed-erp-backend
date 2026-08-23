package be.enrosed.sourcing.domain;

import java.time.Instant;

/**
 * A file that belongs to a container: the payment proof, the commercial
 * invoice, the packing list, the bill of lading. Kept in the same store as
 * the product photos, under the kind that says what it is.
 */
public record PurchaseDocument(
        Long id,
        long orderId,
        Kind kind,
        /** A free word next to the kind: "KBC 23/08", "factuur 2e helft". */
        String label,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String storageKey,
        /** The payment this proof belongs to, when it is one. */
        Long paymentId,
        String actor,
        Instant addedAt
) {
    public enum Kind {
        PAYMENT_PROOF, COMMERCIAL_INVOICE, PACKING_LIST, BILL_OF_LADING, CUSTOMS, OTHER;

        public String dutchLabel() {
            return switch (this) {
                case PAYMENT_PROOF -> "Betalingsbewijs";
                case COMMERCIAL_INVOICE -> "Commercial invoice";
                case PACKING_LIST -> "Packing list";
                case BILL_OF_LADING -> "Bill of lading";
                case CUSTOMS -> "Douanedocument";
                case OTHER -> "Andere";
            };
        }
    }
}
