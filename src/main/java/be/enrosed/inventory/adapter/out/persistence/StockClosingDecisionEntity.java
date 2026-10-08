package be.enrosed.inventory.adapter.out.persistence;

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
 * A user input of a closing: one table for every decision kind, with who decided and when.
 * Together with the opening layers these rows are the only inputs; every other closing table is output.
 */
@Entity
@Table(name = "stock_closing_decision")
public class StockClosingDecisionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "kind", length = 32) public String kind;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "movement_id") public Long movementId;
    @Column(name = "credit_id") public Long creditId;
    @Column(name = "payee", length = 16) public String payee;
    @Column(name = "choice", length = 24) public String choice;
    @Column(name = "flag") public Boolean flag;
    @Column(name = "quantity") public Integer quantity;
    @Column(name = "unit_value_eur", precision = 19, scale = 4) public BigDecimal unitValueEur;
    @Column(name = "amount_eur", precision = 19, scale = 2) public BigDecimal amountEur;
    /** ACCRUAL: the open amount of the stream when the decision was taken; the decision is stale once it differs. */
    @Column(name = "basis_amount_eur", precision = 19, scale = 2) public BigDecimal basisAmountEur;
    @Column(name = "decision_date") public LocalDate decisionDate;
    @Column(name = "reason_code", length = 32) public String reasonCode;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "counterparty", length = 255) public String counterparty;
    @Column(name = "decided_by", length = 64) public String decidedBy;
    @Column(name = "decided_by_name", length = 120) public String decidedByName;
    @Column(name = "decided_at") public Instant decidedAt;
}
