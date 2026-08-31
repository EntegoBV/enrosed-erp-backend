package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The stable merchandising order shared by catalogue-facing product lists.
 *
 * A colour name is presentation copy and can change per language. The
 * canonical colour/variant key for ordering is therefore the member's
 * {@link Product#variantPosition()}, after its category and family position.
 */
@ApplicationScoped
public class ProductOverviewOrder {

    private final CanonicalCatalogDaos.Families families;

    public ProductOverviewOrder(CanonicalCatalogDaos.Families families) {
        this.families = families;
    }

    public List<Product> sort(Collection<Product> products) {
        List<Product> copy = List.copyOf(products);
        return copy.stream().sorted(comparatorFor(copy)).toList();
    }

    public Comparator<Product> comparatorFor(Collection<Product> products) {
        Set<Long> familyIds = products.stream()
                .map(Product::familyId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (familyIds.isEmpty()) return fallbackComparator();

        Map<Long, FamilyRank> ranks = new HashMap<>();
        for (ProductFamilyEntity family : families.list("id in ?1", familyIds)) {
            ranks.put(family.id, new FamilyRank(
                    family.categoryPosition, family.productPosition, family.name));
        }
        return comparator(ranks);
    }

    /** Deterministic legacy fallback for products that are not in a canonical family yet. */
    public static Comparator<Product> fallbackComparator() {
        return comparator(Map.of());
    }

    static Comparator<Product> comparator(Map<Long, FamilyRank> ranks) {
        Collator words = Collator.getInstance(Locale.forLanguageTag("nl-BE"));
        words.setStrength(Collator.PRIMARY);

        return (left, right) -> {
            FamilyRank leftRank = left.familyId() == null ? null : ranks.get(left.familyId());
            FamilyRank rightRank = right.familyId() == null ? null : ranks.get(right.familyId());

            int compared = Integer.compare(categoryPosition(leftRank), categoryPosition(rightRank));
            if (compared != 0) return compared;
            compared = Integer.compare(productPosition(leftRank), productPosition(rightRank));
            if (compared != 0) return compared;
            compared = compareText(words, familyName(left, leftRank), familyName(right, rightRank));
            if (compared != 0) return compared;
            compared = compareNullableLong(left.familyId(), right.familyId());
            if (compared != 0) return compared;
            compared = Integer.compare(left.variantPosition(), right.variantPosition());
            if (compared != 0) return compared;
            compared = compareText(words, left.canonicalVariantKey(), right.canonicalVariantKey());
            if (compared != 0) return compared;
            compared = compareText(words, left.colour(), right.colour());
            if (compared != 0) return compared;
            compared = compareText(words, left.variantSize(), right.variantSize());
            if (compared != 0) return compared;
            compared = compareText(words, left.sku(), right.sku());
            if (compared != 0) return compared;
            return compareNullableLong(left.id(), right.id());
        };
    }

    private static int categoryPosition(FamilyRank rank) {
        return rank == null ? Integer.MAX_VALUE : rank.categoryPosition();
    }

    private static int productPosition(FamilyRank rank) {
        return rank == null ? Integer.MAX_VALUE : rank.productPosition();
    }

    private static String familyName(Product product, FamilyRank rank) {
        return rank == null || rank.name() == null || rank.name().isBlank()
                ? product.name() : rank.name();
    }

    private static int compareText(Collator words, String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int compared = words.compare(safeLeft, safeRight);
        return compared != 0 ? compared : safeLeft.compareTo(safeRight);
    }

    private static int compareNullableLong(Long left, Long right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return Long.compare(left, right);
    }

    record FamilyRank(int categoryPosition, int productPosition, String name) {}
}
