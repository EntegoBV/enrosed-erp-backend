package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.CostPayment;
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
 * One payment on a company cost. No foreign key: the service deletes the
 * payments before the cost, so dev H2 and PostgreSQL behave the same; the
 * indexes on the cost and the bank line come with the migration.
 */
@Entity
@Table(name = "cost_payment")
public class CostPaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "company_cost_id", nullable = false) public Long companyCostId;
    @Column(name = "paid_on", nullable = false) public LocalDate paidOn;
    @Column(name = "amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountEur;
    @Column(name = "account", length = 120) public String account;
    /** The bank line that carried this payment; the bank service sets and clears it. */
    @Column(name = "bank_statement_line_id") public Long bankStatementLineId;
    @Column(name = "note", length = 300) public String note;
    @Column(name = "recorded_at", nullable = false) public Instant recordedAt;
    @Column(name = "actor", length = 255) public String actor;

    public CostPayment toDomain() {
        return new CostPayment(id, companyCostId, paidOn, amountEur, account, bankStatementLineId, note, recordedAt, actor);
    }

    public void apply(CostPayment payment) {
        companyCostId = payment.companyCostId();
        paidOn = payment.paidOn();
        amountEur = payment.amountEur();
        account = payment.account();
        bankStatementLineId = payment.bankStatementLineId();
        note = payment.note();
        recordedAt = payment.recordedAt();
        actor = payment.actor();
    }
}
