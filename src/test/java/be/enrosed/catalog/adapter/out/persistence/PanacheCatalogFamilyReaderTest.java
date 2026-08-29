package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.domain.CatalogChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PanacheCatalogFamilyReaderTest {

    @Test
    void projectsOnlyPhotosPublishedForTheCatalogueChannel() {
        CanonicalCatalogDaos.Families families = mock(CanonicalCatalogDaos.Families.class);
        CatalogDaos.Products products = mock(CatalogDaos.Products.class);
        FamilyPhotoPublicationPolicy publication = mock(FamilyPhotoPublicationPolicy.class);

        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 41L;
        family.familyKey = "family";
        ProductEntity member = new ProductEntity();
        member.id = 42L;
        member.familyId = family.id;
        member.active = true;
        List<ProductEntity> members = List.of(member);

        ProductFamilyPhotoEntity catalogue = photo(family, 51L, "catalogue-large", 0);
        ProductFamilyPhotoEntity websiteOnly = photo(family, 52L, "website-large", 1);
        family.photos.add(catalogue);
        family.photos.add(websiteOnly);

        when(families.listAll()).thenReturn(List.of(family));
        when(products.list(
                "familyId in ?1 order by familyId, variantPosition, id", Set.of(family.id)))
                .thenReturn(members);
        when(publication.isPublic(catalogue, members, CatalogChannel.CATALOGUE))
                .thenReturn(true);
        when(publication.isPublic(websiteOnly, members, CatalogChannel.CATALOGUE))
                .thenReturn(false);

        PanacheCatalogFamilyReader reader = new PanacheCatalogFamilyReader(
                families, products, publication, new ObjectMapper());

        List<CatalogFamilyReader.GalleryPhoto> photos = reader.findByIds(Set.of(family.id))
                .getFirst().photos();

        assertEquals(List.of("catalogue-large"), photos.stream()
                .map(CatalogFamilyReader.GalleryPhoto::storageKey).toList());
        verify(publication).isPublic(catalogue, members, CatalogChannel.CATALOGUE);
        verify(publication).isPublic(websiteOnly, members, CatalogChannel.CATALOGUE);
    }

    private static ProductFamilyPhotoEntity photo(
            ProductFamilyEntity family, long id, String storageKey, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.id = id;
        photo.family = family;
        photo.largeStorageKey = storageKey;
        photo.largeContentType = "image/jpeg";
        photo.position = position;
        return photo;
    }
}
