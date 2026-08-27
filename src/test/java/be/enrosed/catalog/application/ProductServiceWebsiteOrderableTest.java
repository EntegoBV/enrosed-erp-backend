package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProductServiceWebsiteOrderableTest {

    @Test
    void canonicalFamilyPublicationOwnsLinkedVariants() {
        ProductRepository repository = mock(ProductRepository.class);
        CanonicalCatalogDaos.Families families = mock(CanonicalCatalogDaos.Families.class);
        Product staleVariant = product(1L).withPublicationMetadata(
                "family-draft", "stale", PublicationState.PUBLISHED, PublicationState.DRAFT)
                .withCanonicalIdentity(10L, "v1", null, 0, true);
        Product familyPublished = product(2L).withPublicationMetadata(
                "family-live", "live", PublicationState.DRAFT, PublicationState.DRAFT)
                .withCanonicalIdentity(20L, "v2", null, 0, true);
        Product legacyFlat = product(3L).withPublicationMetadata(
                "flat", "flat", PublicationState.PUBLISHED, PublicationState.DRAFT);
        Product demo = product(4L).withPublicationMetadata(
                "demo", "demo", PublicationState.PUBLISHED, PublicationState.DRAFT)
                .withDemo(true);
        when(repository.findAll()).thenReturn(
                List.of(staleVariant, familyPublished, legacyFlat, demo));
        when(families.findById(10L)).thenReturn(family(10L, PublicationState.DRAFT));
        when(families.findById(20L)).thenReturn(family(20L, PublicationState.PUBLISHED));

        ProductService service = new ProductService(repository, mock(PhotoStorage.class),
                mock(ProductValidator.class), families, null, null);

        assertEquals(List.of(2L, 3L), service.websiteOrderableProducts().stream()
                .map(Product::id).toList());
    }

    private static ProductFamilyEntity family(long id, PublicationState website) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = id;
        family.active = true;
        family.websiteStatus = website;
        return family;
    }

    private static Product product(long id) {
        return new Product(id, "SKU-" + id, "Rose", Dimensions.empty(), null, null,
                1L, 1L, true, Barcodes.none(), null,
                new Carton(new Dimensions(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN),
                        1, BigDecimal.ONE),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO,
                BigDecimal.ONE, "test", BigDecimal.TEN, BigDecimal.TEN, 0,
                List.of(), List.of());
    }
}
