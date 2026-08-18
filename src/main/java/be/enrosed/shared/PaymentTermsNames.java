package be.enrosed.shared;

import java.util.Map;

/**
 * Standard payment terms, translated once for everyone.
 *
 * Same idea as {@link ColourNames}: the handful of terms every order uses
 * ("50% voorschot / 50% bij levering") should not read as Dutch on a Polish
 * quote. Terms typed outside the list pass through untouched — better shown
 * as-is than replaced by a wrong guess.
 *
 * Keys are the Dutch phrases as the pick-list stores them.
 */
public final class PaymentTermsNames {

    private PaymentTermsNames() {}

    /** The pick-list, in the order the screen shows it. */
    public static final java.util.List<String> STANDARD = java.util.List.of(
            "Vooruitbetaling",
            "50% voorschot / 50% bij levering",
            "Bij levering",
            "14 dagen netto",
            "30 dagen netto",
            "60 dagen netto");

    private static final Map<String, Map<Language, String>> NAMES = Map.ofEntries(
            entry("Vooruitbetaling",
                    "Paiement anticipé", "Payment in advance", "Vorkasse",
                    "Pago anticipado", "Przedpłata", "Pré-pagamento", "Peşin ödeme"),
            entry("50% voorschot / 50% bij levering",
                    "50% d'acompte, 50% à la livraison", "50% deposit, 50% on delivery",
                    "50% Anzahlung, 50% bei Lieferung", "50% de anticipo, 50% a la entrega",
                    "50% zaliczki, 50% przy dostawie", "50% de sinal, 50% na entrega",
                    "%50 avans, %50 teslimatta"),
            entry("Bij levering",
                    "À la livraison", "On delivery", "Bei Lieferung",
                    "A la entrega", "Przy dostawie", "Na entrega", "Teslimatta"),
            entry("14 dagen netto",
                    "14 jours net", "Net 14 days", "14 Tage netto",
                    "14 días neto", "14 dni netto", "14 dias líquidos", "14 gün net"),
            entry("30 dagen netto",
                    "30 jours net", "Net 30 days", "30 Tage netto",
                    "30 días neto", "30 dni netto", "30 dias líquidos", "30 gün net"),
            /* Legacy spelling that existing customer records carry. */
            entry("30 dagen",
                    "30 jours net", "Net 30 days", "30 Tage netto",
                    "30 días neto", "30 dni netto", "30 dias líquidos", "30 gün net"),
            entry("60 dagen netto",
                    "60 jours net", "Net 60 days", "60 Tage netto",
                    "60 días neto", "60 dni netto", "60 dias líquidos", "60 gün net"));

    /** The term in the given language; unknown terms come back unchanged. */
    public static String translate(String dutchTerm, Language language) {
        if (dutchTerm == null || dutchTerm.isBlank() || language == Language.NL) {
            return dutchTerm;
        }
        Map<Language, String> names = NAMES.get(dutchTerm.trim());
        return names == null ? dutchTerm : names.getOrDefault(language, dutchTerm);
    }

    private static Map.Entry<String, Map<Language, String>> entry(
            String nl, String fr, String en, String de, String es, String pl, String pt, String tr) {
        return Map.entry(nl, Map.of(
                Language.FR, fr, Language.EN, en, Language.DE, de, Language.ES, es,
                Language.PL, pl, Language.PT, pt, Language.TR, tr));
    }
}
