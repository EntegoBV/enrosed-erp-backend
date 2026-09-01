package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Manages the discount tiers; the two stack during pricing. */
@ApplicationScoped
public class DiscountTierService {

    private final SalesRepositories.Tiers tiers;
    private final ProductService products;

    @Inject
    public DiscountTierService(SalesRepositories.Tiers tiers, ProductService products) {
        this.tiers = tiers;
        this.products = products;
    }

    /** Compatibility for focused unit tests that do not load the catalogue. */
    public DiscountTierService(SalesRepositories.Tiers tiers) {
        this(tiers, null);
    }

    public List<DiscountTier> list(TierScope scope) {
        requireScope(scope);
        return tiers.findByScope(scope).stream()
                /* Rows without a product are old global LINE tiers. They remain
                   readable at database level for a safe rollout, but must never
                   reach pricing or the product-specific editor. */
                .filter(tier -> hasValidTarget(scope, tier))
                .sorted(Comparator.comparing(DiscountTier::productId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(DiscountTier::minQuantity))
                .toList();
    }

    public List<DiscountTier> listForProduct(long productId) {
        requireProduct(productId);
        return tiers.findByScopeAndProduct(TierScope.LINE, productId).stream()
                .filter(tier -> tier != null && tier.productId() != null
                        && tier.productId() == productId)
                .sorted(Comparator.comparingInt(DiscountTier::minQuantity))
                .toList();
    }

    @Transactional
    public List<DiscountTier> replace(TierScope scope, List<DiscountTier> replacement) {
        requireScope(scope);
        requireReplacement(replacement);

        Set<TierKey> thresholds = new HashSet<>();
        Set<Long> productIds = new LinkedHashSet<>();
        List<DiscountTier> normalized = replacement.stream()
                .map(tier -> normalize(scope, null, tier))
                .toList();
        for (DiscountTier tier : normalized) {
            TierKey key = new TierKey(tier.productId(), tier.minQuantity());
            if (!thresholds.add(key)) duplicateThreshold(tier.minQuantity());
            if (tier.productId() != null) productIds.add(tier.productId());
        }
        productIds.forEach(this::requireProduct);

        tiers.replaceScope(scope, normalized);
        return list(scope);
    }

    /** Replaces exactly one product's line schedule; every other schedule survives. */
    @Transactional
    public List<DiscountTier> replaceForProduct(
            long productId, List<DiscountTier> replacement) {
        requireProduct(productId);
        requireReplacement(replacement);

        Set<Integer> thresholds = new HashSet<>();
        List<DiscountTier> normalized = replacement.stream()
                .map(tier -> normalize(TierScope.LINE, productId, tier))
                .toList();
        for (DiscountTier tier : normalized) {
            if (!thresholds.add(tier.minQuantity())) duplicateThreshold(tier.minQuantity());
        }

        tiers.replaceProduct(TierScope.LINE, productId, normalized);
        return listForProduct(productId);
    }

    private DiscountTier normalize(TierScope scope, Long pathProductId, DiscountTier tier) {
        if (tier == null || tier.minQuantity() < 0) {
            throw new BusinessRuleException("Een kortingsdrempel kan niet negatief zijn");
        }
        BigDecimal percent = tier.percent();
        if (percent == null || percent.signum() < 0
                || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException("Korting moet tussen 0 en 100% liggen");
        }

        if (tier.scope() != null && tier.scope() != scope) {
            throw new BusinessRuleException("Kortingsschaal in de regel past niet bij " + scope);
        }
        if (scope == TierScope.ORDER) {
            if (tier.productId() != null) {
                throw new BusinessRuleException("Orderkorting mag niet aan een product gekoppeld zijn");
            }
            return new DiscountTier(null, scope, tier.minQuantity(), tier.percent(), null);
        }

        if (pathProductId != null && tier.productId() != null
                && !Objects.equals(pathProductId, tier.productId())) {
            throw new BusinessRuleException("De korting hoort bij een ander product dan het gekozen product");
        }
        Long productId = pathProductId == null ? tier.productId() : pathProductId;
        if (productId == null) {
            throw new BusinessRuleException("Kies voor elke lijnkorting een product");
        }
        if (productId <= 0) {
            throw new BusinessRuleException("Kies een geldig product voor de lijnkorting");
        }
        return new DiscountTier(null, scope, tier.minQuantity(), tier.percent(), productId);
    }

    private static boolean hasValidTarget(TierScope scope, DiscountTier tier) {
        return tier != null && (scope == TierScope.LINE
                ? tier.productId() != null : tier.productId() == null);
    }

    private static void requireScope(TierScope scope) {
        if (scope == null) throw new BusinessRuleException("Kortingsschaal is verplicht");
    }

    private static void requireReplacement(List<DiscountTier> replacement) {
        if (replacement == null) throw new BusinessRuleException("Kortingsregels zijn verplicht");
    }

    private void requireProduct(long productId) {
        if (productId <= 0) {
            throw new BusinessRuleException("Kies een geldig product voor de lijnkorting");
        }
        if (products != null) products.get(productId);
    }

    private static void duplicateThreshold(int minQuantity) {
        throw new BusinessRuleException(
                "Er staat meer dan één korting op drempel " + minQuantity);
    }

    private record TierKey(Long productId, int minQuantity) {}
}
