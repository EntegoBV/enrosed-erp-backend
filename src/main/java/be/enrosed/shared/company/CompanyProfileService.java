package be.enrosed.shared.company;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Beheert onze bedrijfsgegevens.
 *
 * Er is altijd precies één profiel; ontbreekt het, dan komt er een leeg terug
 * in plaats van null. Zo hoeft geen enkele documentsjabloon zich af te vragen
 * of het bestaat.
 */
@ApplicationScoped
public class CompanyProfileService {

    @ApplicationScoped
    public static class Store implements PanacheRepositoryBase<CompanyProfileEntity, Long> {}

    private final Store store;

    public CompanyProfileService(Store store) {
        this.store = store;
    }

    public CompanyProfile get() {
        CompanyProfileEntity entity = store.findById(1L);
        return entity == null ? CompanyProfile.empty() : toDomain(entity);
    }

    @Transactional
    public CompanyProfile save(CompanyProfile profile) {
        CompanyProfileEntity entity = store.findById(1L);
        if (entity == null) {
            entity = new CompanyProfileEntity();
            entity.id = 1L;
            store.persist(entity);
        }
        entity.name = profile.name();
        entity.legalName = profile.legalName();
        entity.vatNumber = profile.vatNumber();
        entity.registrationNumber = profile.registrationNumber();
        entity.addressLine = profile.addressLine();
        entity.postalCode = profile.postalCode();
        entity.city = profile.city();
        entity.countryCode = profile.countryCode();
        entity.email = profile.email();
        entity.phone = profile.phone();
        entity.website = profile.website();
        entity.iban = profile.iban();
        entity.bic = profile.bic();
        entity.documentFooter = profile.documentFooter();
        entity.termsAndConditions = profile.termsAndConditions();
        entity.termsAndConditionsEn = profile.termsAndConditionsEn();
        entity.privacyPolicy = profile.privacyPolicy();
        entity.privacyPolicyEn = profile.privacyPolicyEn();
        store.flush();
        return toDomain(entity);
    }

    private static CompanyProfile toDomain(CompanyProfileEntity entity) {
        return new CompanyProfile(
                entity.name, entity.legalName, entity.vatNumber, entity.registrationNumber,
                entity.addressLine, entity.postalCode, entity.city, entity.countryCode,
                entity.email, entity.phone, entity.website,
                entity.iban, entity.bic, entity.documentFooter, entity.termsAndConditions,
                entity.termsAndConditionsEn, entity.privacyPolicy, entity.privacyPolicyEn);
    }
}
