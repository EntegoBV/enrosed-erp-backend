package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Builds the complete administrator family projection for every command endpoint. */
@ApplicationScoped
public class ProductFamilyDtoFactory {
    private final CanonicalCatalogDaos.ExternalIdentifiers identifiers;
    private final CanonicalCatalogDaos.PriceObservations prices;
    private final CanonicalCatalogDaos.Provenance provenance;
    private final CanonicalCatalogDaos.ImportConflicts conflicts;
    private final CatalogDaos.Products products;
    private final ObjectMapper json;

    @Inject
    be.enrosed.catalog.application.PublicLocalizationCompletenessService localization;

    public ProductFamilyDtoFactory(
            CanonicalCatalogDaos.ExternalIdentifiers identifiers,
            CanonicalCatalogDaos.PriceObservations prices,
            CanonicalCatalogDaos.Provenance provenance,
            CanonicalCatalogDaos.ImportConflicts conflicts,
            CatalogDaos.Products products,
            ObjectMapper json) {
        this.identifiers = identifiers;
        this.prices = prices;
        this.provenance = provenance;
        this.conflicts = conflicts;
        this.products = products;
        this.json = json;
    }

    public ProductFamilyDto from(ProductFamilyEntity family) {
        var members = products.list("familyId = ?1 order by variantPosition, id", family.id);
        ProductFamilyDto result = ProductFamilyDto.from(
                family,
                identifiers.list("ownerType = ?1 and familyId = ?2", "FAMILY", family.id),
                prices.list("familyId", family.id),
                provenance.list("ownerType = ?1 and familyId = ?2", "FAMILY", family.id),
                conflicts.list("familyKey", family.familyKey),
                members,
                json);
        return localization == null ? result
                : result.withAdditionalPublicationIssues(localization.issues(family, members));
    }
}
