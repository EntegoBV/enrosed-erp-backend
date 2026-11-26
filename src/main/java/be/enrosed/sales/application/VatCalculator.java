package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesVatChoice;
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

    /** Our own country: where a service for a customer without VAT number is taxed. */
    public String homeCountry() { return homeCountry; }

    /** The customer's own standard: the fiscal representative when the record says so, else the plain rules. */
    public Result determine(Country country, Customer customer) {
        return determine(country, customer, null, null);
    }

    /**
     * @param route       the document's VAT route (SalesVatChoices.effective); null means the customer's standard
     * @param homeRatePct the Belgian rate, for a service taxed in Belgium; only read on the SERVICE route
     */
    public Result determine(Country country, Customer customer, SalesVatChoice route, BigDecimal homeRatePct) {
        if (country == null) {
            return new Result(VatTreatment.BINNENLAND, BigDecimal.ZERO, "Geen land gekozen");
        }

        String code = country.code() == null ? "" : country.code().toUpperCase(Locale.ROOT);

        /* A service has nothing to do with goods: no supply, no export, no clearance. A business
           customer in another member state accounts for the VAT himself; anyone else in the EU pays
           the Belgian rate; outside the EU it is outside the scope of Belgian VAT. */
        if (route == SalesVatChoice.SERVICE) {
            if (code.equals(homeCountry.toUpperCase(Locale.ROOT))) {
                return new Result(VatTreatment.DIENST_BINNENLAND, nz(country.vatRatePct()),
                        "Dienst in België, Belgisch tarief");
            }
            if (!country.euMember()) {
                return new Result(VatTreatment.DIENST_BUITEN_EU, BigDecimal.ZERO,
                        "Dienst voor een afnemer buiten de EU: niet onderworpen aan Belgische btw");
            }
            if (hasVatNumber(customer)) {
                return new Result(VatTreatment.DIENST_VERLEGD, BigDecimal.ZERO,
                        "Dienst voor een EU-klant met BTW-nummer: btw verlegd naar de afnemer");
            }
            return new Result(VatTreatment.DIENST_BINNENLAND, homeRatePct == null ? nz(country.vatRatePct()) : homeRatePct,
                    "Dienst voor een EU-klant zonder BTW-nummer: Belgisch tarief");
        }

        if (code.equals(homeCountry.toUpperCase(Locale.ROOT))) {
            return new Result(VatTreatment.BINNENLAND, nz(country.vatRatePct()),
                    "Binnenlandse levering, Belgisch tarief");
        }

        if (!country.euMember()) {
            return new Result(VatTreatment.UITVOER, BigDecimal.ZERO,
                    "Levering buiten de EU: vrijgesteld als uitvoer");
        }

        /* Goods cleared in the Netherlands through our limited fiscal representative are supplied
           there; the Dutch buyer with a VAT number owes the VAT himself (art. 12.3 Wet OB). */
        boolean fiscal = route == null ? customer != null && customer.fiscalRepresentative()
                : route == SalesVatChoice.FISCAL_REPRESENTATIVE;
        if (fiscal && hasVatNumber(customer)) {
            return new Result(VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER, BigDecimal.ZERO,
                    "Inklaring via onze fiscaal vertegenwoordiger: btw verlegd naar de afnemer (art. 12.3 Wet OB)");
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
