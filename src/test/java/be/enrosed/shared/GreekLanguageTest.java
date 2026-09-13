package be.enrosed.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreekLanguageTest {
    @Test
    void greekPublicRequestsResolveExplicitlyAndUnsupportedCodesStillFail() {
        assertEquals(Language.EL, Language.requireSupported("el", Language.EN));
        assertEquals(Language.EL, Language.requireSupported(" EL ", Language.NL));
        assertEquals("el-GR", Language.EL.locale().toLanguageTag());
        assertEquals(Language.EN, Language.requireSupported(null, Language.EN));
        assertThrows(IllegalArgumentException.class, () -> Language.requireSupported("xx", Language.EN));
        assertEquals(java.util.List.of(Language.EL, Language.EN, Language.NL), LanguageFallback.chain(Language.EL));
        assertTrue(DocumentText.of(Language.EL).get("quote").codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.GREEK));
    }
}
