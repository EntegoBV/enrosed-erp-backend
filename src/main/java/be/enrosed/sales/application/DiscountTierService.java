package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;

/** Manages the discount tiers; the two stack during pricing. */
@ApplicationScoped
public class DiscountTierService {

    private final SalesRepositories.Tiers tiers;

    public DiscountTierService(SalesRepositories.Tiers tiers) {
        this.tiers = tiers;
    }

    public List<DiscountTier> list(TierScope scope) {
        return tiers.findByScope(scope).stream()
                .sorted(Comparator.comparingInt(DiscountTier::minQuantity))
                .toList();
    }

    @Transactional
    public List<DiscountTier> replace(TierScope scope, List<DiscountTier> replacement) {
        if (scope == null || replacement == null) {
            throw new BusinessRuleException("Kortingsschaal en regels zijn verplicht");
        }
        Set<Integer> thresholds = new HashSet<>();
        for (DiscountTier tier : replacement) {
            if (tier == null || tier.minQuantity() < 0) {
                throw new BusinessRuleException("Een kortingsdrempel kan niet negatief zijn");
            }
            if (!thresholds.add(tier.minQuantity())) {
                throw new BusinessRuleException(
                        "Er staat meer dan één korting op drempel " + tier.minQuantity());
            }
            BigDecimal percent = tier.percent();
            if (percent == null || percent.signum() < 0
                    || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessRuleException("Korting moet tussen 0 en 100% liggen");
            }
        }
        tiers.replaceScope(scope, replacement);
        return list(scope);
    }
}
