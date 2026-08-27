package be.enrosed.catalog.application;

import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure contract for the stable-key fixture; this test never opens a database connection. */
class CatalogDurabilityBackfillResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fixtureContainsTheExactGuardedFamilyMediaAndCategoryTargets() throws Exception {
        JsonNode root = resource();
        assertEquals("2026-08-27-catalog-durability-v1", root.path("version").asText());
        assertTrue(root.path("preserveStatuses").asBoolean());

        JsonNode expected = root.path("expectedCounts");
        JsonNode identity = root.path("familyIdentity");
        assertEquals("model-103-104", identity.path("expectedFamilyKey").asText());
        assertEquals("long-stem-rose-box-display", identity.path("familyKey").asText());
        assertEquals("long-stem-roses-display", identity.path("publicHandle").asText());
        assertEquals("display-roses", identity.path("categoryKey").asText());
        assertEquals(expected.path("familyIdentityVariants").asInt(),
                identity.path("variants").size());
        assertEquals(expected.path("familyIdentityTranslations").asInt(),
                identity.path("translations").size());
        assertEightLanguages(identity.path("translations"), List.of(
                "name", "summary", "description", "format", "seoTitle", "seoDescription"));
        assertEquals("12 extra lange steelrozen in individuele boxen met display",
                identity.path("translations").path("NL").path("name").asText());
        assertEquals("12 Extra-Long Preserved Roses in Individual Boxes with Display",
                identity.path("translations").path("EN").path("name").asText());

        List<String> skus = values(identity.path("variants"), "sku");
        assertEquals(List.of("ENR-P01", "ENR-P02", "ENR-P03", "ENR-P04"), skus);
        assertTrue(identity.path("variants").findValues("expectedCanonicalVariantKey")
                .stream().allMatch(JsonNode::isNull));
        assertEquals(Set.of(
                        "long-stem-rose-box-display-red",
                        "long-stem-rose-box-display-pink",
                        "long-stem-rose-box-display-white",
                        "long-stem-rose-box-display-navy"),
                new HashSet<>(values(identity.path("variants"), "canonicalVariantKey")));

        JsonNode media = root.path("familyMedia");
        assertEquals("preserved-single-rose-in-display", media.path("familyKey").asText());
        assertEquals(expected.path("familyMediaSkus").asInt(), media.path("expectedSkus").size());
        assertEquals(expected.path("familyMediaSourceKeys").asInt(),
                media.path("orderedSourceKeys").size());
        assertEquals(expected.path("familyMediaInternalOnlySourceKeys").asInt(),
                media.path("internalOnlySourceKeys").size());
        List<String> ordered = stringValues(media.path("orderedSourceKeys"));
        assertEquals(List.of("34987448959145", "34988100944041", "34988100354217",
                "34988101664937"), ordered.subList(0, 4));
        assertTrue(new HashSet<>(ordered)
                .containsAll(stringValues(media.path("internalOnlySourceKeys"))));
        assertEquals(ordered.size(), new HashSet<>(ordered).size());

        JsonNode categories = root.path("categories");
        assertEquals(expected.path("categories").asInt(), categories.size());
        assertEquals(Set.of("rose-bears", "soap-roses"), fieldNames(categories));
        categories.fields().forEachRemaining(category -> assertEightLanguages(
                category.getValue().path("translations"), List.of(
                        "name", "eyebrow", "description", "mobileName",
                        "navigationName", "footerName")));
        assertEquals("Foam Roses & Bears",
                categories.path("rose-bears").path("translations").path("EN")
                        .path("name").asText());
        assertTrue(categories.path("rose-bears").path("featuredSku").isNull());
        assertEquals("ENR-SOAP-ROSE-BOX-LED-RED",
                categories.path("soap-roses").path("featuredSku").asText());

        JsonNode moves = root.path("familyMoves");
        assertEquals(expected.path("familyMoves").asInt(), moves.size());
        assertTrue(moves.findValues("required").stream().allMatch(JsonNode::asBoolean));
        assertEquals(Set.of("soaproos-in-vensterdoos", "soap-rose-box-led",
                        "soap-roos-in-box", "odoo-half-heart-foam-25",
                        "odoo-half-heart-foam-40", "model-108-109", "model-111-112"),
                new HashSet<>(values(moves, "familyKey")));
        assertEquals(List.of(0, 1, 2), movesFor(moves, "soap-roses").stream()
                .map(move -> move.path("position").asInt()).toList());
        assertEquals(List.of(0, 1, 2, 3), movesFor(moves, "rose-bears").stream()
                .map(move -> move.path("position").asInt()).toList());

        /* The checked-in fixture must remain portable across TEST/PROD database sequences. */
        assertFalse(root.toString().contains("\"familyId\""));
        assertFalse(root.toString().contains("\"productId\""));
        assertFalse(root.toString().contains("\"categoryId\""));
        assertFalse(root.toString().contains("\"photoId\""));
    }

    private static void assertEightLanguages(JsonNode translations, List<String> fields) {
        assertEquals(Language.values().length, translations.size());
        for (Language language : Language.values()) {
            JsonNode value = translations.path(language.name());
            assertFalse(value.isMissingNode(), language.name());
            for (String field : fields) {
                assertFalse(value.path(field).asText().isBlank(),
                        language.name() + "." + field);
            }
        }
    }

    private static List<JsonNode> movesFor(JsonNode moves, String targetCategoryKey) {
        List<JsonNode> result = new ArrayList<>();
        moves.forEach(move -> {
            if (targetCategoryKey.equals(move.path("targetCategoryKey").asText())) result.add(move);
        });
        return result;
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.path(field).asText()));
        return result;
    }

    private static List<String> stringValues(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText()));
        return result;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static JsonNode resource() throws Exception {
        try (InputStream input = CatalogDurabilityBackfillResourceTest.class
                .getResourceAsStream("/i18n/catalog-durability-backfill.json")) {
            if (input == null) throw new IllegalStateException("Missing durability fixture");
            return JSON.readTree(input);
        }
    }
}
