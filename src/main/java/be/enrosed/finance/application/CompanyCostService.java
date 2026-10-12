package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Booking the company's own costs: what was paid, when, to whom and why. */
@ApplicationScoped
public class CompanyCostService {

    public static final String ACTIVITY_ENTITY = "COMPANY_COST";
    private static final Logger LOG = Logger.getLogger(CompanyCostService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** How far an entered inclusive total may sit from excl. plus the rate: the rounding of either side. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");

    private final CompanyCosts costs;

    @Inject
    Instance<ActivityLogService> activity;

    @Inject
    Instance<be.enrosed.media.MediaService> media;

    /** The list "Aan wie" learns every name a cost is booked to; absent in the unit tests, which build the service by hand. */
    @Inject
    Instance<CostPartyService> parties;

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
        /* The bank link is the bank service's to set, never the form's. */
        CompanyCost clean = validated(cost, null, Instant.now(), null);
        CompanyCost saved = costs.save(clean);
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Kost geboekt");
        learnParty(saved);
        return saved;
    }

    @Transactional
    public CompanyCost update(long id, CompanyCost changes) {
        CompanyCost current = get(id);
        /* The link to the recurring definition is the booking job's, not the form's. */
        CompanyCost linked = new CompanyCost(null, changes.date(), changes.category(), changes.description(), changes.party(),
                changes.amountExclEur(), changes.amountInclEur(), changes.vatPct(), changes.reference(), changes.paidOn(),
                changes.salesChannel(), changes.notes(), null, current.recurringCostId(), null);
        CompanyCost saved = costs.save(validated(linked, current.id(), current.createdAt(), current.bankStatementLineId()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost bijgewerkt");
        learnParty(saved);
        return saved;
    }

    /** Settles an open cost on a day: the accountant's invoice was paid, the direct debit went through. */
    @Transactional
    public CompanyCost markPaid(long id, LocalDate paidOn) {
        CompanyCost current = get(id);
        LocalDate day = paidOn == null ? LocalDate.now() : paidOn;
        CompanyCost saved = costs.save(new CompanyCost(current.id(), current.date(), current.category(), current.description(),
                current.party(), current.amountExclEur(), current.amountInclEur(), current.vatPct(), current.reference(), day,
                current.salesChannel(), current.notes(), current.createdAt(), current.recurringCostId(),
                current.bankStatementLineId()));
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

    /** The bank line is the stored cost's or none: the bank service sets it, a form never does. */
    private static CompanyCost validated(CompanyCost cost, Long id, Instant createdAt, Long bankStatementLineId) {
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
        BigDecimal excl = cost.amountExclEur().setScale(2, RoundingMode.HALF_UP);
        String channel = clean(cost.salesChannel());
        return new CompanyCost(id, cost.date(), category.toUpperCase(), description, clean(cost.party()),
                excl, inclusiveChecked(excl, cost.amountInclEur(), cost.vatPct()), cost.vatPct(),
                clean(cost.reference()), cost.paidOn(),
                channel == null ? null : channel.toUpperCase(), clean(cost.notes()),
                createdAt == null ? Instant.now() : createdAt, cost.recurringCostId(), bankStatementLineId);
    }

    /**
     * The inclusive total as entered, when there is one: two decimals, never
     * below excl., and what it leaves above excl. must be the VAT the rate
     * gives, within the rounding of either side; without a rate it equals excl.
     */
    private static BigDecimal inclusiveChecked(BigDecimal excl, BigDecimal entered, BigDecimal vatPct) {
        if (entered == null) return null;
        BigDecimal incl = entered.setScale(2, RoundingMode.HALF_UP);
        if (incl.compareTo(excl) < 0) throw new BusinessRuleException("Het bedrag incl. btw kan niet kleiner zijn dan excl.");
        BigDecimal vat = incl.subtract(excl);
        boolean fits = vatPct == null || vatPct.signum() == 0
                ? vat.signum() == 0
                : vat.subtract(excl.multiply(vatPct).divide(HUNDRED, 4, RoundingMode.HALF_UP)).abs().compareTo(TOLERANCE) <= 0;
        if (!fits) throw new BusinessRuleException("Het bedrag incl. btw past niet bij het btw-percentage");
        return incl;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The list learns the name after the cost is in, in its own transaction:
     * a failure of the list never undoes the booking. The channel is a
     * default worth keeping only for a fair cost; a rent paid under WEBSITE
     * says nothing about the landlord.
     */
    private void learnParty(CompanyCost cost) {
        if (parties == null || !parties.isResolvable()) return;
        try {
            parties.get().learn(cost.party(), cost.category(), cost.vatPct(),
                    CostPartyService.FAIR_CATEGORY.equals(cost.category()) ? cost.salesChannel() : null);
        } catch (RuntimeException failure) {
            LOG.debug("De lijst Aan wie leerde de kost niet: " + failure.getMessage(), failure);
        }
    }

    private void recordActivity(String action, CompanyCost cost, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                cost.id() == null ? null : cost.id().toString(),
                cost.description() + " · € " + cost.amountExclEur().toPlainString(), summary);
    }
}
