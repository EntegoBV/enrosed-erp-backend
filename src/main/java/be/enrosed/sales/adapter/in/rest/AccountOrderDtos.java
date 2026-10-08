package be.enrosed.sales.adapter.in.rest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire contract of a logged-in customer's orders and documents. Every record is an
 * explicit allow-list: nothing of the staff view, the priced document or the customer
 * record is ever serialised as it is, so no internal note, portal link, cost, margin or
 * payment state can leave by accident.
 */
public final class AccountOrderDtos {

    private AccountOrderDtos() {}

    /** Prefill of the delivery step; {@code source} is LAST_ORDER, CUSTOMER_RECORD or NONE. */
    public record DeliveryDefaults(
            String source,
            String fulfillment,
            Long pickupLocationId,
            PublicQuoteDtos.Destination destination,
            String contactName,
            String phone
    ) {}

    /** One row under "my orders"; {@code kind} is ORDER, QUOTE, INVOICE or CREDIT_NOTE. */
    public record DocumentRow(
            Long id,
            String kind,
            String number,
            LocalDate date,
            String status,
            /** CUSTOMER or ENROSED for a cancelled order or quotation, else null. */
            String cancelledBy,
            /** Both totals are null while the amount is still to be confirmed or cannot be shown. */
            BigDecimal totalExclVat,
            BigDecimal totalInclVat,
            String currency,
            LocalDate validUntil,
            boolean canChange,
            boolean canCancel,
            boolean hasDetail,
            boolean hasPdf,
            String relatedNumber
    ) {}

    public record DocumentPage(List<DocumentRow> items, Long nextCursor) {}

    /** An order or quotation in full; {@code basis} is AS_ORDERED or CURRENT. */
    public record OrderDetail(
            Long id,
            String kind,
            String number,
            LocalDate date,
            String status,
            String cancelledBy,
            String cancellationMessage,
            /** Null for a quotation. */
            Integer revision,
            boolean canChange,
            boolean canCancel,
            boolean hasPdf,
            String basis,
            LocalDate basisDate,
            LocalDate validUntil,
            String fulfillment,
            PickupView pickupLocation,
            PublicQuoteDtos.Destination destination,
            String contactName,
            String phone,
            /** The customer's own remark, never a text Enrosed wrote. */
            String notes,
            List<DetailLine> lines,
            List<DetailExtraLine> extraLines,
            DetailTotals totals,
            String relatedNumber
    ) {}

    public record DetailLine(
            Long productId,
            String sku,
            String description,
            Integer cartons,
            Integer piecesPerCarton,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal discountPct,
            BigDecimal net
    ) {}

    public record DetailExtraLine(String description, BigDecimal net) {}

    /** {@code shippingStatus} is PICKUP, CALCULATED or TO_CONFIRM. */
    public record DetailTotals(
            BigDecimal goods,
            BigDecimal extras,
            BigDecimal shipping,
            String shippingStatus,
            BigDecimal totalExclVat,
            BigDecimal vatRatePct,
            BigDecimal vatAmount,
            BigDecimal totalInclVat,
            String vatTreatment
    ) {}

    public record PickupView(Long id, String label, String address) {}

    /** {@code orderId} is set while an existing order is being changed: its products keep their stored price. */
    public record OrderPreviewRequest(
            String language,
            String fulfillment,
            Long pickupLocationId,
            PublicQuoteDtos.Destination destination,
            List<PublicQuoteDtos.ItemRequest> items,
            Long orderId
    ) {}

    /** Company, VAT number and e-mail are the session's and have no field here. */
    public record OrderRequest(
            String language,
            String fulfillment,
            Long pickupLocationId,
            PublicQuoteDtos.Destination destination,
            List<PublicQuoteDtos.ItemRequest> items,
            String contactName,
            String phone,
            String notes,
            Boolean privacyAccepted,
            /** Honeypot. Real clients leave this field empty. */
            String website,
            String formToken,
            String challengeToken,
            /** Ignored when placing; required when changing. */
            Integer baseRevision
    ) {}

    public record CancelRequest(Integer baseRevision) {}

    /** All that is answered to a write and kept for its replay; the page fetches the detail itself. */
    public record OrderReceipt(Long id, String number, Integer revision, String status) {}
}
