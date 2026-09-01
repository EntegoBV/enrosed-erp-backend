package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
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
import static org.mockito.Mockito.mock;

class DiscountTierServiceTest {

    @Test
    void zeroThresholdIsAValidOrderBaselineTier() {
        InMemoryTiers repository = new InMemoryTiers();
        DiscountTierService service = new DiscountTierService(repository);

        List<DiscountTier> saved = service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, 0, BigDecimal.ZERO),
                new DiscountTier(null, TierScope.ORDER, 250, new BigDecimal("2"))));

        assertEquals(List.of(0, 250), saved.stream().map(DiscountTier::minQuantity).toList());
    }

    @Test
    void lineSchedulesAreReplacedOneProductAtATime() {
        InMemoryTiers repository = new InMemoryTiers();
        DiscountTierService service = serviceWithProducts(repository);

        service.replaceForProduct(11L, List.of(
                new DiscountTier(null, null, 0, BigDecimal.ZERO),
                new DiscountTier(null, null, 100, new BigDecimal("4"))));
        service.replaceForProduct(22L, List.of(
                new DiscountTier(null, TierScope.LINE, 50, new BigDecimal("3"), 22L)));
        List<DiscountTier> replaced = service.replaceForProduct(11L, List.of(
                new DiscountTier(null, TierScope.LINE, 200, new BigDecimal("7"), 11L)));

        assertEquals(List.of(200), replaced.stream().map(DiscountTier::minQuantity).toList());
        assertEquals(List.of(11L), replaced.stream().map(DiscountTier::productId).distinct().toList());
        assertEquals(List.of(50), service.listForProduct(22L).stream()
                .map(DiscountTier::minQuantity).toList());
    }

    @Test
    void lineRequiresAProductAndOrderRejectsOne() {
        DiscountTierService service = serviceWithProducts(new InMemoryTiers());

        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.LINE, List.of(
                new DiscountTier(null, TierScope.LINE, 1, BigDecimal.ONE))));
        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, 1, BigDecimal.ONE, 11L))));
        assertThrows(BusinessRuleException.class, () -> service.replaceForProduct(11L, List.of(
                new DiscountTier(null, TierScope.LINE, 1, BigDecimal.ONE, 22L))));
    }

    @Test
    void legacyGlobalLineRowsAreNotListed() {
        InMemoryTiers repository = new InMemoryTiers();
        repository.seed(
                new DiscountTier(1L, TierScope.LINE, 0, new BigDecimal("99")),
                new DiscountTier(2L, TierScope.LINE, 100, new BigDecimal("5"), 11L));
        DiscountTierService service = new DiscountTierService(repository);

        List<DiscountTier> visible = service.list(TierScope.LINE);

        assertEquals(1, visible.size());
        assertEquals(11L, visible.getFirst().productId());
    }

    @Test
    void negativeThresholdAndOutOfRangeDiscountAreRejected() {
        DiscountTierService service = new DiscountTierService(new InMemoryTiers());

        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, -1, BigDecimal.ZERO))));
        assertThrows(BusinessRuleException.class, () -> service.replace(TierScope.ORDER, List.of(
                new DiscountTier(null, TierScope.ORDER, 1, new BigDecimal("101")))));
    }

    private static DiscountTierService serviceWithProducts(InMemoryTiers repository) {
        ProductService products = mock(ProductService.class);
        return new DiscountTierService(repository, products);
    }

    private static final class InMemoryTiers implements SalesRepositories.Tiers {
        private final List<DiscountTier> stored = new ArrayList<>();

        void seed(DiscountTier... tiers) {
            stored.addAll(List.of(tiers));
        }

        @Override
        public List<DiscountTier> findByScope(TierScope scope) {
            return stored.stream().filter(tier -> tier.scope() == scope).toList();
        }

        @Override
        public List<DiscountTier> findByScopeAndProduct(TierScope scope, long productId) {
            return stored.stream()
                    .filter(tier -> tier.scope() == scope && tier.productId() != null
                            && tier.productId() == productId)
                    .toList();
        }

        @Override
        public void replaceScope(TierScope scope, List<DiscountTier> tiers) {
            stored.removeIf(tier -> tier.scope() == scope);
            stored.addAll(tiers);
        }

        @Override
        public void replaceProduct(TierScope scope, long productId, List<DiscountTier> tiers) {
            stored.removeIf(tier -> tier.scope() == scope && tier.productId() != null
                    && tier.productId() == productId);
            stored.addAll(tiers);
        }
    }
}
