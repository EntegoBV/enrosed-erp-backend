package be.enrosed.shared;

import java.util.Locale;

/**
 * De talen waarin wij naar een klant communiceren.
 *
 * Een Duitse of Franse klant een Nederlandstalige offerte sturen leest als
 * slordigheid, ook als het bedrag klopt. De taal hangt daarom aan de klant en
 * niet aan ons scherm: wij blijven intern in het Nederlands werken terwijl het
 * document in zijn taal vertrekt.
 *
 * Bewust een korte lijst in plaats van elke locale die Java kent. Elke taal die
 * erbij komt moet ook echt vertaald worden - een halfvertaalde offerte is
 * slechter dan een Engelse.
 */
public enum Language {

    NL("nl", "Nederlands", "nl-BE"),
    FR("fr", "Frans", "fr-BE"),
    EN("en", "Engels", "en-GB"),
    DE("de", "Duits", "de-DE"),
    ES("es", "Spaans", "es-ES"),
    PL("pl", "Pools", "pl-PL"),
    PT("pt", "Portugees", "pt-PT"),
    TR("tr", "Turks", "tr-TR");

    private final String code;
    private final String label;
    private final String localeTag;

    Language(String code, String label, String localeTag) {
        this.code = code;
        this.label = label;
        this.localeTag = localeTag;
    }

    /** Tweeletterige code zoals ze in de database en de CSV staat. */
    public String code() {
        return code;
    }

    /** Hoe wij de taal intern noemen, in het Nederlands. */
    public String label() {
        return label;
    }

    /**
     * Locale voor getallen en datums.
     *
     * Let op: dit stuurt alleen de opmaak van bedragen. Duizendtallen met een
     * punt en decimalen met een komma zijn in het grootste deel van Europa
     * gelijk; een Engelse klant krijgt het omgekeerd, zoals hij het verwacht.
     */
    public Locale locale() {
        return Locale.forLanguageTag(localeTag);
    }

    /** Onbekend of leeg valt terug op Nederlands. */
    public static Language of(String code) {
        if (code == null || code.isBlank()) return NL;
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        for (Language language : values()) {
            if (language.code.equals(wanted) || language.name().equalsIgnoreCase(wanted)) {
                return language;
            }
        }
        return NL;
    }
}
