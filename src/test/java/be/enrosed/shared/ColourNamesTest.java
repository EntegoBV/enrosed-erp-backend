package be.enrosed.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour dictionary. A standard colour needs no translation per product,
 * so the public catalogue has to know which colours are standard - by the
 * same normalisation the translation itself uses.
 */
class ColourNamesTest {

    @Test
    @DisplayName("elke keuzelijstkleur is een standaardkleur met een woord in alle negen talen")
    void everyPickListColourIsStandardInEveryLanguage() {
        assertEquals(9, Language.values().length);
        for (String colour : ColourNames.STANDARD) {
            assertTrue(ColourNames.isStandard(colour), colour);
            for (Language language : Language.values()) {
                String word = ColourNames.standardName(colour, language);
                assertTrue(word != null && !word.isBlank(), colour + " " + language);
            }
            assertEquals(colour, ColourNames.standardName(colour, Language.NL));
        }
    }

    @Test
    @DisplayName("hoofdletters en spaties eromheen tellen niet, zoals bij het vertalen")
    void caseAndSurroundingSpacesFollowTheNormalisationOfTranslate() {
        for (String stored : new String[] {"Bordeaux", "bordeaux", "BORDEAUX", "  bordeaux ", "bORDEAUX\t"}) {
            assertTrue(ColourNames.isStandard(stored), stored);
            assertEquals("Burgundy", ColourNames.translate(stored, Language.EN), stored);
            assertEquals("Burgundy", ColourNames.standardName(stored, Language.EN), stored);
            assertEquals("Bordeaux", ColourNames.standardName(stored, Language.NL),
                    "the public word is the dictionary's spelling in Dutch too");
            assertEquals(stored, ColourNames.translate(stored, Language.NL),
                    "documents keep printing the stored Dutch word itself");
        }
    }

    @Test
    @DisplayName("een eigen kleur, een code, leeg of null is geen standaardkleur")
    void anythingElseIsNotStandard() {
        for (String stored : new String[] {"Vintage roze", "Panda", "Burgundy", "Bordeaux rood", "Bor deaux", "", "   "}) {
            assertFalse(ColourNames.isStandard(stored), stored);
            assertNull(ColourNames.standardName(stored, Language.EN), stored);
        }
        assertFalse(ColourNames.isStandard(null));
        assertNull(ColourNames.standardName(null, Language.FR));
        assertEquals("Vintage roze", ColourNames.translate("Vintage roze", Language.FR));
    }
}
