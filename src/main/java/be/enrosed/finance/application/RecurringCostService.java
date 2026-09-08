package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.RecurringCost;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The recurring costs: defined once, booked by the job every time they fall
 * due. A definition remembers where it got to, so a restart or a manual run
 * never books a period twice.
 */
@ApplicationScoped
public class RecurringCostService {

    public static final String ACTIVITY_ENTITY = "RECURRING_COST";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int BOOKINGS_PER_RUN = 1_000;

    private final RecurringCosts definitions;
    private final CompanyCosts costs;

    @Inject
    Instance<ActivityLogService> activity;

    public RecurringCostService(RecurringCosts definitions, CompanyCosts costs) {
        this.definitions = definitions;
        this.costs = costs;
    }

    public List<RecurringCost> list() {
        return definitions.findAll();
    }

    public RecurringCost get(long id) {
        return definitions.findById(id).orElseThrow(() -> new NotFoundException("Vaste kost", id));
    }

    @Transactional
    public RecurringCost create(RecurringCost definition) {
        RecurringCost clean = validated(definition, null, Instant.now(), null);
        RecurringCost saved = definitions.save(clean);
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Vaste kost ingesteld · " + saved.interval().label().toLowerCase());
        return saved;
    }

    @Transactional
    public RecurringCost update(long id, RecurringCost changes) {
        RecurringCost current = get(id);
        RecurringCost saved = definitions.save(validated(changes, current.id(), current.createdAt(), current.lastBookedOn()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Vaste kost bijgewerkt");
        return saved;
    }

    @Transactional
    public void delete(long id) {
        RecurringCost definition = get(id);
        definitions.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, definition, "Vaste kost verwijderd; de geboekte kosten blijven staan");
    }

    /**
     * Books every occurrence that fell due up to and including the day, for
     * every active definition, and moves each definition on to its next date.
     * Returns what was booked, newest definition order.
     */
    @Transactional
    public List<CompanyCost> bookDue(LocalDate today) {
        List<CompanyCost> booked = new ArrayList<>();
        for (RecurringCost definition : definitions.findAll()) {
            if (!definition.active() || definition.nextDate() == null || definition.id() == null) continue;
            Set<LocalDate> already = costs.findByRecurringCost(definition.id()).stream()
                    .map(CompanyCost::date).collect(Collectors.toSet());
            LocalDate next = definition.nextDate();
            LocalDate lastBooked = definition.lastBookedOn();
            int guard = 0;
            while (next != null && !next.isAfter(today) && guard++ < BOOKINGS_PER_RUN) {
                if (!already.contains(next)) {
                    CompanyCost cost = costs.save(costFor(definition, next));
                    booked.add(cost);
                    recordBooked(definition, cost);
                }
                lastBooked = next;
                next = definition.occurrenceAfter(next);
            }
            boolean stillActive = next != null;
            if (!Objects.equals(next, definition.nextDate()) || !Objects.equals(lastBooked, definition.lastBookedOn())
                    || stillActive != definition.active()) {
                definitions.save(definition.withProgress(next, lastBooked, stillActive));
            }
        }
        return booked;
    }

    private static CompanyCost costFor(RecurringCost definition, LocalDate date) {
        return new CompanyCost(null, date, definition.category(), definition.name(), definition.party(),
                definition.amountExclEur(), definition.vatPct(), definition.reference(),
                definition.autoPaid() ? date : null, definition.salesChannel(),
                "Automatisch geboekt als vaste kost (" + definition.interval().label().toLowerCase() + ")",
                Instant.now(), definition.id());
    }

    private RecurringCost validated(RecurringCost definition, Long id, Instant createdAt, LocalDate lastBookedOn) {
        if (definition == null) throw new BusinessRuleException("Geen gegevens van de vaste kost meegestuurd");
        String name = clean(definition.name());
        if (name == null) throw new BusinessRuleException("Geef de vaste kost een naam");
        String category = clean(definition.category());
        if (category == null) throw new BusinessRuleException("Kies een categorie");
        if (definition.amountExclEur() == null || definition.amountExclEur().signum() < 0) {
            throw new BusinessRuleException("Het bedrag kan niet negatief zijn");
        }
        if (definition.vatPct() != null && (definition.vatPct().signum() < 0 || definition.vatPct().compareTo(HUNDRED) > 0)) {
            throw new BusinessRuleException("Het btw-percentage ligt tussen 0 en 100");
        }
        if (definition.interval() == null) throw new BusinessRuleException("Kies hoe vaak de kost terugkomt");
        if (definition.startDate() == null) throw new BusinessRuleException("Geef de eerste datum van de vaste kost");
        if (definition.endDate() != null && definition.endDate().isBefore(definition.startDate())) {
            throw new BusinessRuleException("De einddatum ligt voor de eerste datum");
        }
        String channel = clean(definition.salesChannel());
        RecurringCost base = new RecurringCost(id, name, category.toUpperCase(), clean(definition.party()),
                definition.amountExclEur().setScale(2, RoundingMode.HALF_UP), definition.vatPct(),
                channel == null ? null : channel.toUpperCase(), definition.interval(), definition.startDate(), definition.endDate(),
                null, definition.active(), definition.autoPaid(), clean(definition.reference()), clean(definition.notes()),
                lastBookedOn, createdAt == null ? Instant.now() : createdAt);
        /* Where the schedule stands follows from the rhythm and what was booked, never from the form. */
        LocalDate next = base.firstOccurrenceFrom(lastBookedOn == null ? base.startDate() : lastBookedOn.plusDays(1));
        return base.withProgress(next, lastBookedOn, definition.active() && next != null);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordActivity(String action, RecurringCost definition, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                definition.id() == null ? null : definition.id().toString(),
                definition.name() + " · € " + definition.amountExclEur().toPlainString(), summary);
    }

    private void recordBooked(RecurringCost definition, CompanyCost cost) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(ActivityLogService.ACTION_CREATED, CompanyCostService.ACTIVITY_ENTITY,
                cost.id() == null ? null : cost.id().toString(),
                cost.description() + " · € " + cost.amountExclEur().toPlainString(),
                "Vaste kost automatisch geboekt voor " + cost.date() + " (" + definition.interval().label().toLowerCase() + ")");
    }
}
