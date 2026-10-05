package be.enrosed.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-me texts of the photo export. Same guard rail as
 * {@link DocumentTextTest}: a missing key would print an empty label in a
 * file that is already with a customer.
 */
class PhotoExportTextTest {

    @Test
    @DisplayName("elke taal heeft precies dezelfde sleutels")
    void everyLanguageHasEveryKey() {
        assertEquals(9, Language.values().length,
                "pas deze test aan wanneer een klanttaal wordt toegevoegd");
        Set<String> reference = new TreeSet<>(PhotoExportText.of(Language.NL).keySet());
        assertTrue(reference.size() > 30, "the bundle must actually be loaded");

        for (Language language : Language.values()) {
            Set<String> keys = new TreeSet<>(PhotoExportText.of(language).keySet());

            Set<String> missing = new LinkedHashSet<>(reference);
            missing.removeAll(keys);
            assertTrue(missing.isEmpty(), language + " mist: " + missing);

            Set<String> extra = new LinkedHashSet<>(keys);
            extra.removeAll(reference);
            assertTrue(extra.isEmpty(), language + " heeft er te veel: " + extra);
        }
    }

    @Test
    @DisplayName("geen enkele tekst is leeg")
    void noBlankText() {
        for (Language language : Language.values()) {
            for (Map.Entry<String, String> entry : PhotoExportText.of(language).entrySet()) {
                assertTrue(entry.getValue() != null && !entry.getValue().isBlank(),
                        language + " heeft niets staan bij " + entry.getKey());
            }
        }
    }

    @Test
    @DisplayName("invulplekken overleven de vertaling")
    void placeholdersSurviveTranslation() {
        Map<String, String[]> placeholders = Map.of(
                "about.lead", new String[] {"{lead}"},
                "about.shared", new String[] {"{role}"},
                "packaging.displayOf", new String[] {"{count}"},
                "carton.pieces", new String[] {"{count}"},
                "carton.displays", new String[] {"{count}", "{pieces}"},
                "carton.oneDisplay", new String[] {"{pieces}"});
        for (Language language : Language.values()) {
            for (Map.Entry<String, String[]> expected : placeholders.entrySet()) {
                String text = PhotoExportText.text(language, expected.getKey());
                for (String placeholder : expected.getValue()) {
                    assertTrue(text.contains(placeholder),
                            language + " " + expected.getKey() + " mist " + placeholder);
                }
            }
        }
    }

    @Test
    @DisplayName("elke selectie heeft een label in elke taal")
    void everySelectionHasALabel() {
        for (Language language : Language.values()) {
            for (String key : new String[] {"scope.ACTIVE", "scope.WEBSITE", "scope.ALL",
                                            "photos.ALL", "photos.WEBSITE"}) {
                assertTrue(PhotoExportText.of(language).containsKey(key), language + " mist " + key);
            }
        }
    }

    @Test
    void anUnknownKeyIsAProgrammingError() {
        assertThrows(IllegalStateException.class, () -> PhotoExportText.text(Language.FR, "does.not.exist"));
    }
}
