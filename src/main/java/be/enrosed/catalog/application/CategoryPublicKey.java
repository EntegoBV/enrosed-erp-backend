package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;

import java.text.Normalizer;
import java.util.Locale;

/** Derives the stable lowercase URL key without rewriting the administrator-owned category code. */
public final class CategoryPublicKey {
    private CategoryPublicKey() {}

    public static String from(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessRuleException("Categoriecode is verplicht");
        }
        String ascii = Normalizer.normalize(code.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String key = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (key.isBlank()) {
            throw new BusinessRuleException(
                    "Categoriecode moet minstens één letter of cijfer bevatten");
        }
        return key;
    }
}
