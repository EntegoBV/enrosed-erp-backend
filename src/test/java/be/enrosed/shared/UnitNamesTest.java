package be.enrosed.shared;

import be.enrosed.catalog.application.PublicContentSeedLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unit dictionary. Like the document texts, a gap is not an error but
 * an empty spot on a quote that is already with a customer, and "stuk" must
 * keep printing exactly what the documents printed before units existed.
 */
class UnitNamesTest {

    @Test
    @DisplayName("de lijst staat vast en begint met stuk")
    void pickListOrderIsTheFileOrder() {
        assertEquals(List.of("stuk", "bowl", "stolp", "box", "roos", "hart", "beer"), UnitNames.KEYS);
        assertEquals("stuk", UnitNames.DEFAULT);
    }

    @Test
    @DisplayName("elke eenheid heeft elke vorm in alle negen talen")
    void everyUnitHasEveryFormInEveryLanguage() {
        assertEquals(9, Language.values().length);
        for (String unit : UnitNames.KEYS) {
            for (Language language : Language.values()) {
                UnitNames.Localized localized = UnitNames.in(unit, language);
                assertEquals(unit, localized.key());
                for (String form : List.of(localized.one(), localized.few(), localized.many(),
                        localized.other(), localized.shortForm(), localized.per())) {
                    assertTrue(form != null && !form.isBlank(), unit + " " + language);
                    assertFalse(form.contains("%") || form.contains("{") || form.contains(";"),
                            unit + " " + language + ": " + form);
                    assertEquals(form.strip(), form, unit + " " + language + " has stray spaces");
                }
                if (language != Language.PL) {
                    assertEquals(localized.other(), localized.few(), unit + " " + language);
                    assertEquals(localized.other(), localized.many(), unit + " " + language);
                }
            }
        }
    }

    @Test
    @DisplayName("stuk leest precies zoals de documenten het altijd al schreven")
    void pieceReproducesTheWordingDocumentsAlreadyUsed() {
        List<String> per = List.of("per stuk", "par pièce", "per piece", "pro Stück", "por unidad",
                "za sztukę", "por unidade", "adet başına", "ανά τεμάχιο");
        List<String> shortForms = List.of("st.", "pcs", "pcs", "Stk.", "uds.", "szt.", "un.", "adet", "τεμ.");
        Language[] languages = Language.values();
        for (int index = 0; index < languages.length; index++) {
            Language language = languages[index];
            Map<String, String> text = DocumentText.of(language);
            Map<String, String> catalogue = PublicContentSeedLoader.catalogSeedValues(language);
            assertEquals(per.get(index), UnitNames.per("stuk", language), language.name());
            assertEquals(shortForms.get(index), UnitNames.shortForm("stuk", language), language.name());
            assertEquals(text.get("salesPricePerPiece"), UnitNames.per("stuk", language), language.name());
            assertEquals(text.get("portalPerPiece"), UnitNames.per("stuk", language), language.name());
            assertEquals(catalogue.get("catalog.simple.perpiece"), UnitNames.per("stuk", language));
            assertEquals(catalogue.get("catalog.common.pieces"), UnitNames.shortForm("stuk", language));
            /* The new neutral "Totaal: %s" plus the unit reads like the old piece-only sentence. */
            assertEquals(text.get("salesTotalInnerPieces").formatted("48"),
                    text.get("salesTotalUnits").formatted(UnitNames.count("stuk", 48, language)),
                    language.name());
        }
        assertEquals("Oorspronkelijk aangevraagd: 48 stuks",
                DocumentText.of(Language.NL).get("salesRequestedUnits")
                        .formatted(UnitNames.count("stuk", 48, Language.NL)));
    }

    @Test
    @DisplayName("een onbekende of lege eenheid wordt stuk, nooit een ruwe sleutel")
    void unknownOrBlankFallsBackToPiece() {
        assertEquals("stuk", UnitNames.normalize(null));
        assertEquals("stuk", UnitNames.normalize("  "));
        assertEquals("stuk", UnitNames.normalize("teddy"));
        assertEquals("bowl", UnitNames.normalize(" Bowl "));
        assertTrue(UnitNames.isKnown("STOLP"));
        assertFalse(UnitNames.isKnown("bowls"));
        assertFalse(UnitNames.isKnown(null));
        assertEquals("per stuk", UnitNames.per("teddy", Language.NL));
        assertEquals("stuk", UnitNames.in("teddy", Language.EN).key());
    }

    @Test
    @DisplayName("aantallen volgen de meervoudsregels en het getalformaat van de taal")
    void countsFollowPluralRulesAndNumberFormat() {
        assertEquals("1 bowl", UnitNames.count("bowl", 1, Language.NL));
        assertEquals("16 bowls", UnitNames.count("bowl", 16, Language.NL));
        assertEquals("1.200 bowls", UnitNames.count("bowl", 1200, Language.NL));
        assertEquals("1,200 bowls", UnitNames.count("bowl", 1200, Language.EN));
        assertEquals("1.200 Schalen", UnitNames.count("bowl", 1200, Language.DE));
        assertEquals("8 stolpen", UnitNames.count("stolp", 8, Language.NL));
        assertEquals("1 Stück", UnitNames.count("stuk", 1, Language.DE));
        assertEquals("16 kase", UnitNames.count("bowl", 16, Language.TR));

        /* French counts zero with the singular. */
        assertEquals("0 bol", UnitNames.count("bowl", 0, Language.FR));
        assertEquals("2 bols", UnitNames.count("bowl", 2, Language.FR));

        /* Polish: one, few (2-4, 22-24, not 12-14) and many. */
        assertEquals("1 miseczka", UnitNames.count("bowl", 1, Language.PL));
        assertEquals("2 miseczki", UnitNames.count("bowl", 2, Language.PL));
        assertEquals("4 miseczki", UnitNames.count("bowl", 4, Language.PL));
        assertEquals("5 miseczek", UnitNames.count("bowl", 5, Language.PL));
        assertEquals("12 miseczek", UnitNames.count("bowl", 12, Language.PL));
        assertEquals("14 miseczek", UnitNames.count("bowl", 14, Language.PL));
        assertEquals("22 miseczki", UnitNames.count("bowl", 22, Language.PL));
        assertEquals("112 miseczek", UnitNames.count("bowl", 112, Language.PL));
        assertEquals("0 sztuk", UnitNames.count("stuk", 0, Language.PL));
        assertEquals("3 misie", UnitNames.count("beer", 3, Language.PL));
        assertEquals("za misia", UnitNames.per("beer", Language.PL));
        assertEquals("za różę", UnitNames.per("roos", Language.PL));
    }

    @Test
    @DisplayName("de korte vorm gebruikt een afkorting alleen waar de eenheid er een heeft")
    void shortCountUsesAnAbbreviationOnlyWhereOneExists() {
        assertEquals("16 st.", UnitNames.shortCount("stuk", 16, Language.NL));
        assertEquals("16 pcs", UnitNames.shortCount("stuk", 16, Language.EN));
        assertEquals("16 adet", UnitNames.shortCount("stuk", 16, Language.TR));
        assertEquals("16 bowls", UnitNames.shortCount("bowl", 16, Language.NL));
        assertEquals("1 bowl", UnitNames.shortCount("bowl", 1, Language.NL));
        assertEquals("2 miseczki", UnitNames.shortCount("bowl", 2, Language.PL));
    }
}
