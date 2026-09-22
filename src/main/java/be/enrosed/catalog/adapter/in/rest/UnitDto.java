package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.shared.Language;
import be.enrosed.shared.UnitNames;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one piece is called, in one language, for clients that print it
 * themselves: the customer portal, the website and the public quote page.
 *
 * Every plural form travels along so a client can pick the right one with
 * its own plural rules ({@code Intl.PluralRules}) instead of guessing; few
 * and many only differ from other in Polish. {@code short} is the compact
 * table form, {@code per} the complete phrase ("per bowl", "za miseczkę").
 */
public record UnitDto(
        String key,
        String one,
        String few,
        String many,
        String other,
        @JsonProperty("short") String shortForm,
        String per) {

    public static UnitDto of(String unitKey, Language language) {
        UnitNames.Localized unit = UnitNames.in(unitKey, language);
        return new UnitDto(unit.key(), unit.one(), unit.few(), unit.many(), unit.other(),
                unit.shortForm(), unit.per());
    }

    /** One entry of the ERP pick-list; the ERP works in Dutch. */
    public record Name(String key, String one, String other, String per,
                       @JsonProperty("short") String shortForm) {
        public static Name of(String unitKey) {
            UnitNames.Localized unit = UnitNames.in(unitKey, Language.NL);
            return new Name(unit.key(), unit.one(), unit.other(), unit.per(), unit.shortForm());
        }
    }
}
