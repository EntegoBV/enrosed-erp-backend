package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;

/**
 * Rekent bedragen om tussen RMB, dollar en euro.
 *
 * RMB gaat altijd via de dollar: zo werkt de handel ook, en zo hoeft er maar
 * een koers bijgehouden te worden per stap.
 */
@ApplicationScoped
public class CurrencyConverter {

    public BigDecimal toUsd(BigDecimal amount, Currency from, BigDecimal cnyToUsd, BigDecimal usdToEur) {
        BigDecimal value = Money.nz(amount);
        return switch (from) {
            case USD -> value;
            case CNY -> value.multiply(Money.nz(cnyToUsd));
            case EUR -> Money.divide(value, usdToEur);
        };
    }

    public BigDecimal toEur(BigDecimal amount, Currency from, BigDecimal cnyToUsd, BigDecimal usdToEur) {
        BigDecimal value = Money.nz(amount);
        return switch (from) {
            case EUR -> value;
            case USD -> value.multiply(Money.nz(usdToEur));
            case CNY -> value.multiply(Money.nz(cnyToUsd)).multiply(Money.nz(usdToEur));
        };
    }
}
