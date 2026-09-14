package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FamilyPhotoCompatibilityServiceTest {
    @Test
    void uploadingAndReorderingFamilyImagesPreservesExistingPhotoIdsAndChannelLeads() {
        CatalogDaos.Products products = mock(CatalogDaos.Products.class);
        EntityManager em = mock(EntityManager.class);
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 14L;
        ProductEntity product = member(57L, family.id);
        ProductPhotoEntity own = projection(product, 800L, null, "WEBSITE");
        ProductPhotoEntity old = projection(product, 801L, 139L, "CATALOGUE");
        own.position = 2;
        old.position = 3;
        product.photos.addAll(List.of(own, old));
        ProductFamilyPhotoEntity existing = source(family, 139L, product, 1);
        existing.largeStorageKey = "updated-original.webp";
        existing.largeWidthPx = 2400;
        ProductFamilyPhotoEntity added = source(family, 190L, product, 0);
        family.photos.addAll(List.of(existing, added));
        when(products.list("familyId", family.id)).thenReturn(List.of(product));
        FamilyPhotoCompatibilityService service = new FamilyPhotoCompatibilityService(
                products, em, new FamilyPhotoVariantResolver());

        service.sync(family);

        assertEquals(3, product.photos.size());
        assertSame(old, product.photos.stream().filter(p -> Long.valueOf(139).equals(p.familyPhotoId))
                .findFirst().orElseThrow());
        assertEquals(801L, old.id);
        assertEquals("CATALOGUE", old.leadRoles);
        assertEquals("updated-original.webp", old.storageKey);
        assertEquals(2400, old.widthPx);
        assertEquals(4, old.position);
        assertEquals("WEBSITE", own.leadRoles);
        assertEquals(2, own.position);
        ProductPhotoEntity newProjection = product.photos.stream()
                .filter(p -> Long.valueOf(190).equals(p.familyPhotoId)).findFirst().orElseThrow();
        assertNull(newProjection.leadRoles);
        assertEquals(3, newProjection.position);
        verify(em, times(1)).persist(newProjection);
        verify(em, never()).remove(any());

        newProjection.leadRoles = "CATALOGUE,WEBSITE";
        added.position = 2;
        existing.position = 0;
        service.sync(family);

        assertEquals(3, product.photos.size());
        assertEquals(3, old.position);
        assertEquals(4, newProjection.position);
        assertEquals("CATALOGUE,WEBSITE", newProjection.leadRoles);
        verify(em, times(1)).persist(any());
        verify(em, never()).remove(any());
    }

    @Test
    void deletedOrReassignedSourceRemovesOnlyItsProjectionWithoutMovingItsLead() {
        CatalogDaos.Products products = mock(CatalogDaos.Products.class);
        EntityManager em = mock(EntityManager.class);
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 14L;
        ProductEntity red = member(57L, family.id);
        ProductEntity blue = member(58L, family.id);
        ProductPhotoEntity own = projection(red, 800L, null, "WEBSITE");
        ProductPhotoEntity reassigned = projection(red, 801L, 139L, "CATALOGUE");
        ProductPhotoEntity removed = projection(red, 802L, 140L, null);
        red.photos.addAll(List.of(own, reassigned, removed));
        family.photos.add(source(family, 139L, blue, 0));
        when(products.list("familyId", family.id)).thenReturn(List.of(red, blue));

        new FamilyPhotoCompatibilityService(products, em, new FamilyPhotoVariantResolver()).sync(family);

        assertEquals(List.of(own), red.photos);
        assertEquals("WEBSITE", own.leadRoles);
        assertEquals(1, blue.photos.size());
        assertEquals(139L, blue.photos.getFirst().familyPhotoId);
        assertNull(blue.photos.getFirst().leadRoles);
        verify(em).remove(reassigned);
        verify(em).remove(removed);
        verify(em, never()).remove(own);
    }

    private ProductEntity member(long id, long familyId) {
        ProductEntity product = new ProductEntity();
        product.id = id;
        product.familyId = familyId;
        return product;
    }

    private ProductPhotoEntity projection(ProductEntity product, long id, Long familyPhotoId,
                                           String leads) {
        ProductPhotoEntity photo = new ProductPhotoEntity();
        photo.product = product;
        photo.id = id;
        photo.familyPhotoId = familyPhotoId;
        photo.leadRoles = leads;
        photo.storageKey = "original.webp";
        return photo;
    }

    private ProductFamilyPhotoEntity source(ProductFamilyEntity family, long id,
                                            ProductEntity variant, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.id = id;
        photo.family = family;
        photo.variantProduct = variant;
        photo.position = position;
        photo.largeStorageKey = "family-" + id + ".webp";
        photo.originalFilename = photo.largeStorageKey;
        photo.largeContentType = "image/webp";
        return photo;
    }
}
