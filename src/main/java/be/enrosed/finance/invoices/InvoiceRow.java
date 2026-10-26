package be.enrosed.finance.invoices;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One document in the Facturen finder, whatever its source: a container invoice or invoice file, a company cost, one of
 * our sales documents, or an Odoo move. Amounts are magnitudes with two decimals; {@code kind} says whether it is a credit.
 * Read-only: building a row never writes anything.
 */
public record InvoiceRow(
        /** "pi:12", "pd:245", "cost:7", "odoo:900001", "so:9". */
        String key,
        /** CONTAINER_INVOICE | CONTAINER_FILE | COST | ODOO | SALES. */
        String source,
        /** IN | OUT. */
        String direction,
        /** INVOICE | CREDIT_NOTE | PROFORMA. */
        String kind,
        String docLabel,
        String number,
        /** The invoice date as the document gives it (preview, download name). */
        LocalDate date,
        /** What the list sorts, pages, filters and groups by; for Odoo its accounting date. */
        LocalDate sortDate,
        /** INVOICE | UPLOADED | RECORDED. */
        String dateKind,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        BigDecimal amountEur,
        BigDecimal paidEur,
        BigDecimal openEur,
        Party party,
        /** SUPPLIER | LOGISTICS | SEPARATE | OTHER for container rows, else null. */
        String payee,
        String description,
        String category,
        /** STANDARD | PARTNER_ADVANCE | PARTNER_SETTLEMENT for sales rows, else null. */
        String salesPurpose,
        /** PAID | PARTIAL | OPEN | OVERDUE | SETTLED | NO_DATA | NEW | HANDLED | CONTAINER | TICA | CANCELLED. */
        String status,
        /** COST_REMOVED (with NEW: its cost was deleted in ENROSED) | BEFORE_START | null. */
        String statusReason,
        /** UPLOAD | MANUAL | ODOO | ENROSED. */
        String origin,
        String peppolState,
        Long odooMoveId,
        /** Odoo rows only. */
        OdooFacts odoo,
        ContainerRef container,
        List<Link> links,
        List<FileRef> files,
        boolean missingFile
) {
    public static final String CONTAINER_INVOICE = "CONTAINER_INVOICE";
    public static final String CONTAINER_FILE = "CONTAINER_FILE";
    public static final String COST = "COST";
    public static final String ODOO = "ODOO";
    public static final String SALES = "SALES";

    /** {@code type}: SUPPLIER | PAYEE | COST_PARTY | ODOO_PARTNER | CUSTOMER; {@code id} null when unknown. */
    public record Party(String name, String type, Long id) {}

    public record ContainerRef(long id, String number, String alias) {
        /** Alias when filled, else number, else "Inkoop #id" (the shared container name rule). */
        public String label() {
            if (alias != null && !alias.isBlank()) return alias.strip();
            if (number != null && !number.isBlank()) return number.strip();
            return "Inkoop #" + id;
        }
    }

    /** {@code type}: CONTAINER | TRANSACTION | TICA. */
    public record Link(String type, long id, String label, LocalDate date, String account) {}

    /** {@code type}: PURCHASE_DOCUMENT | MEDIA_ASSET | ODOO_ATTACHMENT | SALES_PDF; {@code kind}: PDF | XML | IMAGE | FILE. */
    public record FileRef(String type, long id, Long orderId, Long moveId, String name, String contentType, String kind,
                          Long sizeBytes, boolean archived) {}

    public record OdooFacts(long moveId, String name, String moveType, String state, String paymentState,
                            boolean registrable,
                            /** OUTGOING | CANCELLED | NO_DATE | ZERO | NOT_EUR | TICA | MARKED | DEMO | null. */
                            String blockedBy,
                            MarkView mark) {}

    public record MarkView(String resolution, Long purchaseOrderId, String note, Instant markedAt, String markedBy) {}

    /** The numeric part of the key: the id the list orders ties by. */
    public long id() {
        return idOf(key);
    }

    static long idOf(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon < 0) return 0;
        try {
            return Long.parseLong(key.substring(colon + 1));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    /** Fixed source rank for ties on the same date: IN containers, files, costs, Odoo; OUT sales, Odoo. */
    public int rank() {
        return rankOf(source, direction);
    }

    static int rankOf(String source, String direction) {
        if (source == null) return 9;
        return switch (source) {
            case CONTAINER_INVOICE -> 1;
            case CONTAINER_FILE -> 2;
            case COST -> 3;
            case SALES -> 1;
            case ODOO -> 4;
            default -> 9;
        };
    }
}
