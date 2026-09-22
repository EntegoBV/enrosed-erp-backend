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
    @DisplayName("het pakboncontract is compleet in alle negen klanttalen")
    void packingSlipContractIsCompleteInAllNineLanguages() {
        assertEquals(9, Language.values().length,
                "pas deze expliciete taalcontracttest aan wanneer een klanttaal wordt toegevoegd");
        Set<String> packingSlipKeys = Set.of(
                "packingSlip", "deliveryAddress", "pieces", "looseCartons",
                "notOnPallet", "contents", "loadCheck", "loadedByDate",
                "receivedByDate", "height", "quantity", "unitsPerCarton",
                "salesDisplayUnits", "salesDisplaysPerCarton", "salesDisplaysPerCartonCount",
                "salesDisplaysPerCartonUnits");

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
                                            "mailIntroUpdated", "unitsPerCarton",
                                            "salesUnitsPerDisplay", "salesTotalUnits",
                                            "salesLooseUnits", "salesPricePerDisplayOf",
                                            "salesDisplaysPerCartonCount", "salesDisplaysPerCartonUnits"}) {
                assertTrue(text.get(key).contains("%s"),
                        language + " mist de invulplek in " + key + ": " + text.get(key));
            }
        }
    }

    @Test
    @DisplayName("eenheidszinnen dragen precies één invulplek, zodat ze elke eenheid kunnen dragen")
    void unitPhrasesTakeExactlyOneCount() {
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            for (String key : new String[] {"unitsPerCarton", "salesUnitsPerDisplay",
                                            "salesTotalUnits", "salesLooseUnits",
                                            "salesPricePerDisplayOf", "salesDisplaysPerCartonCount"}) {
                String phrase = text.get(key);
                assertEquals(1, phrase.split("%s", -1).length - 1, language + " " + key + ": " + phrase);
                String filled = phrase.formatted(UnitNames.count("bowl", 8, language));
                assertTrue(filled.contains(UnitNames.count("bowl", 8, language)), language + " " + key);
            }
        }
        assertEquals("16 bowls per doos",
                DocumentText.of(Language.NL).get("unitsPerCarton").formatted(UnitNames.count("bowl", 16, Language.NL)));
        assertEquals("5 displays per doos (40 bowls)",
                DocumentText.of(Language.NL).get("salesDisplaysPerCartonUnits")
                        .formatted("5", UnitNames.count("bowl", 40, Language.NL)));
        assertEquals("per display van 8 bowls",
                DocumentText.of(Language.NL).get("salesPricePerDisplayOf").formatted(UnitNames.count("bowl", 8, Language.NL)));
    }

    @Test
    @DisplayName("een doos met displays telt displays en stuks met hetzelfde doos-woord als de stuks")
    void displayCartonPhraseCountsDisplaysThenUnitsWithTheCartonNounOfThePieces() {
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            String phrase = text.get("salesDisplaysPerCartonUnits");
            assertEquals(2, phrase.split("%s", -1).length - 1, language + ": " + phrase);
            String bowls = UnitNames.count("bowl", 40, language);
            String filled = phrase.formatted("5", bowls);
            assertTrue(filled.contains("5") && filled.contains(bowls), language + ": " + filled);
            assertTrue(filled.startsWith(text.get("salesDisplaysPerCartonCount").formatted("5")),
                    language + ": both display phrases read the same");
            /* The carton noun of "40 bowls per doos" also carries the display count. */
            String carton = text.get("unitsPerCarton").replace("%s", "").strip();
            assertTrue(filled.contains(carton), language + ": " + filled + " / " + carton);
        }
    }

    @Test
    void advanceAgreementCopyIsCompleteAndFormatsTheFrozenProfitShareInEveryLanguage() {
        for (Language language : Language.values()) {
            var text = DocumentText.of(language);
            for (String key : Set.of("advanceAgreementTitle", "advanceAgreementIntro", "advanceAgreementExclVat",
                    "advanceAgreementSettlement", "advanceAgreementSettlementUnspecified", "advanceInstalment",
                    "advancePercentage", "advanceAmount", "advanceFinalPending", "advanceAgreementMailIntro", "advanceAgreementPriority"))
                assertTrue(text.containsKey(key) && !text.get(key).isBlank(), language + " missing " + key);
            assertTrue(text.get("advanceAgreementSettlement").formatted("50").contains("50%"));
            assertEquals(text.get("quote"), text.get("advanceQuote"), "Neutral quote title in " + language);
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
