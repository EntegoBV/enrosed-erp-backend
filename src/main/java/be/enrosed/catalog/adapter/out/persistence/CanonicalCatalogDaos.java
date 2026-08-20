package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

public final class CanonicalCatalogDaos {
    private CanonicalCatalogDaos() {}

    @ApplicationScoped public static class Families implements PanacheRepository<ProductFamilyEntity> {}
    @ApplicationScoped public static class Collections implements PanacheRepository<ProductCollectionEntity> {}
    @ApplicationScoped public static class FamilyCollections implements PanacheRepository<ProductFamilyCollectionEntity> {}
    @ApplicationScoped public static class FamilyTexts implements PanacheRepository<ProductFamilyTextEntity> {}
    @ApplicationScoped public static class FamilyPhotos implements PanacheRepository<ProductFamilyPhotoEntity> {}
    @ApplicationScoped public static class Packages implements PanacheRepository<ProductPackageEntity> {}
    @ApplicationScoped public static class DimensionObservations implements PanacheRepository<ProductDimensionObservationEntity> {}
    @ApplicationScoped public static class ExternalIdentifiers implements PanacheRepository<ProductExternalIdentifierEntity> {}
    @ApplicationScoped public static class PriceObservations implements PanacheRepository<ProductPriceObservationEntity> {}
    @ApplicationScoped public static class Provenance implements PanacheRepository<ProductProvenanceEntity> {}
    @ApplicationScoped public static class ImportBatches implements PanacheRepository<CatalogImportBatchEntity> {}
    @ApplicationScoped public static class ImportConflicts implements PanacheRepository<CatalogImportConflictEntity> {}
}
