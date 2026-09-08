package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.BankBalance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "bank_balance")
public class BankBalanceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 120) public String account;
    @Column(name = "balance_date", nullable = false) public LocalDate date;
    @Column(name = "balance_eur", nullable = false, precision = 19, scale = 2) public BigDecimal balanceEur;
    @Column(length = 2000) public String notes;
    @Column(name = "created_at") public Instant createdAt;

    public BankBalance toDomain() {
        return new BankBalance(id, account, date, balanceEur, notes, createdAt);
    }

    public void apply(BankBalance balance) {
        account = balance.account();
        date = balance.date();
        balanceEur = balance.balanceEur();
        notes = balance.notes();
        createdAt = balance.createdAt();
    }
}
