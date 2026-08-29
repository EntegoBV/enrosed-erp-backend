package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.application.CountryDefaults;
import be.enrosed.sales.application.CountryService;
import be.enrosed.sales.domain.Country;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static be.enrosed.sales.adapter.out.persistence.SalesEntities.CountryPolicyVersionEntity;

/** Installs the complete 2026 country policy once without overwriting later edits. */
@ApplicationScoped
public class CountryDefaultsSeed {

    static final String VERSION = "2026-08-29-all-countries-business-days-v1";
    private static final Logger LOG = Logger.getLogger(CountryDefaultsSeed.class);

    private final CountryService countries;
    private final SalesDaos.CountryPolicyVersions versions;

    public CountryDefaultsSeed(CountryService countries, SalesDaos.CountryPolicyVersions versions) {
        this.countries = countries;
        this.versions = versions;
    }

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        if (versions.findById(VERSION) != null) return;

        Map<String, Country> current = countries.list().stream()
                .collect(Collectors.toMap(Country::code, Function.identity()));
        int created = 0;
        int updated = 0;
        for (Country policy : CountryDefaults.all()) {
            Country existing = current.get(policy.code());
            Country merged = CountryDefaults.mergeForRollout(existing, policy);
            countries.save(merged);
            if (existing == null) created++; else updated++;
        }

        CountryPolicyVersionEntity applied = new CountryPolicyVersionEntity();
        applied.version = VERSION;
        applied.appliedAt = Instant.now();
        versions.persist(applied);
        LOG.infof("Landenbeleid geladen: %d toegevoegd, %d bijgewerkt", created, updated);
    }
}
