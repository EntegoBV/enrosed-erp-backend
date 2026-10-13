package be.enrosed.finance.banking;

import jakarta.persistence.*;
import java.time.Instant;

/** A durable receipt for one bank-led cost creation, retained even when the local cost is removed later. */
@Entity @Table(name="bank_cost_registration",uniqueConstraints=@UniqueConstraint(name="uq_bank_created_cost",columnNames="company_cost_id"))
public class BankCostRegistrationEntity {
    @Id @Column(name="bank_statement_line_id") public Long bankStatementLineId;
    @Column(name="request_id",nullable=false,length=36) public String requestId;
    @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(name="company_cost_id",nullable=false) public long companyCostId;
    @Column(name="created_at",nullable=false) public Instant createdAt;
}
