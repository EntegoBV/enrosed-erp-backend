package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.VatTreatment;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Determines which VAT regime applies to a delivery.
 *
 * The rule is simple as long as you read it as a decision tree: own country
 * is domestic, another EU state with a valid customer VAT number is exempt,
 * outside the EU is export. Without a VAT number it stays taxed, because
 * then it is no intra-community supply.
 *
 * The VAT number is only checked on shape here, not queried at VIES. For a
 * real exemption the number must be declared valid - a separate integration
 * that is not in here yet.
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
     * Shape check on the VAT number: two letters of country code followed by
     * digits or letters. No VIES check, so no proof of validity.
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
