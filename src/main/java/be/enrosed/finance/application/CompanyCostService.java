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

    @Inject
    Instance<be.enrosed.media.MediaService> media;

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
        /* The link to the recurring definition is the booking job's, not the form's. */
        CompanyCost linked = new CompanyCost(null, changes.date(), changes.category(), changes.description(), changes.party(),
                changes.amountExclEur(), changes.vatPct(), changes.reference(), changes.paidOn(), changes.salesChannel(),
                changes.notes(), null, current.recurringCostId());
        CompanyCost saved = costs.save(validated(linked, current.id(), current.createdAt()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost bijgewerkt");
        return saved;
    }

    /** Settles an open cost on a day: the accountant's invoice was paid, the direct debit went through. */
    @Transactional
    public CompanyCost markPaid(long id, LocalDate paidOn) {
        CompanyCost current = get(id);
        LocalDate day = paidOn == null ? LocalDate.now() : paidOn;
        CompanyCost saved = costs.save(new CompanyCost(current.id(), current.date(), current.category(), current.description(),
                current.party(), current.amountExclEur(), current.vatPct(), current.reference(), day, current.salesChannel(),
                current.notes(), current.createdAt(), current.recurringCostId()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost betaald op " + day);
        return saved;
    }

    @Transactional
    public void delete(long id) {
        CompanyCost cost = get(id);
        /* The invoice stays in the library; only its link to this cost goes. */
        if (media != null && media.isResolvable()) media.get().unlinkTarget(be.enrosed.media.MediaTargetType.COMPANY_COST, id);
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
                createdAt == null ? Instant.now() : createdAt, cost.recurringCostId());
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
