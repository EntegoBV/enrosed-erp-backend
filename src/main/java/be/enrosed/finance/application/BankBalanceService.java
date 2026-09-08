package be.enrosed.finance.application;

import be.enrosed.finance.domain.BankBalance;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/** The bank readings: what each account held on a day. */
@ApplicationScoped
public class BankBalanceService {

    public static final String ACTIVITY_ENTITY = "BANK_BALANCE";

    private final BankBalances balances;

    @Inject
    Instance<ActivityLogService> activity;

    public BankBalanceService(BankBalances balances) {
        this.balances = balances;
    }

    public List<BankBalance> list() {
        return balances.findAll();
    }

    public BankBalance get(long id) {
        return balances.findById(id).orElseThrow(() -> new NotFoundException("Banksaldo", id));
    }

    @Transactional
    public BankBalance create(BankBalance balance) {
        BankBalance saved = balances.save(validated(balance, null, Instant.now()));
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Banksaldo ingegeven");
        return saved;
    }

    @Transactional
    public BankBalance update(long id, BankBalance changes) {
        BankBalance current = get(id);
        BankBalance saved = balances.save(validated(changes, current.id(), current.createdAt()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Banksaldo bijgewerkt");
        return saved;
    }

    @Transactional
    public void delete(long id) {
        BankBalance balance = get(id);
        balances.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, balance, "Banksaldo verwijderd");
    }

    private static BankBalance validated(BankBalance balance, Long id, Instant createdAt) {
        if (balance == null) throw new BusinessRuleException("Geen saldo meegestuurd");
        String account = clean(balance.account());
        if (account == null) throw new BusinessRuleException("Geef de rekening een naam");
        if (balance.date() == null) throw new BusinessRuleException("Geef de datum van het saldo");
        if (balance.balanceEur() == null) throw new BusinessRuleException("Geef het saldo");
        String timeZone = clean(balance.timeZone());
        if (balance.asOfAt() != null) {
            if (timeZone == null) timeZone = "Europe/Brussels";
            if (timeZone.length() > 64) throw new BusinessRuleException("Ongeldige tijdzone voor het banksaldo");
            try {
                if (!balance.asOfAt().atZone(ZoneId.of(timeZone)).toLocalDate().equals(balance.date())) {
                    throw new BusinessRuleException("Datum en tijdstip van het banksaldo komen niet overeen");
                }
            } catch (DateTimeException invalidZone) {
                throw new BusinessRuleException("Ongeldige tijdzone voor het banksaldo");
            }
        } else {
            timeZone = null;
        }
        return new BankBalance(id, account, balance.date(), balance.balanceEur().setScale(2, RoundingMode.HALF_UP),
                clean(balance.notes()), createdAt == null ? Instant.now() : createdAt, balance.asOfAt(), timeZone);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordActivity(String action, BankBalance balance, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                balance.id() == null ? null : balance.id().toString(),
                balance.account() + " · " + balance.date() + " · € " + balance.balanceEur().toPlainString(), summary);
    }
}
