package be.enrosed.finance.application;

import be.enrosed.finance.domain.RecurringCost;

import java.util.List;
import java.util.Optional;

/** Where the recurring cost definitions are kept. */
public interface RecurringCosts {
    /** Active first, then by name. */
    List<RecurringCost> findAll();
    Optional<RecurringCost> findById(long id);
    RecurringCost save(RecurringCost definition);
    void deleteById(long id);
}
