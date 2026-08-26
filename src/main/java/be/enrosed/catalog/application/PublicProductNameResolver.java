package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * One public-name fallback policy shared by every public catalogue projection.
 *
 * Explicit public translations win. Legacy document translations remain a
 * zero-downtime fallback only while the base public name is still inherited;
 * once an editor deliberately diverges the public base name, the internal
 * document translation can no longer leak back into the website.
 */
@ApplicationScoped
public class PublicProductNameResolver {

    public LanguageFallback.Resolved<String> resolve(
            ProductEntity product, Language requested) {
        LanguageFallback.Resolved<String> explicit = LanguageFallback.text(
                product.texts, requested, text -> text.language,
                text -> text.publicName, null);
        if (present(explicit.value())) return explicit;

        if (inherited(product.publicName, product.name)) {
            return LanguageFallback.text(product.texts, requested,
                    text -> text.language, text -> text.name, product.name);
        }
        return new LanguageFallback.Resolved<>(product.publicName, null);
    }

    public String name(ProductEntity product, Language requested) {
        return resolve(product, requested).value();
    }

    private static boolean inherited(String publicName, String documentName) {
        return !present(publicName) || documentName != null
                && publicName.strip().equalsIgnoreCase(documentName.strip());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
