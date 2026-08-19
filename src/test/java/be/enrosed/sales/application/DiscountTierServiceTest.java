package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscountTierServiceTest {

    @Test
    void zeroThresholdIsAValidBaselineTier() {
        InMemoryTiers repository = new InMemoryTiers();
        DiscountTierService service = new DiscountTierService(repository);

        List<DiscountTier> saved = service.replace(TierScope.LINE, List.of(
                new DiscountTier(null, TierScope.LINE, 0, BigDecimal.ZERO),
                new DiscountTier(null, TierScope.LINE, 250, new BigDecimal("2"))));

        assertEquals(List.of(0, 250), saved.stream().map(DiscountTier::minQuantity).toList());
    }

    @Test
    void negativeThresholdAndOutOfRangeDiscountAreRejected() {
        DiscountTierService service = new DiscountTierService(new InMemoryTiers());

        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, -1, BigDecimal.ZERO))));
        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, 1, new BigDecimal("101")))));
    }

    private static final class InMemoryTiers implements SalesRepositories.Tiers {
        private List<DiscountTier> stored = List.of();

        @Override
        public List<DiscountTier> findByScope(TierScope scope) {
            return stored.stream().filter(tier -> tier.scope() == scope).toList();
        }

        @Override
        public void replaceScope(TierScope scope, List<DiscountTier> tiers) {
            stored = new ArrayList<>(tiers);
        }
    }
}
