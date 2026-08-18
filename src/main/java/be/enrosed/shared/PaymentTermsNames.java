package be.enrosed.shared;

import java.util.List;
import java.util.Map;

/**
 * Standard payment terms, translated once for everyone.
 *
 * Same idea as {@link ColourNames}: the handful of terms every order uses
 * ("50% voorschot / 50% bij levering") should not read as Dutch on a Polish
 * quote. Terms typed outside the list pass through untouched — better shown
 * as-is than replaced by a wrong guess.
 *
 * The translations live in i18n/payment-terms.csv; keys are the Dutch
 * phrases as the pick-list stores them. The file also carries the legacy
 * "30 dagen" spelling that existing customer records still use.
 */
public final class PaymentTermsNames {

    private PaymentTermsNames() {}

    /** The pick-list, in the order the screen shows it. */
    public static final List<String> STANDARD = List.of(
            "Vooruitbetaling",
            "50% voorschot / 50% bij levering",
            "Bij levering",
            "14 dagen netto",
            "30 dagen netto",
            "60 dagen netto");

    private static final Map<Language, Map<String, String>> NAMES =
            TranslationCsv.load("/i18n/payment-terms.csv");

    /** The term in the given language; unknown terms come back unchanged. */
    public static String translate(String dutchTerm, Language language) {
        if (dutchTerm == null || dutchTerm.isBlank() || language == Language.NL) {
            return dutchTerm;
        }
        String term = NAMES.get(language).get(dutchTerm.trim());
        return term == null ? dutchTerm : term;
    }
}
