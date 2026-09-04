package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicContentSeedValuesTest {

    @Test
    void shippedCatalogueCopyIsAvailableWithoutTheStoreForEveryLanguage() {
        for (Language language : Language.values()) {
            Map<String, String> values = PublicContentSeedLoader.catalogSeedValues(language);
            assertTrue(values.containsKey("catalog.brochure.overview.priceonrequest"), language.code());
            assertTrue(values.containsKey("catalog.spec.container"), language.code());
            assertFalse(values.containsValue(""), language.code());
        }
        assertEquals("Prijs op aanvraag",
                PublicContentSeedLoader.catalogSeedValues(Language.NL).get("catalog.brochure.overview.priceonrequest"));
        assertEquals("UITVOERINGEN",
                PublicContentSeedLoader.catalogSeedValues(Language.NL).get("catalog.common.variant.plural"));
    }

    @Test
    void onlyTheFormerSelectionWordingIsCorrectedNeverADashboardEdit() {
        assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(
                ContentScope.CATALOG, "catalog.common.variant.plural", Language.NL, "GESELECTEERDE VARIANTEN"));
        assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(
                ContentScope.CATALOG, "catalog.common.selectedfamily.singular", Language.EN, "selected product family"));
        assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                ContentScope.CATALOG, "catalog.common.variant.plural", Language.NL, "KLEUREN"));
        assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                ContentScope.CATALOG, "catalog.brochure.overview.title", Language.NL, "Het volledige assortiment in één oogopslag."));
    }
}
