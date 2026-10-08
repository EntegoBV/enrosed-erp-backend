package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void supersededWebsiteCopyMatchesOnlyTheListedExactValuesPerKeyAndLanguage() throws Exception {
        JsonNode superseded = supersededWebsiteCopy();
        assertEquals(List.of("products.hero.title", "meta.products.title", "meta.products.description",
                        "legal.notice.purpose.p1", "legal.notice.updated"),
                fieldNames(superseded),
                "only the reviewed products-page copy, the legal notice purpose and its date are swapped");
        int values = 0;
        for (String key : fieldNames(superseded)) {
            JsonNode byLanguage = superseded.path(key);
            assertEquals(Language.values().length, byLanguage.size(), key + " covers every language");
            for (Language language : Language.values()) {
                JsonNode previous = byLanguage.path(language.name());
                assertTrue(previous.isArray() && !previous.isEmpty(), key + " " + language);
                for (JsonNode value : previous) {
                    values++;
                    String former = value.asText();
                    assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(
                            ContentScope.WEBSITE, key, language, former), key + " " + language);
                    assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                            ContentScope.WEBSITE, key, language, former + " Eigen aanvulling."));
                    assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                            ContentScope.WEBSITE, key, language, " " + former));
                    assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                            ContentScope.CATALOG, key, language, former));
                    assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                            ContentScope.WEBSITE, "products.hero.intro", language, former));
                    for (Language other : Language.values()) {
                        if (other == language || contains(previous(superseded, key, other), former)) continue;
                        assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                                ContentScope.WEBSITE, key, other, former),
                                key + " " + language + " value must not match " + other);
                    }
                }
            }
            for (Language language : Language.values()) {
                assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                        ContentScope.WEBSITE, key, language, null));
                assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                        ContentScope.WEBSITE, key, language, ""));
            }
        }
        assertEquals(53, values, "products page: live production value per language plus the earlier"
                + " bestseller seed (35); legal notice purpose: the one value every database held (9);"
                + " legal notice date: the one value every database held (9)");
    }

    @Test
    void supersededWebsiteCopyTargetsSeededRowsThatCarryTheReviewedReplacement() throws Exception {
        JsonNode superseded = supersededWebsiteCopy();
        Map<String, List<String>> seed = new java.util.HashMap<>();
        try (var input = getClass().getResourceAsStream("/i18n/website-content.csv")) {
            List<List<String>> rows = be.enrosed.shared.Csv.parseRows(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(List.of("nl", "fr", "en", "de", "es", "pl", "pt", "tr", "el"),
                    rows.getFirst().subList(3, 12));
            rows.stream().skip(1).forEach(row -> seed.put(row.getFirst(), row));
        }
        List<Language> columns = List.of(Language.NL, Language.FR, Language.EN, Language.DE,
                Language.ES, Language.PL, Language.PT, Language.TR, Language.EL);
        for (String key : fieldNames(superseded)) {
            List<String> row = seed.get(key);
            assertTrue(row != null, key + " is a seeded WEBSITE key");
            for (int index = 0; index < columns.size(); index++) {
                Language language = columns.get(index);
                String replacement = row.get(3 + index);
                assertFalse(replacement.isBlank(), key + " " + language);
                assertFalse(contains(previous(superseded, key, language), replacement),
                        key + " " + language + " seed must differ from every superseded value");
                assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                        ContentScope.WEBSITE, key, language, replacement),
                        key + " " + language + " replacement must be stable on the next start");
            }
        }
        assertEquals("The complete wholesale rose collection.", seed.get("products.hero.title").get(5));
        assertEquals("Preserved Rose Catalogue: Domes, Boxes & Displays | Enrosed",
                seed.get("meta.products.title").get(5));
        assertEquals("Catalogue de roses stabilisées : cloches, boîtes | Enrosed",
                seed.get("meta.products.title").get(4));
        assertEquals("The full wholesale range: preserved roses in glass domes, rose boxes and counter displays, plus soap and foam roses. Compare colours and request a quote.",
                seed.get("meta.products.description").get(5));
        for (int index = 3; index < 12; index++) {
            String description = seed.get("meta.products.description").get(index);
            assertTrue(description.length() <= 155, description);
            assertFalse(description.matches("(?iu).*(bestsell|best-seller|meistverkauft|superventas"
                    + "|najlepiej sprzedaj|mais vendid|en çok satan|bestverkocht|δημοφιλ|popular).*"),
                    "no popularity claim: " + description);
            assertTrue(seed.get("meta.products.title").get(index).length() <= 60,
                    seed.get("meta.products.title").get(index));
        }
    }

    @Test
    void legalNoticePurposeSaysAccountCustomersCanOrderAndTheFormerDenialIsSuperseded() throws Exception {
        String key = "legal.notice.purpose.p1";
        List<String> row;
        try (var input = getClass().getResourceAsStream("/i18n/website-content.csv")) {
            row = be.enrosed.shared.Csv.parseRows(
                            new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .stream().filter(candidate -> candidate.getFirst().equals(key)).findFirst().orElseThrow();
        }
        JsonNode superseded = supersededWebsiteCopy();
        List<Language> columns = List.of(Language.NL, Language.FR, Language.EN, Language.DE,
                Language.ES, Language.PL, Language.PT, Language.TR, Language.EL);
        for (int index = 0; index < columns.size(); index++) {
            Language language = columns.get(index);
            JsonNode previous = previous(superseded, key, language);
            assertEquals(1, previous.size(), "one former value: the seed every database held " + language);
            String former = previous.get(0).asText();
            String seeded = row.get(3 + index);
            int firstSentence = former.indexOf(". ") + 2;
            assertTrue(firstSentence > 2 && seeded.startsWith(former.substring(0, firstSentence)),
                    "the first sentence is unchanged " + language);
            assertTrue(seeded.length() > former.length(), language.name());
        }
        assertEquals("This website presents Enrosed products and wholesale information to businesses, including"
                        + " wholesalers, florists and retail buyers. It supports product comparison and quotation"
                        + " requests; business customers with an account can also place orders, which become binding"
                        + " only once Enrosed has confirmed them. The website itself does not conclude a sale.",
                row.get(5));
        assertTrue(row.get(3).endsWith(" De site ondersteunt productvergelijking en offerteaanvragen; zakelijke"
                + " klanten met een account kunnen er ook bestellen, waarbij een bestelling pas bindend is nadat"
                + " Enrosed ze heeft bevestigd. De website sluit zelf geen verkoop af."), row.get(3));
        assertTrue(previous(superseded, key, Language.EN).get(0).asText()
                .endsWith("it is not an online wholesale checkout and does not itself conclude a sale."));
        assertTrue(previous(superseded, key, Language.NL).get(0).asText()
                .endsWith("het is geen online groothandelswebshop en brengt zelf geen verkoopovereenkomst tot stand."));
        assertFalse(row.get(5).contains("not an online wholesale checkout"));
        assertFalse(row.get(3).contains("geen online groothandelswebshop"));
        // The trade terms on the same site call these customers "empresariales" / "empresariais".
        assertTrue(row.get(7).endsWith(" Permite comparar productos y solicitar presupuestos; los clientes"
                + " empresariales con cuenta también pueden hacer pedidos, que solo son vinculantes una vez"
                + " confirmados por Enrosed. El sitio web no formaliza por sí mismo ninguna venta."), row.get(7));
        assertTrue(row.get(8).endsWith(" Umożliwia porównanie produktów i składanie zapytań ofertowych;"
                + " klienci biznesowi posiadający konto mogą także składać zamówienia, które stają się wiążące"
                + " dopiero po potwierdzeniu przez Enrosed. Sama strona internetowa nie prowadzi do zawarcia"
                + " umowy sprzedaży."), row.get(8));
        assertTrue(row.get(9).endsWith(" Permite comparar produtos e pedir orçamentos; os clientes"
                + " empresariais com conta podem também fazer encomendas, que só se tornam vinculativas depois"
                + " de confirmadas pela Enrosed. O website não conclui, por si só, qualquer venda."), row.get(9));
    }

    @Test
    void legalNoticeDateIsTheDayTheOrderingTextWasPublishedAndTheFormerDateIsSuperseded() throws Exception {
        String key = "legal.notice.updated";
        List<String> row;
        try (var input = getClass().getResourceAsStream("/i18n/website-content.csv")) {
            row = be.enrosed.shared.Csv.parseRows(
                            new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .stream().filter(candidate -> candidate.getFirst().equals(key)).findFirst().orElseThrow();
        }
        assertEquals(List.of("8 oktober 2026", "8 octobre 2026", "8 October 2026", "8. Oktober 2026",
                "8 de octubre de 2026", "8 października 2026 r.", "8 de outubro de 2026", "8 Ekim 2026",
                "8 Οκτωβρίου 2026"), row.subList(3, 12));
        // Literals, not read back from the seed: what production holds until this release.
        List<String> former = List.of("20 augustus 2026", "20 août 2026", "20 August 2026", "20. August 2026",
                "20 de agosto de 2026", "20 sierpnia 2026 r", "20 de agosto de 2026", "20 Ağustos 2026",
                "20 Αυγούστου 2026");
        JsonNode superseded = supersededWebsiteCopy();
        List<Language> columns = List.of(Language.NL, Language.FR, Language.EN, Language.DE,
                Language.ES, Language.PL, Language.PT, Language.TR, Language.EL);
        for (int index = 0; index < columns.size(); index++) {
            Language language = columns.get(index);
            JsonNode previous = previous(superseded, key, language);
            assertEquals(1, previous.size(), "one former value: the seed every database held " + language);
            assertEquals(former.get(index), previous.get(0).asText(), language.name());
            assertTrue(PublicContentSeedLoader.isKnownStaleSeedValue(
                    ContentScope.WEBSITE, key, language, former.get(index)), language.name());
            // The other legal pages keep their own dates; this list must not reach them.
            assertFalse(PublicContentSeedLoader.isKnownStaleSeedValue(
                    ContentScope.WEBSITE, "legal.privacy.updated", language, former.get(index)), language.name());
        }
    }

    private JsonNode supersededWebsiteCopy() throws Exception {
        try (var input = getClass().getResourceAsStream("/i18n/website-copy-superseded-values.json")) {
            assertTrue(input != null, "superseded website copy resource is present");
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode previous(JsonNode superseded, String key, Language language) {
        return superseded.path(key).path(language.name());
    }

    private static boolean contains(JsonNode values, String candidate) {
        for (JsonNode value : values) {
            if (value.asText().equals(candidate)) return true;
        }
        return false;
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
