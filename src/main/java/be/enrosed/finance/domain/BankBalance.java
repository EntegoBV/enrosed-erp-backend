package be.enrosed.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What an account held on a day, typed over from the bank: the current
 * account, the savings account, PayPal. A row per reading; the newest
 * reading per account is the balance, the readings together the trend.
 */
public record BankBalance(
        Long id,
        String account,
        LocalDate date,
        BigDecimal balanceEur,
        String notes,
        Instant createdAt,
        Instant asOfAt,
        String timeZone
) {
    /** Legacy daily readings represent the end of their recorded day. */
    public BankBalance(Long id, String account, LocalDate date, BigDecimal balanceEur, String notes, Instant createdAt) {
        this(id, account, date, balanceEur, notes, createdAt, null, null);
    }
}
