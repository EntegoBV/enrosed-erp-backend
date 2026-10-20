package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** Where the list "Aan wie" is kept. */
public interface CostParties {
    /** Active parties, by name regardless of case; merged names remain available as aliases. */
    List<CostParty> findAll();
    /** By the matching key, see {@link CostParty#keyOf(String)}. */
    Optional<CostParty> findByKey(String key);
    Optional<CostParty> findById(long id);
    CostParty save(CostParty party);
    void deleteById(long id);
    /** Locks in stable ID order, shared by edits, deletion, merge and restore. */
    void lock(long... ids);
    Long mergedInto(long id);
    boolean isMergedKey(String key);
    List<Alias> aliases(long targetId);
    void setMergedInto(long sourceId, Long targetId, Instant at, String actor);

    record Alias(CostParty party, long targetId, Instant mergedAt, String mergedBy) {}
    /**
     * Renames the party on every cost, recurring cost and bank line whose
     * stored spelling has the old name's key (see {@link CostParty#keyOf(String)}),
     * so 'Immo  Ham' follows 'Immo Ham'; says how many rows of each changed.
     */
    Renamed renameEverywhere(String oldName, String newName);

    /** How many rows of each kind a rename touched. */
    record Renamed(int costs, int recurringCosts, int bankLines) {
        public static final Renamed NONE = new Renamed(0, 0, 0);
    }
}
