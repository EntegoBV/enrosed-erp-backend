package be.enrosed.finance.application;

import be.enrosed.finance.domain.BankBalance;

import java.util.List;
import java.util.Optional;

/** Where the bank readings are kept. */
public interface BankBalances {
    /** Newest date first. */
    List<BankBalance> findAll();
    Optional<BankBalance> findById(long id);
    BankBalance save(BankBalance balance);
    void deleteById(long id);
}
