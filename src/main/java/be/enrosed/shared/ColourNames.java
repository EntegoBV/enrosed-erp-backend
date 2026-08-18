package be.enrosed.shared;

import java.util.Locale;
import java.util.Map;

/**
 * Standard product colours, translated once for everyone.
 *
 * Colour is the one product attribute that appears on every quote and in
 * every catalogue, and it is always the same twenty words. Translating
 * "Rood" per product through the CSV file would mean typing "Rouge" forty
 * times and misspelling it twice. A product-specific translation still wins
 * when one exists — "Vintage roze" is not in this list and never will be.
 *
 * Keys are the Dutch names as the colour pick-list stores them.
 */
public final class ColourNames {

    private ColourNames() {}

    /** The pick-list, in the order the screen shows it. */
    public static final java.util.List<String> STANDARD = java.util.List.of(
            "Rood", "Roze", "Fuchsia", "Bordeaux", "Wit", "Ivoor", "Champagne",
            "Geel", "Oranje", "Groen", "Blauw", "Paars", "Lila",
            "Zwart", "Grijs", "Zilver", "Goud", "Gemengd");

    private static final Map<String, Map<Language, String>> NAMES = Map.ofEntries(
            entry("Rood", "Rouge", "Red", "Rot", "Rojo", "Czerwony", "Vermelho", "Kırmızı"),
            entry("Roze", "Rose", "Pink", "Rosa", "Rosa", "Różowy", "Cor-de-rosa", "Pembe"),
            entry("Fuchsia", "Fuchsia", "Fuchsia", "Fuchsia", "Fucsia", "Fuksja", "Fúcsia", "Fuşya"),
            entry("Bordeaux", "Bordeaux", "Burgundy", "Bordeauxrot", "Burdeos", "Bordowy", "Bordô", "Bordo"),
            entry("Wit", "Blanc", "White", "Weiß", "Blanco", "Biały", "Branco", "Beyaz"),
            entry("Ivoor", "Ivoire", "Ivory", "Elfenbein", "Marfil", "Kość słoniowa", "Marfim", "Fildişi"),
            entry("Champagne", "Champagne", "Champagne", "Champagner", "Champán", "Szampański", "Champanhe", "Şampanya"),
            entry("Geel", "Jaune", "Yellow", "Gelb", "Amarillo", "Żółty", "Amarelo", "Sarı"),
            entry("Oranje", "Orange", "Orange", "Orange", "Naranja", "Pomarańczowy", "Laranja", "Turuncu"),
            entry("Groen", "Vert", "Green", "Grün", "Verde", "Zielony", "Verde", "Yeşil"),
            entry("Blauw", "Bleu", "Blue", "Blau", "Azul", "Niebieski", "Azul", "Mavi"),
            entry("Paars", "Violet", "Purple", "Violett", "Morado", "Fioletowy", "Roxo", "Mor"),
            entry("Lila", "Lilas", "Lilac", "Flieder", "Lila", "Liliowy", "Lilás", "Lila"),
            entry("Zwart", "Noir", "Black", "Schwarz", "Negro", "Czarny", "Preto", "Siyah"),
            entry("Grijs", "Gris", "Grey", "Grau", "Gris", "Szary", "Cinzento", "Gri"),
            entry("Zilver", "Argent", "Silver", "Silber", "Plata", "Srebrny", "Prateado", "Gümüş"),
            entry("Goud", "Or", "Gold", "Gold", "Dorado", "Złoty", "Dourado", "Altın"),
            entry("Gemengd", "Assorti", "Mixed", "Gemischt", "Surtido", "Mieszany", "Sortido", "Karışık"));

    /**
     * The colour in the given language.
     *
     * Unknown colours come back unchanged: a colour someone typed by hand is
     * better shown as-is than replaced by a wrong guess.
     */
    public static String translate(String dutchName, Language language) {
        if (dutchName == null || dutchName.isBlank() || language == Language.NL) {
            return dutchName;
        }
        Map<Language, String> names = NAMES.get(normalise(dutchName));
        return names == null ? dutchName : names.getOrDefault(language, dutchName);
    }

    private static String normalise(String name) {
        String trimmed = name.trim();
        return trimmed.isEmpty() ? trimmed
                : trimmed.substring(0, 1).toUpperCase(Locale.ROOT)
                    + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }

    private static Map.Entry<String, Map<Language, String>> entry(
            String nl, String fr, String en, String de, String es, String pl, String pt, String tr) {
        return Map.entry(nl, Map.of(
                Language.FR, fr, Language.EN, en, Language.DE, de, Language.ES, es,
                Language.PL, pl, Language.PT, pt, Language.TR, tr));
    }
}
