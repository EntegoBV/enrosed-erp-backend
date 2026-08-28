package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.application.FamilyPhotoVariantResolver;
import be.enrosed.catalog.application.PublicProductNameResolver;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicFamilyCatalogImageCacheTest {

    @Test
    void onlyTheCurrentChecksumIsImmutableAndLegacyOrStaleUrlsStillServe() {
        CanonicalCatalogDaos.Families families = mock(CanonicalCatalogDaos.Families.class);
        CanonicalCatalogDaos.DimensionObservations dimensions =
                mock(CanonicalCatalogDaos.DimensionObservations.class);
        CatalogDaos.Products products = mock(CatalogDaos.Products.class);
        CatalogDaos.Categories categories = mock(CatalogDaos.Categories.class);
        CanonicalCatalogDaos.PriceObservations prices =
                mock(CanonicalCatalogDaos.PriceObservations.class);
        PhotoStorage storage = mock(PhotoStorage.class);
        FamilyPhotoPublicationPolicy publication = mock(FamilyPhotoPublicationPolicy.class);

        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 41L;
        family.publicHandle = "family";
        family.active = true;
        family.websiteStatus = PublicationState.PUBLISHED;
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = "admin-photo";
        photo.originalFilename = "supplier.webp";
        photo.smallStorageKey = "small-key";
        photo.smallContentType = "image/jpeg";
        photo.smallSha256 = "a".repeat(64);
        photo.largeStorageKey = "large-key";
        photo.largeContentType = "image/webp";
        photo.largeSha256 = "b".repeat(64);
        family.photos.add(photo);
        ProductEntity product = new ProductEntity();
        product.id = 42L;
        product.familyId = family.id;
        product.active = true;
        List<ProductEntity> members = List.of(product);

        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamilyEntity> familyQuery = mock(PanacheQuery.class);
        when(families.find("publicHandle", "family")).thenReturn(familyQuery);
        when(familyQuery.firstResult()).thenReturn(family);
        when(products.list("familyId = ?1 order by variantPosition, id", family.id))
                .thenReturn(members);
        when(publication.isPublic(photo, members, CatalogChannel.WEBSITE)).thenReturn(true);
        when(storage.read("small-key"))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3}));

        PublicFamilyCatalogResource resource = new PublicFamilyCatalogResource(
                families, dimensions, products, categories, prices, storage,
                mock(FamilyPhotoVariantResolver.class), publication,
                mock(PublicProductNameResolver.class), mock(ContentTranslationService.class),
                new ObjectMapper());

        try (Response exact = resource.image(
                     "family", "admin-photo", "small", "a".repeat(64));
             Response stale = resource.image(
                     "family", "admin-photo", "small", "c".repeat(64));
             Response legacy = resource.image("family", "admin-photo", "small")) {
            assertEquals(200, exact.getStatus());
            assertEquals("public, max-age=31536000, immutable",
                    exact.getHeaderString("Cache-Control"));
            assertEquals(200, stale.getStatus());
            assertEquals("public, max-age=60", stale.getHeaderString("Cache-Control"));
            assertEquals(200, legacy.getStatus());
            assertEquals("public, max-age=60", legacy.getHeaderString("Cache-Control"));
            assertEquals("image/jpeg", stale.getMediaType().toString());
            assertEquals("inline; filename=\"supplier.jpg\"; "
                            + "filename*=UTF-8''supplier.jpg",
                    stale.getHeaderString("Content-Disposition"));
        }
    }
}
