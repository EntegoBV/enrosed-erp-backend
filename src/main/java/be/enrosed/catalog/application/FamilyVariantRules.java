package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Canonical website option and ordering invariants for stock-bearing family members. */
public final class FamilyVariantRules {
    public static final String OPTION_ISSUE =
            "Actieve varianten moeten een unieke combinatie van kleur en maat hebben";
    public static final String POSITION_ISSUE =
            "Actieve varianten moeten een unieke niet-negatieve positie binnen de familie hebben";

    private FamilyVariantRules() {}

    public static boolean websiteOptionsMustBeUnique(ProductFamilyEntity family) {
        return readyOrPublished(family.websiteStatus)
                || readyOrPublished(family.orderAppStatus)
                || readyOrPublished(family.catalogueStatus);
    }

    public static boolean hasDuplicateOptions(
            ProductFamilyEntity family, List<ProductEntity> members) {
        if (!websiteOptionsMustBeUnique(family)) return false;
        Set<OptionKey> seen = new HashSet<>();
        return members.stream().filter(member -> member.active)
                .map(member -> key(member.colour, member.variantSize))
                .anyMatch(option -> !seen.add(option));
    }

    public static boolean hasInvalidPositions(List<ProductEntity> members) {
        Set<Integer> seen = new HashSet<>();
        return members.stream().filter(member -> member.active)
                .map(member -> member.variantPosition)
                .anyMatch(position -> position < 0 || !seen.add(position));
    }

    public static boolean sameOption(Product left, Product right) {
        return Objects.equals(key(left.colour(), left.variantSize()),
                key(right.colour(), right.variantSize()));
    }

    private static OptionKey key(String colour, String size) {
        return new OptionKey(normalize(colour), normalize(size));
    }

    private static boolean readyOrPublished(PublicationState state) {
        return state == PublicationState.READY || state == PublicationState.PUBLISHED;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record OptionKey(String colour, String size) {}
}
