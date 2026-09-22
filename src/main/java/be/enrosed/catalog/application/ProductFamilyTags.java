package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;

import java.util.LinkedHashSet;
import java.util.List;

/** Normalizes an optional translated tag list without turning omission into a clear. */
public final class ProductFamilyTags {
    private ProductFamilyTags() {}

    public static List<String> normalize(List<String> requested) {
        if (requested == null) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            if (value == null || value.isBlank()) {
                throw new BusinessRuleException("Familietags mogen niet leeg zijn");
            }
            String tag = value.strip();
            if (tag.length() > 255) {
                throw new BusinessRuleException("Een familietag mag maximaal 255 tekens bevatten");
            }
            result.add(tag);
        }
        return List.copyOf(result);
    }
}
