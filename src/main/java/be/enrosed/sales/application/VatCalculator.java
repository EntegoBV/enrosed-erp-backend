package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.VatTreatment;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Bepaalt welk BTW-regime op een levering van toepassing is.
 *
 * De regel is eenvoudig zolang je hem als beslisboom leest: eigen land is
 * binnenland, andere EU-lidstaat met een geldig BTW-nummer van de klant is
 * vrijgesteld, buiten de EU is uitvoer. Zonder BTW-nummer blijft het belast,
 * want dan is het geen intracommunautaire levering.
 *
 * Het BTW-nummer wordt hier alleen op vorm gecontroleerd, niet bij VIES
 * opgevraagd. Voor een echte vrijstelling moet je het nummer geldig laten
 * verklaren - dat is een aparte koppeling die hier nog niet in zit.
 */
@ApplicationScoped
public class VatCalculator {

    @ConfigProperty(name = "enrosed.company.country", defaultValue = "BE")
    String homeCountry;

    public record Result(VatTreatment treatment, BigDecimal ratePct, String reason) {}

    public Result determine(Country country, Customer customer) {
        if (country == null) {
            return new Result(VatTreatment.BINNENLAND, BigDecimal.ZERO, "Geen land gekozen");
        }

        String code = country.code() == null ? "" : country.code().toUpperCase(Locale.ROOT);

        if (code.equals(homeCountry.toUpperCase(Locale.ROOT))) {
            return new Result(VatTreatment.BINNENLAND, nz(country.vatRatePct()),
                    "Binnenlandse levering, Belgisch tarief");
        }

        if (!country.euMember()) {
            return new Result(VatTreatment.UITVOER, BigDecimal.ZERO,
                    "Levering buiten de EU: vrijgesteld als uitvoer");
        }

        if (hasVatNumber(customer)) {
            return new Result(VatTreatment.INTRACOMMUNAUTAIR, BigDecimal.ZERO,
                    "EU-klant met BTW-nummer: heffing verlegd naar de afnemer");
        }

        return new Result(VatTreatment.EU_ZONDER_BTW_NUMMER, nz(country.vatRatePct()),
                "EU-klant zonder BTW-nummer: geen vrijstelling, controleer de "
                        + "afstandsverkoopregels met je boekhouder");
    }

    /**
     * Vormcontrole op het BTW-nummer: twee letters landcode gevolgd door
     * cijfers of letters. Geen VIES-controle, dus geen bewijs van geldigheid.
     */
    public boolean hasVatNumber(Customer customer) {
        if (customer == null) return false;
        String vat = customer.vatNumber() == null ? "" : customer.vatNumber().replaceAll("[\\s.]", "");
        return vat.matches("[A-Za-z]{2}[0-9A-Za-z]{2,12}");
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
