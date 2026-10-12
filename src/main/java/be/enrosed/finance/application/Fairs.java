package be.enrosed.finance.application;

import be.enrosed.finance.domain.Fair;

import java.util.List;
import java.util.Optional;

/** Where the fairs are kept. */
public interface Fairs {
    /** Every fair, active or not, by label. */
    List<Fair> findAll();
    Optional<Fair> findById(long id);
    Optional<Fair> findByCode(String code);
    Fair save(Fair fair);
    void deleteById(long id);
    /** Whether a company cost or a recurring cost still carries the code as its channel. */
    boolean inUse(String code);
}
