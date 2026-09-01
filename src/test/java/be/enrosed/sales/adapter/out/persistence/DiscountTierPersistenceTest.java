package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DiscountTierPersistenceTest {

    @Inject
    SalesRepositories.Tiers tiers;

    @BeforeEach
    @Transactional
    void clear() {
        tiers.replaceScope(TierScope.LINE, List.of());
        tiers.replaceScope(TierScope.ORDER, List.of());
    }

    @Test
    @Transactional
    void replacingOneProductLeavesEveryOtherLineScheduleIntact() {
        tiers.replaceProduct(TierScope.LINE, 11L, List.of(
                tier(11L, 0, "0"), tier(11L, 100, "4")));
        tiers.replaceProduct(TierScope.LINE, 22L, List.of(
                tier(22L, 50, "3")));

        tiers.replaceProduct(TierScope.LINE, 11L, List.of(
                tier(11L, 200, "7")));

        assertEquals(List.of(200), tiers.findByScopeAndProduct(TierScope.LINE, 11L).stream()
                .map(DiscountTier::minQuantity).toList());
        assertEquals(List.of(50), tiers.findByScopeAndProduct(TierScope.LINE, 22L).stream()
                .map(DiscountTier::minQuantity).toList());
        assertEquals(2, tiers.findByScope(TierScope.LINE).size());
    }

    private static DiscountTier tier(long productId, int minQuantity, String percent) {
        return new DiscountTier(null, TierScope.LINE, minQuantity,
                new BigDecimal(percent), productId);
    }
}
