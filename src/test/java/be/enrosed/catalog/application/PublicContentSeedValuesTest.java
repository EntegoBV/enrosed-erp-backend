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

    @Test
    void consentUpdateRecognizesOnlyTheExactReleasedPrivacyCopy() throws Exception {
        try (var input = getClass().getResourceAsStream("/i18n/website-consent-previous-values.json")) {
            var previous = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            String old = previous.path("footer.cookie.analyticsDescription").path("NL").asText();
            assertFalse(old.isBlank());
            assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.analyticsDescription", Language.NL, old));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.analyticsDescription", Language.NL, "Eigen privacyverklaring van de beheerder"));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.analyticsDescription", Language.EL, old));
        }
    }

    @Test
    void cookieIntroCorrectsTheTwoObservedLegacyVariantsWithoutMatchingAdministratorEdits() throws Exception {
        Map<Language, String> old = Map.of(
                Language.NL, "Deze Enrosed-website gebruikt momenteel geen analyse- of advertentiecookies. Er wordt alleen gebruik gemaakt van de functionaliteit die nodig is om de website weer te geven en de door u gekozen links te openen.",
                Language.PL, "Ta witryna internetowa Enrosed nie wykorzystuje obecnie żadnych plików cookie do celów analitycznych ani reklamowych. Wykorzystywane są wyłącznie funkcjonalności niezbędne do wyświetlenia strony i otwarcia wybranych przez Ciebie linków.");
        for (var entry : old.entrySet()) {
            assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.description", entry.getKey(), entry.getValue()));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.description", entry.getKey(), entry.getValue() + " Eigen aanvulling."));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.analyticsDescription", entry.getKey(), entry.getValue()));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.description", Language.EL, entry.getValue()));
        }
        for (Language language : Language.values()) {
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.description", language, null));
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                    "footer.cookie.description", language, ""));
        }
        try (var input = getClass().getResourceAsStream("/i18n/website-consent-previous-values.json")) {
            var previous = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input)
                    .path("footer.cookie.description");
            for (Language language : old.keySet()) {
                assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(ContentScope.WEBSITE,
                        "footer.cookie.description", language, previous.path(language.name()).asText()));
            }
        }
    }
}
