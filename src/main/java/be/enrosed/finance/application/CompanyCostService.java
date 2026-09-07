package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Booking the company's own costs: what was paid, when, to whom and why. */
@ApplicationScoped
public class CompanyCostService {

    public static final String ACTIVITY_ENTITY = "COMPANY_COST";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CompanyCosts costs;

    @Inject
    Instance<ActivityLogService> activity;

    public CompanyCostService(CompanyCosts costs) {
        this.costs = costs;
    }

    public List<CompanyCost> list(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessRuleException("De einddatum ligt voor de begindatum");
        }
        return costs.findBetween(from, to);
    }

    public CompanyCost get(long id) {
        return costs.findById(id).orElseThrow(() -> new NotFoundException("Kost", id));
    }

    @Transactional
    public CompanyCost create(CompanyCost cost) {
        CompanyCost clean = validated(cost, null, Instant.now());
        CompanyCost saved = costs.save(clean);
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Kost geboekt");
        return saved;
    }

    @Transactional
    public CompanyCost update(long id, CompanyCost changes) {
        CompanyCost current = get(id);
        CompanyCost saved = costs.save(validated(changes, current.id(), current.createdAt()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost bijgewerkt");
        return saved;
    }

    @Transactional
    public void delete(long id) {
        CompanyCost cost = get(id);
        costs.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, cost, "Kost verwijderd");
    }

    private static CompanyCost validated(CompanyCost cost, Long id, Instant createdAt) {
        if (cost == null) throw new BusinessRuleException("Geen kostgegevens meegestuurd");
        if (cost.date() == null) throw new BusinessRuleException("Geef de datum van de kost");
        String category = clean(cost.category());
        if (category == null) throw new BusinessRuleException("Kies een categorie");
        String description = clean(cost.description());
        if (description == null) throw new BusinessRuleException("Omschrijf de kost");
        if (cost.amountExclEur() == null || cost.amountExclEur().signum() < 0) {
            throw new BusinessRuleException("Het bedrag kan niet negatief zijn");
        }
        if (cost.vatPct() != null && (cost.vatPct().signum() < 0 || cost.vatPct().compareTo(HUNDRED) > 0)) {
            throw new BusinessRuleException("Het btw-percentage ligt tussen 0 en 100");
        }
        String channel = clean(cost.salesChannel());
        return new CompanyCost(id, cost.date(), category.toUpperCase(), description, clean(cost.party()),
                cost.amountExclEur().setScale(2, java.math.RoundingMode.HALF_UP), cost.vatPct(),
                clean(cost.reference()), cost.paidOn(),
                channel == null ? null : channel.toUpperCase(), clean(cost.notes()),
                createdAt == null ? Instant.now() : createdAt);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordActivity(String action, CompanyCost cost, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                cost.id() == null ? null : cost.id().toString(),
                cost.description() + " · € " + cost.amountExclEur().toPlainString(), summary);
    }
}
