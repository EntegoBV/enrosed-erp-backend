package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;

import java.math.BigDecimal;
import java.util.List;

/**
 * The complete country policy shown under Landen &amp; vracht.
 *
 * <p>The fallback tariff remains editable after the one-time rollout. Carrier
 * tables can still replace it for an actual shipment. Transit is always in
 * working days and administration starts at zero.</p>
 */
public final class CountryDefaults {

    private static final BigDecimal DEFAULT_MINIMUM = money("1500");
    private static final BigDecimal FREIGHT_PER_PALLET = money("95");
    private static final BigDecimal MINIMUM_FREIGHT = money("250");
    private static final BigDecimal NO_ADMINISTRATION = money("0");

    private static final List<Country> ALL = List.of(
            country("BE", "België", "600", "21", 3, true),
            country("NL", "Nederland", "750", "21", 3, true),
            country("DE", "Duitsland", "1250", "19", 3, true),
            country("FR", "Frankrijk", "1250", "20", 3, true),
            country("LU", "Luxemburg", "1000", "17", 3, true),
            country("GB", "Verenigd Koninkrijk", null, "0", 5, false),
            country("IE", "Ierland", null, "23", 5, true),
            country("ES", "Spanje", "1500", "21", 5, true),
            country("PT", "Portugal", null, "23", 5, true),
            country("IT", "Italië", "1500", "22", 5, true),
            country("AT", "Oostenrijk", null, "20", 5, true),
            country("CH", "Zwitserland", null, "0", 5, false),
            country("DK", "Denemarken", null, "25", 5, true),
            country("SE", "Zweden", null, "25", 6, true),
            country("NO", "Noorwegen", null, "0", 6, false),
            country("FI", "Finland", null, "25.5", 6, true),
            country("PL", "Polen", null, "23", 5, true),
            country("CZ", "Tsjechië", null, "21", 5, true),
            country("SK", "Slovakije", null, "23", 5, true),
            country("HU", "Hongarije", null, "27", 5, true),
            country("RO", "Roemenië", null, "21", 6, true),
            country("BG", "Bulgarije", null, "20", 6, true),
            country("GR", "Griekenland", null, "24", 6, true),
            country("HR", "Kroatië", null, "25", 5, true),
            country("SI", "Slovenië", null, "22", 5, true),
            country("EE", "Estland", null, "24", 6, true),
            country("LV", "Letland", null, "21", 6, true),
            country("LT", "Litouwen", null, "21", 6, true),
            country("CY", "Cyprus", null, "19", 7, true),
            country("MT", "Malta", null, "18", 7, true),
            country("TR", "Turkije", null, "0", 6, false),
            country("UA", "Oekraïne", null, "0", 7, false),
            country("RS", "Servië", null, "0", 6, false),
            country("CN", "China", null, "0", 7, false),
            country("HK", "Hongkong", null, "0", 7, false),
            country("VN", "Vietnam", null, "0", 7, false),
            country("TH", "Thailand", null, "0", 7, false),
            country("IN", "India", null, "0", 7, false),
            country("ID", "Indonesië", null, "0", 7, false),
            country("MY", "Maleisië", null, "0", 7, false),
            country("KR", "Zuid-Korea", null, "0", 7, false),
            country("JP", "Japan", null, "0", 7, false),
            country("AE", "Verenigde Arabische Emiraten", null, "0", 7, false),
            country("US", "Verenigde Staten", null, "0", 7, false),
            country("CA", "Canada", null, "0", 7, false),
            country("EC", "Ecuador", null, "0", 7, false),
            country("CO", "Colombia", null, "0", 7, false),
            country("KE", "Kenia", null, "0", 7, false),
            country("ET", "Ethiopië", null, "0", 7, false),
            country("MA", "Marokko", null, "0", 7, false),
            country("EG", "Egypte", null, "0", 7, false),
            country("ZA", "Zuid-Afrika", null, "0", 7, false)
    );

    private CountryDefaults() {}

    public static List<Country> all() {
        return ALL;
    }

    /**
     * Applies the new commercial policy while retaining an existing manually
     * maintained fallback freight tariff.
     */
    public static Country mergeForRollout(Country current, Country policy) {
        if (current == null) return policy;
        return new Country(
                policy.code(), policy.name(), policy.minOrderValue(),
                current.freightPerPallet() == null ? policy.freightPerPallet() : current.freightPerPallet(),
                current.minFreight() == null ? policy.minFreight() : current.minFreight(),
                NO_ADMINISTRATION, policy.vatRatePct(), policy.transitDays(), policy.euMember());
    }

    private static Country country(String code, String name, String minimum,
                                   String vat, int transitDays, boolean euMember) {
        return new Country(code, name,
                minimum == null ? DEFAULT_MINIMUM : money(minimum),
                FREIGHT_PER_PALLET, MINIMUM_FREIGHT, NO_ADMINISTRATION,
                money(vat), transitDays, euMember);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
