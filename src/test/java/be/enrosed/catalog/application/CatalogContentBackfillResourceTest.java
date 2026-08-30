package be.enrosed.catalog.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import be.enrosed.shared.Csv;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogLocalizationBackfillEntity;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogContentBackfillResourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void resourcesContainCompleteDistinctReviewedCopyAndCanonicalCounts() throws Exception {
        new CatalogContentBackfillService(null, null, null, null, JSON).validateResources();

        JsonNode backfill = resource("/i18n/catalog-content-backfill.json");
        assertEquals(3, backfill.path("expectedCounts").path("categories").asInt());
        assertEquals(27, backfill.path("expectedCounts").path("families").asInt());
        assertEquals(64, backfill.path("expectedCounts").path("variants").asInt());
        assertEquals(85, backfill.path("expectedCounts").path("images").asInt());
        assertEquals(64, backfill.path("targetVariantKeys").size());
        assertEquals(85, backfill.path("targetImageKeys").size());
        assertEquals(64, values(backfill.path("targetVariantKeys")).size());
        assertEquals(85, values(backfill.path("targetImageKeys")).size());
        assertTrue(values(backfill.path("targetImageKeys"))
                .containsAll(CatalogFoamPhotoBackfillService.targetImageKeys()));
        assertEquals(Set.of("display-roses", "divers", "rose-bears"),
                fieldNames(backfill.path("categories")));
        backfill.path("categories").fields().forEachRemaining(category -> {
            assertEquals(8, category.getValue().size());
            category.getValue().forEach(localized -> {
                for (String field : List.of("name", "eyebrow", "description", "mobileName",
                        "navigationName", "footerName")) {
                    assertFalse(localized.path(field).asText().isBlank(),
                            category.getKey() + "." + field);
                }
            });
        });
        assertEquals("Signature displays", backfill.path("categories")
                .path("display-roses").path("EN").path("mobileName").asText());
        assertEquals("Displays", backfill.path("categories")
                .path("display-roses").path("EN").path("navigationName").asText());
        assertEquals("Soap & foam roses", backfill.path("categories")
                .path("rose-bears").path("EN").path("footerName").asText());
        assertEquals("FOAM_DECORATIVE", backfill.path("families")
                .path("foam-half-heart-25").path("profile").asText());
        assertEquals("Foam Rose Bear 25 cm", backfill.path("families")
                .path("foam-bear-25").path("EN").asText());
        assertTrue(values(backfill.path("targetVariantKeys")).contains("foam-bear-25-mixed"));
        assertEquals("#DD92C9", backfill.path("colourHexes").path("Mixed").asText());

        JsonNode copy = resource("/i18n/catalog-family-copy.json");
        assertEquals(19, copy.path("families").size());
        for (String language : Set.of("NL", "FR", "EN", "DE", "ES", "PL", "PT", "TR")) {
            Set<String> summaries = new HashSet<>();
            Set<String> descriptions = new HashSet<>();
            copy.path("families").fields().forEachRemaining(family -> {
                JsonNode value = family.getValue().path(language);
                String summary = value.path("summary").asText();
                String description = value.path("description").asText();
                assertFalse(summary.isBlank(), family.getKey() + "." + language + ".summary");
                assertFalse(description.isBlank(), family.getKey() + "." + language + ".description");
                assertEquals(3, value.path("highlights").size());
                summaries.add(summary);
                descriptions.add(description);
            });
            assertEquals(19, summaries.size(), "summary copy must remain product-specific");
            assertEquals(19, descriptions.size(), "description copy must remain product-specific");
        }
    }

    @Test
    void publicCopyResourcesKeepTheReviewedEightLocaleContract() throws Exception {
        List<List<String>> website = csv("/i18n/website-content.csv");
        assertEquals(624, website.size(), "one header plus 623 website keys");
        assertTrue(website.stream().skip(1).allMatch(row -> row.size() == 11
                && row.subList(3, 11).stream().noneMatch(String::isBlank)));
        List<String> stemRoses = row(website, "home.counter.item3.title", 0);
        assertEquals("12 steelrozen", stemRoses.get(3));
        assertEquals("12 Stem Roses", stemRoses.get(5));
        for (String key : List.of("quote.error.validation", "quote.error.rateLimited",
                "quote.error.payloadTooLarge", "quote.error.reviewRequired",
                "quote.error.network", "quote.error.contact")) {
            List<String> errorCopy = row(website, key, 0);
            assertEquals(8, errorCopy.subList(3, 11).stream().filter(
                    value -> !value.isBlank()).count(), key);
        }
        List<String> rateLimited = row(website, "quote.error.rateLimited", 0);
        assertTrue(rateLimited.subList(3, 11).stream()
                .allMatch(value -> value.contains("{seconds}")));

        List<List<String>> catalog = csv("/i18n/public-content.csv");
        assertEquals(92, catalog.size(), "one header plus 91 catalogue keys");
        assertTrue(catalog.stream().skip(1).allMatch(row -> row.size() == 12
                && row.subList(4, 12).stream().noneMatch(String::isBlank)));
        assertEquals("GROSSISTA", row(catalog, "catalog.brand.wholesale", 1).get(10));
        assertEquals("AİLELER", row(catalog, "catalog.common.family.plural", 1).get(11));
    }

    @Test
    void staleContradictoryClaimsAreExplicitlyCorrected() throws Exception {
        JsonNode copy = resource("/i18n/catalog-family-copy.json");
        String heart = localizedDescription(copy, "hearth-glass-flowerbox", "NL");
        String glass = localizedDescription(copy, "glass-flowerbox", "NL");
        String dome = localizedDescription(copy, "rose-in-dome-m", "NL");
        assertFalse(heart.contains("13"));
        assertFalse(glass.contains("13"));
        assertFalse(dome.toLowerCase().contains("vier kleuren"));
        assertFalse(dome.toLowerCase().contains("kersenroze"));
        assertTrue(copy.path("families").path("hearth-glass-flowerbox").path("NL")
                .path("knownStaleDescription").asText().contains("13"));
        assertTrue(copy.path("families").path("rose-in-dome-m").path("NL")
                .path("knownStaleDescription").asText().contains("vier prachtige kleuren"));
    }

    @Test
    void sameBackfillVersionWithDifferentPayloadFailsLoudly() throws Exception {
        JsonNode backfill = resource("/i18n/catalog-content-backfill.json");
        JsonNode familyCopy = resource("/i18n/catalog-family-copy.json");
        String version = backfill.path("version").asText() + "+"
                + familyCopy.path("version").asText();
        CanonicalCatalogDaos.LocalizationBackfills markers = mock(
                CanonicalCatalogDaos.LocalizationBackfills.class);
        CatalogLocalizationBackfillEntity applied = new CatalogLocalizationBackfillEntity();
        applied.version = version;
        applied.payloadSha256 = "different-payload";
        when(markers.findById(version)).thenReturn(applied);

        CatalogContentBackfillService service = new CatalogContentBackfillService(
                null, null, null, markers, JSON);
        IllegalStateException error = assertThrows(IllegalStateException.class, service::apply);
        assertTrue(error.getMessage().contains("andere inhoud"));
    }

    private static String localizedDescription(JsonNode root, String family, String language) {
        return root.path("families").path(family).path(language).path("description").asText();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static Set<String> values(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static JsonNode resource(String path) throws Exception {
        try (InputStream input = CatalogContentBackfillResourceTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing test resource " + path);
            return JSON.readTree(input);
        }
    }

    private static List<List<String>> csv(String path) throws Exception {
        try (InputStream input = CatalogContentBackfillResourceTest.class
                .getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing test resource " + path);
            return Csv.parseRows(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private static List<String> row(
            List<List<String>> rows, String key, int keyIndex) {
        return rows.stream().skip(1).filter(item -> key.equals(item.get(keyIndex)))
                .findFirst().orElseThrow();
    }
}
