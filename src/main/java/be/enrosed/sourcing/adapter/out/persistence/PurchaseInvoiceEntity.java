package be.enrosed.sourcing.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One invoice of a container: the supplier's commercial invoice, or an
 * invoice for the inspection, the transport, a courier. Explicit snake_case
 * columns because the PostgreSQL migration creates the table before startup;
 * payee and currency are plain text (no enum check), NOT NULL lives in the
 * migration only (dev H2 adds the table through schema update), and there is
 * no foreign key: the service clears the payments' and documents' references.
 */
@Entity(name = "PurchaseInvoiceEntity")
@Table(name = "purchase_invoice")
public class PurchaseInvoiceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "order_id") public long orderId;
    /** SUPPLIER, LOGISTICS, SEPARATE or OTHER: the payment stream the invoice belongs to. */
    @Column(name = "payee", length = 16) public String payee;
    @Column(name = "number", length = 64) public String number;
    @Column(name = "invoice_date") public LocalDate invoiceDate;
    @Column(name = "amount", precision = 19, scale = 2) public BigDecimal amount;
    /** EUR, USD or CNY. */
    @Column(name = "currency", length = 8) public String currency;
    @Column(name = "amount_eur", precision = 19, scale = 2) public BigDecimal amountEur;
    /** The purchase document holding the file; null while none was uploaded. */
    @Column(name = "document_id") public Long documentId;
    @Column(name = "notes", length = 500) public String notes;
    @Column(name = "actor", length = 255) public String actor;
    @Column(name = "recorded_at") public Instant recordedAt;
}
