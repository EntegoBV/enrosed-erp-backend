package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicProductNameResolverTest {
    private final PublicProductNameResolver resolver = new PublicProductNameResolver();

    @Test
    void explicitLocalizedPublicNameWins() {
        ProductEntity product = product("Invoice name", "Public base");
        text(product, Language.EN, "Invoice EN", "Public EN");

        var resolved = resolver.resolve(product, Language.EN);

        assertEquals("Public EN", resolved.value());
        assertEquals(Language.EN, resolved.sourceLanguage());
    }

    @Test
    void inheritedPublicNameKeepsLegacyLocalizedFallbackDuringRollout() {
        ProductEntity product = product("Invoice name", "Invoice name");
        text(product, Language.FR, "Facture FR", null);

        var resolved = resolver.resolve(product, Language.FR);

        assertEquals("Facture FR", resolved.value());
        assertEquals(Language.FR, resolved.sourceLanguage());
    }

    @Test
    void divergentPublicBaseNeverLeaksLegacyDocumentTranslation() {
        ProductEntity product = product("Invoice name", "Public base");
        text(product, Language.FR, "Facture FR", null);

        var resolved = resolver.resolve(product, Language.FR);

        assertEquals("Public base", resolved.value());
        assertNull(resolved.sourceLanguage());
    }

    private static ProductEntity product(String documentName, String publicName) {
        ProductEntity product = new ProductEntity();
        product.name = documentName;
        product.publicName = publicName;
        return product;
    }

    private static void text(
            ProductEntity product, Language language,
            String documentName, String publicName) {
        ProductTextEntity text = new ProductTextEntity();
        text.product = product;
        text.language = language;
        text.name = documentName;
        text.publicName = publicName;
        product.texts.add(text);
    }
}
