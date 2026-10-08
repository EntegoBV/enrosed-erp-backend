package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockValuationRuleEntity;
import be.enrosed.inventory.domain.ValuationRuleText;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The one valuation rule of the company: FIFO per receipt lot, with the
 * first financial year it applies to.
 *
 * The row is created with the first closing. While no closing is final the
 * rule follows the closings, so a concept started for the wrong year fixes
 * nothing; once a closing is final the row no longer changes. It is also the
 * row two creates of a closing meet on.
 */
@ApplicationScoped
public class StockValuationRuleService {

    private final InventoryStore.Rules rules;
    private final InventoryStore.Closings closings;
    private final CurrentActor actor;
    private final EntityManager entities;

    public StockValuationRuleService(InventoryStore.Rules rules, InventoryStore.Closings closings, CurrentActor actor,
                                     EntityManager entities) {
        this.rules = rules;
        this.closings = closings;
        this.actor = actor;
        this.entities = entities;
    }

    /** The rule as readers see it: the row with the lowest id; null while no closing exists. */
    @Transactional
    public StockValuationRuleEntity current() {
        return rules.find("order by id").firstResult();
    }

    /**
     * Takes the rule row for the length of the transaction, so creating a closing and starting a new
     * version run one after another. Null for the very first closing: there is no row to lock yet,
     * and the unique index on year and version is the guard.
     */
    @Transactional
    public StockValuationRuleEntity lockForCreate() {
        StockValuationRuleEntity rule = current();
        if (rule == null) return null;
        entities.flush();
        entities.refresh(rule, LockModeType.PESSIMISTIC_WRITE);
        return rule;
    }

    /**
     * Brings the rule in line with the closings after a create or a concept delete. While none is
     * final: the first year is the lowest closing year and the text is rendered again with it, and
     * without any closing the row goes. With a final closing the row stays as it is.
     */
    @Transactional
    public StockValuationRuleEntity alignWithClosings() {
        List<StockClosingEntity> all = closings.listAll();
        StockValuationRuleEntity rule = current();
        if (all.stream().anyMatch(closing -> StockClosingService.STATUS_FINAL.equals(closing.status))) return rule;
        if (all.isEmpty()) {
            if (rule != null) rules.delete(rule);
            rules.flush();
            return null;
        }
        int firstYear = all.stream().map(closing -> closing.closingYear).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).min().orElseThrow();
        if (rule == null) {
            ActorRef who = actor.current();
            rule = new StockValuationRuleEntity();
            rule.method = ValuationRuleText.METHOD;
            rule.methodLabel = ValuationRuleText.METHOD_LABEL;
            rule.ruleVersion = ValuationRuleText.RULE_VERSION;
            rule.createdBy = who.username();
            rule.createdByName = who.displayName().length() > 120 ? who.displayName().substring(0, 120) : who.displayName();
            rule.createdAt = Instant.now();
            rules.persist(rule);
        }
        rule.effectiveFromYear = firstYear;
        rule.ruleText = ValuationRuleText.render(firstYear);
        rules.flush();
        return rule;
    }
}
