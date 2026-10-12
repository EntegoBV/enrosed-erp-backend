package be.enrosed.finance.application;

import be.enrosed.finance.domain.Fair;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The fairs behind the cost category "Beurs & stand": each one a sales
 * channel code that the costs and the sales share. A fair gets its code
 * from its name once and keeps it; one that is over is switched off.
 */
@ApplicationScoped
public class FairService {

    public static final String ACTIVITY_ENTITY = "FAIR";
    private static final int LABEL_LENGTH = 120;

    private final Fairs fairs;
    private final CompanyCosts costs;
    private final RecurringCosts recurringCosts;
    private final SalesRepositories.Orders orders;

    @Inject
    Instance<ActivityLogService> activity;

    public FairService(Fairs fairs, CompanyCosts costs, RecurringCosts recurringCosts, SalesRepositories.Orders orders) {
        this.fairs = fairs;
        this.costs = costs;
        this.recurringCosts = recurringCosts;
        this.orders = orders;
    }

    public List<Fair> list() {
        return fairs.findAll();
    }

    public Fair get(long id) {
        return fairs.findById(id).orElseThrow(() -> new NotFoundException("Beurs", id));
    }

    /** A new fair; the code follows from the label unless one is given. */
    @Transactional
    public Fair create(String label, String code) {
        String name = cleanLabel(label);
        String key = Fair.codeOf(code == null || code.isBlank() ? name : code);
        if (key == null) throw new BusinessRuleException("Geef de beurs een naam met letters of cijfers");
        if (Fair.FIXED_CHANNELS.contains(key)) throw new BusinessRuleException("Deze code is een vast verkoopkanaal");
        if (fairs.findByCode(key).isPresent()) throw new BusinessRuleException("Deze beurs bestaat al");
        Fair saved = fairs.save(new Fair(null, key, name, true, Instant.now()));
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Beurs toegevoegd");
        return saved;
    }

    /** The label and the switch; the code never changes, the costs carry it. */
    @Transactional
    public Fair update(long id, String label, Boolean active) {
        Fair current = get(id);
        Fair saved = fairs.save(new Fair(current.id(), current.code(), cleanLabel(label),
                active == null ? current.active() : active, current.createdAt()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, saved.active() ? "Beurs bijgewerkt" : "Beurs bijgewerkt · uit");
        return saved;
    }

    /** Only a code nothing carries any more: no cost, no recurring cost, no sales document from the stand. */
    @Transactional
    public void delete(long id) {
        Fair fair = get(id);
        long carried = costs.countBySalesChannel(fair.code()) + recurringCosts.countBySalesChannel(fair.code())
                + orders.countBySalesChannel(fair.code());
        if (carried > 0) {
            throw new BusinessRuleException("Deze beurs wordt nog gebruikt; zet ze uit in plaats van ze te verwijderen.");
        }
        fairs.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, fair, "Beurs verwijderd");
    }

    private static String cleanLabel(String label) {
        String name = label == null ? "" : label.strip();
        if (name.isEmpty()) throw new BusinessRuleException("Geef de beurs een naam");
        if (name.length() > LABEL_LENGTH) throw new BusinessRuleException("De naam is maximaal 120 tekens");
        return name;
    }

    private void recordActivity(String action, Fair fair, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY, fair.id() == null ? null : fair.id().toString(),
                fair.label() + " · " + fair.code(), summary);
    }
}
