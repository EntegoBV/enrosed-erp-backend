package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Country;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.math.BigDecimal;

/** Minimum orderwaarde en palletvracht per land. */
@ApplicationScoped
public class CountryService {

    private final SalesRepositories.Countries countries;

    public CountryService(SalesRepositories.Countries countries) {
        this.countries = countries;
    }

    public List<Country> list() {
        return countries.findAll().stream().sorted(Comparator.comparing(Country::name)).toList();
    }

    public Country get(String code) {
        return countries.findByCode(code).orElseThrow(() -> new NotFoundException("Land", code));
    }

    public Country find(String code) {
        return code == null ? null : countries.findByCode(code).orElse(null);
    }

    @Transactional
    public Country save(Country country) {
        if (country == null) {
            throw new BusinessRuleException("Geen landgegevens meegestuurd");
        }
        if (country.code() == null || country.code().isBlank()
                || country.name() == null || country.name().isBlank()) {
            throw new BusinessRuleException("Landcode en naam zijn verplicht");
        }
        requireNonNegative(country.minOrderValue(), "Minimum orderwaarde");
        requireNonNegative(country.freightPerPallet(), "Vracht per pallet");
        requireNonNegative(country.minFreight(), "Minimumvracht");
        requireNonNegative(country.handling(), "Behandelingskost");
        if (country.vatRatePct() != null && (country.vatRatePct().signum() < 0
                || country.vatRatePct().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException("Btw-percentage moet tussen 0 en 100% liggen");
        }
        if (country.transitDays() < 0) {
            throw new BusinessRuleException("Transittijd kan niet negatief zijn");
        }
        return countries.save(country);
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    @Transactional
    public void delete(String code) {
        get(code);
        countries.deleteByCode(code);
    }
}
