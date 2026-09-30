package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An advance invoice on a regular quote, or the final invoice (slotfactuur)
 * that deducts them: a side table, so sales_order and its purpose stay as
 * they are. The stage is a plain string (ADVANCE or FINAL), never an enum
 * check; the final row freezes the deducted advances as JSON, an issued
 * advance keeps the VAT regime it was issued in.
 */
@Entity
@Table(name = "sales_advance_billing", indexes = @Index(name = "sales_advance_billing_quote_idx", columnList = "quote_id"))
public class SalesAdvanceBillingEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "quote_id", nullable = false) public Long quoteId;
    @Column(name = "stage", nullable = false, length = 16) public String stage;
    @Column(name = "percentage", precision = 9, scale = 4) public BigDecimal percentage;
    @Column(name = "amount_excl_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountExclEur;
    @Column(name = "deductions_json", columnDefinition = "text") public String deductionsJson;
    /** The VAT regime an advance was issued in (a VatTreatment name as plain text), null until it is issued. */
    @Column(name = "vat_treatment", length = 64) public String vatTreatment;
    @Column(name = "vat_rate_pct", precision = 9, scale = 4) public BigDecimal vatRatePct;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
