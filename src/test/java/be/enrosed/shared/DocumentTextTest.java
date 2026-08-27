package be.enrosed.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation bundles.
 *
 * The most important test is the first: a missing key raises no error but
 * leaves an empty box on a quote already with a customer. Hence every
 * language must carry exactly the same keys.
 */
class DocumentTextTest {

    @Test
    @DisplayName("elke taal heeft precies dezelfde sleutels")
    void everyLanguageHasEveryKey() {
        Set<String> reference = new TreeSet<>(DocumentText.of(Language.NL).keySet());

        for (Language language : Language.values()) {
            Set<String> keys = new TreeSet<>(DocumentText.of(language).keySet());

            Set<String> missing = new LinkedHashSet<>(reference);
            missing.removeAll(keys);
            assertTrue(missing.isEmpty(),
                    language + " mist: " + missing);

            Set<String> extra = new LinkedHashSet<>(keys);
            extra.removeAll(reference);
            assertTrue(extra.isEmpty(),
                    language + " heeft er te veel: " + extra);
        }
    }

    @Test
    @DisplayName("geen enkele tekst is leeg")
    void noBlankText() {
        for (Language language : Language.values()) {
            for (Map.Entry<String, String> entry : DocumentText.of(language).entrySet()) {
                assertTrue(entry.getValue() != null && !entry.getValue().isBlank(),
                        language + " heeft niets staan bij " + entry.getKey());
            }
        }
    }

    @Test
    @DisplayName("het pakboncontract is compleet in alle acht klanttalen")
    void packingSlipContractIsCompleteInAllEightLanguages() {
        assertEquals(8, Language.values().length,
                "pas deze expliciete taalcontracttest aan wanneer een klanttaal wordt toegevoegd");
        Set<String> packingSlipKeys = Set.of(
                "packingSlip", "deliveryAddress", "pieces", "looseCartons",
                "notOnPallet", "contents", "loadCheck", "loadedByDate",
                "receivedByDate", "height");

        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            for (String key : packingSlipKeys) {
                assertTrue(text.containsKey(key) && !text.get(key).isBlank(),
                        language + " mist pakbontekst " + key);
            }
        }
    }

    @Test
    @DisplayName("zinnen met een invulplek houden die ook in vertaling")
    void placeholdersSurviveTranslation() {
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            for (String key : new String[] {"validUntilSentence", "mailSubject",
                                            "mailSubjectTermsAdded", "mailIntro",
                                            "mailIntroUpdated"}) {
                assertTrue(text.get(key).contains("%s"),
                        language + " mist de invulplek in " + key + ": " + text.get(key));
            }
        }
    }

    @Test
    @DisplayName("datums volgen de taal")
    void datesFollowLanguage() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        assertEquals("25/05/2026", DocumentText.date(date, Language.NL));
        assertEquals("25/05/2026", DocumentText.date(date, Language.FR));
        assertEquals("25/05/2026", DocumentText.date(date, Language.DE));
        /* English gets the month spelled out: 05/25 and 25/05 read
           differently on either side of the ocean, and a delivery term
           leaves no room for that doubt. */
        assertEquals("25 May 2026", DocumentText.date(date, Language.EN));
    }

    @Test
    @DisplayName("de leverweek wordt in elke taal uitgeschreven")
    void weekIsSpelledOut() {
        assertEquals("week 42 (12/10 - 18/10/2026)", DocumentText.week("2026-W42", Language.NL));
        assertEquals("semaine 42 (12/10 - 18/10/2026)", DocumentText.week("2026-W42", Language.FR));
        assertEquals("KW 42 (12/10 - 18/10/2026)", DocumentText.week("2026-W42", Language.DE));
        assertEquals("week 42 (12/10 - 18 October 2026)", DocumentText.week("2026-W42", Language.EN));
    }

    @Test
    @DisplayName("een onbekende taalcode valt terug op Nederlands")
    void unknownLanguageFallsBack() {
        assertEquals(Language.NL, Language.of(null));
        assertEquals(Language.NL, Language.of(""));
        assertEquals(Language.NL, Language.of("zz"));
        assertEquals(Language.FR, Language.of("fr"));
        assertEquals(Language.FR, Language.of("FR"));
        assertEquals(Language.DE, Language.of("de"));
    }
}
