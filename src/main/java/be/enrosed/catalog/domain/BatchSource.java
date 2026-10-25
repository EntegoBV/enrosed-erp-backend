package be.enrosed.catalog.domain;

import java.time.LocalDate;

/**
 * Where receipt pieces came from: a container line, frozen on the batch. {@code productSupplierId} is the line's
 * {@code purchase_order_line.product_supplier_id} (the supplier version it was ordered from).
 */
public record BatchSource(long purchaseOrderId, long purchaseOrderLineId, String orderNumber, Long supplierId,
        Long productSupplierId, boolean partner, LocalDate receivedOn, BatchCost cost) {
}
