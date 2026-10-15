package be.enrosed.sourcing.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What a purchase payment was before it first followed its bank transaction:
 * "aangepast aan de bank (was € 12.480,00 op 13/10)". One row per payment,
 * written once (the first adjustment wins) and removed by a hand edit of the
 * amount or the date, or by deleting the payment. Kept beside the payment so
 * the payment record itself is never saved again for it. Explicit snake_case
 * columns because the PostgreSQL migration creates the table before startup;
 * NOT NULL lives in the migration only and there is no foreign key.
 */
@Entity(name = "PurchasePaymentBankAdjustmentEntity")
@Table(name = "purchase_payment_bank_adjustment")
public class PurchasePaymentBankAdjustmentEntity {
    @Id @Column(name = "payment_id") public Long paymentId;
    @Column(name = "from_amount_eur", precision = 19, scale = 2) public BigDecimal fromAmountEur;
    @Column(name = "from_paid_on") public LocalDate fromPaidOn;
    /** The transaction the payment followed. */
    @Column(name = "bank_statement_line_id") public Long bankStatementLineId;
    @Column(name = "adjusted_at") public Instant adjustedAt;
    @Column(name = "actor", length = 255) public String actor;
}
