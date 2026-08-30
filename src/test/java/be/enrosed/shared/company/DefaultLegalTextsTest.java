package be.enrosed.shared.company;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLegalTextsTest {

    @Test
    void tradeTermsKeepPrepaymentAsTheOnlyDefaultAndPreserveMandatoryRemedies() {
        for (String terms : List.of(
                DefaultLegalTexts.TERMS_NL,
                DefaultLegalTexts.TERMS_EN,
                DefaultLegalTexts.TERMS_FR,
                DefaultLegalTexts.TERMS_DE)) {
            assertTrue(terms.contains("40"), "statutory recovery compensation must be present");
            assertTrue(terms.contains("48"), "visible transport claims must be separated");
            assertTrue(terms.contains("90") || terms.contains("ninety") || terms.contains("negentig") || terms.contains("nonante")
                    || terms.contains("neunzig"), "force-majeure long stop must be present");
            assertFalse(terms.contains("10%"));
            assertFalse(terms.contains("10 %"));
            assertFalse(terms.contains("125"));
        }

        assertFalse(DefaultLegalTexts.TERMS_NL.contains("is niets vermeld, dan binnen dertig dagen"));
        assertFalse(DefaultLegalTexts.TERMS_EN.contains("where none are stated, within thirty days"));
        assertFalse(DefaultLegalTexts.TERMS_FR.contains("à défaut de mention, dans les trente jours"));
        assertFalse(DefaultLegalTexts.TERMS_DE.contains("fehlt eine Angabe, innerhalb von dreißig Tagen"));

        assertTrue(DefaultLegalTexts.TERMS_NL.contains("standaard")
                || DefaultLegalTexts.TERMS_NL.contains("volledige betaling"));
        assertTrue(DefaultLegalTexts.TERMS_EN.contains("full payment in cleared funds"));
        assertTrue(DefaultLegalTexts.TERMS_FR.contains("paiement intégral"));
        assertTrue(DefaultLegalTexts.TERMS_DE.contains("vollständige Zahlung"));
        assertTrue(DefaultLegalTexts.TERMS_NL.contains("schriftelijke retourautorisatie"));
        assertTrue(DefaultLegalTexts.TERMS_EN.contains("written return authorisation"));
        assertTrue(DefaultLegalTexts.TERMS_FR.contains("autorisation écrite de retour"));
        assertTrue(DefaultLegalTexts.TERMS_DE.contains("schriftliche Rücksendeautorisierung"));
    }

    @Test
    void termsUseTheVerifiedCourtAndExcludeCisg() {
        assertTrue(DefaultLegalTexts.TERMS_NL.contains("ondernemingsrechtbank Antwerpen, afdeling Turnhout"));
        assertTrue(DefaultLegalTexts.TERMS_EN.contains("Enterprise Court of Antwerp, Turnhout division"));
        assertTrue(DefaultLegalTexts.TERMS_FR.contains("tribunal de l'entreprise d'Anvers, division Turnhout"));
        assertTrue(DefaultLegalTexts.TERMS_DE.contains("Unternehmensgerichts Antwerpen, Abteilung Turnhout"));
        assertTrue(DefaultLegalTexts.TERMS_NL.contains("Weens Koopverdrag"));
        assertTrue(DefaultLegalTexts.TERMS_EN.contains("Vienna Sales Convention"));
    }
}
