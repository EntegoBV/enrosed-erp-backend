package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Country;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;

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
        return countries.save(country);
    }

    @Transactional
    public void delete(String code) {
        get(code);
        countries.deleteByCode(code);
    }
}
