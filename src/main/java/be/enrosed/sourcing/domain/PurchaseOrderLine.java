package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;

/**
 * Line on a purchase order. Price fields may be empty; the product's own
 * price then applies.
 */
public record PurchaseOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,
        /**
         * The quantity at the moment the order was placed with the supplier.
         *
         * Snapshotted when the status leaves concept. Containers regularly
         * arrive with less than was ordered; from then on the line can say
         * "ordered 96, received 90" instead of silently forgetting what was
         * agreed. Null for lines added after ordering.
         */
        Integer orderedQuantity
) {}
