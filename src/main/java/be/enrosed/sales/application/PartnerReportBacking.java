package be.enrosed.sales.application;

import be.enrosed.sales.domain.SalesOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Sales documents made from partner meldingen (Veilingverkopen): their pieces already left stock
 * through the meldingen' own stock lines, so the document books none, is frozen, and stays out of
 * the shipment and closing lists. A document is report-backed when the portal's append-only
 * {@code partner_sale_invoice_part} has rows for it (that survives trash and re-invoicing).
 *
 * <p>Implemented in be.enrosed.partnerportal so sales does not depend on the portal; injected as
 * {@code Instance}, and an unresolvable instance means nothing is report-backed.
 */
public interface PartnerReportBacking {

    /** The ids among these documents that are report-backed. */
    Set<Long> reportBacked(Collection<Long> salesOrderIds);

    /**
     * Per report-backed document among these: per product on its lines, the sales units its
     * meldingen booked out of stock (sale minus buyback parts, between 0 and the line quantity).
     * A report-backed document without product lines maps to an empty map; others are absent.
     */
    Map<Long, Map<Long, Integer>> bookedQuantities(Collection<SalesOrder> documents);

    /** When the newest stock line of the parts on this document was booked; null when there is none. */
    Instant lastMovementAt(long salesOrderId);

    /**
     * Per product of this container: what open meldingen still claim (sale minus buyback quantity,
     * and their net), which a manual settlement may not take. Products without open parts are absent.
     */
    Map<Long, OpenReported> openReported(long purchaseOrderId);

    record OpenReported(int quantity, BigDecimal netEur) {}
}
