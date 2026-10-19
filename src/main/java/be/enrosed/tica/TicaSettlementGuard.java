package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

/** Source-owned offsets cannot be edited into ordinary cash payments through another screen. */
@ApplicationScoped
public class TicaSettlementGuard {
    public java.util.Map<Long, Long> costLinks(java.util.Collection<Long> ids) {
        java.util.Map<Long, Long> links = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) return links;
        for (TicaSettlementEntity row : TicaSettlementEntity.<TicaSettlementEntity>list("commissionCostId in ?1", ids))
            links.put(row.commissionCostId, row.id);
        return links;
    }
    public void requireEditableCost(Long costId) {
        if (costId != null && TicaSettlementEntity.count("commissionCostId", costId) > 0)
            throw new BusinessRuleException("Deze commissie is vastgelegd in een TICA-afrekening en is daar verrekend. Pas haar niet als losse kost of bankbetaling aan.");
    }
}
