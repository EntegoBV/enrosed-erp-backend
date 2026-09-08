package be.enrosed.finance.banking;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="bank_statement_line", uniqueConstraints=@UniqueConstraint(name="bank_statement_account_fingerprint", columnNames={"account", "fingerprint"}))
public class BankStatementLineEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false, length=120) public String account;
    @Column(nullable=false, length=64) public String fingerprint;
    @Column(name="amount_eur", nullable=false, precision=19, scale=2) public BigDecimal amountEur;
    @Column(name="booked_at", nullable=false) public Instant bookedAt;
    @Column(name="time_zone", nullable=false, length=64) public String timeZone;
    @Column(length=500) public String reference;
    @Column(length=300) public String counterparty;
    @Column(name="recorded_at", nullable=false) public Instant recordedAt;
    @Column(length=255) public String actor;
    @Column(name="sales_payment_id", unique=true) public Long salesPaymentId;
    @Column(name="sales_order_id") public Long salesOrderId;
    @Column(name="allocated_at") public Instant allocatedAt;
    @Column(name="allocation_created_payment") public boolean allocationCreatedPayment;
}
