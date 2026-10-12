package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostPayment;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Where the payments on the company costs are kept. */
public interface CostPayments {
    /** The payments of one cost, oldest first (date, then id). */
    List<CostPayment> findByCost(long costId);
    /** The payments of many costs in one query, keyed by cost; a cost without payments is absent. */
    Map<Long, List<CostPayment>> findByCosts(Collection<Long> costIds);
    Optional<CostPayment> findById(long id);
    CostPayment save(CostPayment payment);
    void deleteById(long id);
    /** Every payment of a cost; how many went. */
    int deleteByCost(long costId);
}
