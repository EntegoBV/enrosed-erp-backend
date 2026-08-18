package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;

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
        tiers.replaceScope(scope, replacement);
        return list(scope);
    }
}
