package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Where the company costs are kept. */
public interface CompanyCosts {
    /** Newest date first; both bounds optional and inclusive. */
    List<CompanyCost> findBetween(LocalDate from, LocalDate to);
    Optional<CompanyCost> findById(long id);
    /** Every cost a recurring definition booked, any date. */
    List<CompanyCost> findByRecurringCost(long recurringCostId);
    CompanyCost save(CompanyCost cost);
    void deleteById(long id);
}
