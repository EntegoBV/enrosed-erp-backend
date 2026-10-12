package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;

import java.util.List;
import java.util.Optional;

/** Where the list "Aan wie" is kept. */
public interface CostParties {
    /** Every party, by name regardless of case. */
    List<CostParty> findAll();
    /** By the matching key, see {@link CostParty#keyOf(String)}. */
    Optional<CostParty> findByKey(String key);
    Optional<CostParty> findById(long id);
    CostParty save(CostParty party);
    void deleteById(long id);
    /**
     * Renames the party on every cost, recurring cost and bank line that
     * carries the old name, regardless of case; returns how many rows changed.
     */
    int renameEverywhere(String oldName, String newName);
}
