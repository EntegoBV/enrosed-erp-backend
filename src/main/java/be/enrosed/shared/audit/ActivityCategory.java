package be.enrosed.shared.audit;

import java.util.Locale;
import java.util.Set;

/** Stable business grouping used by the company-wide logbook. */
public enum ActivityCategory {
    SALES(Set.of("SALES_ORDER")),
    PURCHASING(Set.of("PURCHASE_ORDER", "SUPPLIER")),
    CATALOGUE(Set.of("PRODUCT", "PRODUCT_FAMILY", "MEDIA_ASSET")),
    RELATIONS(Set.of("CUSTOMER")),
    PLANNING(Set.of("PLANNER_ITEM")),
    OTHER(Set.of());

    private static final Set<String> KNOWN_ENTITY_TYPES = Set.of(
            "SALES_ORDER", "PURCHASE_ORDER", "SUPPLIER", "PRODUCT",
            "PRODUCT_FAMILY", "MEDIA_ASSET", "CUSTOMER", "PLANNER_ITEM");

    private final Set<String> entityTypes;

    ActivityCategory(Set<String> entityTypes) {
        this.entityTypes = entityTypes;
    }

    public Set<String> entityTypes() {
        return entityTypes;
    }

    public static Set<String> knownEntityTypes() {
        return KNOWN_ENTITY_TYPES;
    }

    public static ActivityCategory forEntityType(String entityType) {
        String normalized = entityType == null ? "" : entityType.strip().toUpperCase(Locale.ROOT);
        for (ActivityCategory category : values()) {
            if (category.entityTypes.contains(normalized)) return category;
        }
        return OTHER;
    }

    public static ActivityCategory fromQuery(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Unknown activity category");
        }
    }
}
