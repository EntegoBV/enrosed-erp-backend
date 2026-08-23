package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.ProductVariantLinkService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicCatalogResourceTest {

    @Test
    void catalogContainsOnlyActiveSkusPublishedForRequestedChannel() {
        ProductService products = mock(ProductService.class);
        CategoryService categories = mock(CategoryService.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("https://erp.example.test/"));
        when(categories.list()).thenReturn(List.of(category()));
        when(products.list()).thenReturn(List.of(
                product(1L, true, PublicationState.PUBLISHED, PublicationState.DRAFT),
                product(2L, true, PublicationState.DRAFT, PublicationState.PUBLISHED),
                product(3L, false, PublicationState.PUBLISHED, PublicationState.PUBLISHED)));

        Response response = new PublicCatalogResource(products, categories)
                .catalog(CatalogChannel.WEBSITE, "EN", uriInfo);
        PublicCatalogDto catalog = (PublicCatalogDto) response.getEntity();

        assertEquals(200, response.getStatus());
        assertEquals(Language.EN, catalog.language());
        assertEquals(List.of(1L), catalog.products().stream()
                .map(PublicCatalogDto.PublicProductDto::id).toList());
        assertEquals("public, max-age=60, stale-while-revalidate=300",
                response.getHeaderString("Cache-Control"));
    }

    @Test
    void photoEndpointHidesPhotosOfPrivateProducts() {
        ProductService products = mock(ProductService.class);
        Product privateProduct = product(1L, true, PublicationState.DRAFT, PublicationState.READY);
        when(products.get(1L)).thenReturn(privateProduct);

        PublicCatalogResource resource = new PublicCatalogResource(products, mock(CategoryService.class));

        assertThrows(NotFoundException.class, () -> resource.photo(1L, 9L));
        verify(products, never()).photoData("photo-key");
    }

    @Test
    void photoEndpointAllowsAProductPublishedOnEitherPublicChannel() {
        ProductService products = mock(ProductService.class);
        Product published = product(1L, true, PublicationState.DRAFT, PublicationState.PUBLISHED);
        when(products.get(1L)).thenReturn(published);
        when(products.photoData("photo-key")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));

        Response response = new PublicCatalogResource(products, mock(CategoryService.class))
                .photo(1L, 9L);

        assertEquals(200, response.getStatus());
        assertEquals("public, max-age=31536000, immutable",
                response.getHeaderString("Cache-Control"));
        assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
    }

    @Test
    void authenticatedProductPhotoEndpointNeverAdvertisesPrivateMediaAsPublicCacheable() {
        ProductService products = mock(ProductService.class);
        when(products.photo(1L, 9L)).thenReturn(new Photo(
                9L, "private-photo", "private.webp", "image/webp",
                2, 1, 1, 0));
        when(products.photoData("private-photo"))
                .thenReturn(new ByteArrayInputStream(new byte[] { 1, 2 }));

        Response response = new ProductResource(
                products, mock(BarcodeValidator.class),
                mock(ProductVariantLinkService.class), mock(ProductFamilyDtoFactory.class),
                mock(StockService.class))
                .viewPhoto(1L, 9L);

        assertEquals("private, max-age=60", response.getHeaderString("Cache-Control"));
    }

    @Test
    void familyCatalogNeverLabelsAnOlderProjectionWithANewerRevision() {
        String oldRevision = "a".repeat(64);
        String newRevision = "b".repeat(64);

        assertEquals(oldRevision,
                PublicFamilyCatalogResource.requireStableRevision(oldRevision, oldRevision));
        assertThrows(ServiceUnavailableException.class, () ->
                PublicFamilyCatalogResource.requireStableRevision(oldRevision, newRevision));
    }

    private static Category category() {
        return new Category(7L, "ROSES", "Rozen", "Geconserveerde rozen", 1);
    }

    private static Product product(Long id, boolean active,
                                   PublicationState website, PublicationState orderApp) {
        return new Product(
                id, "ENR-P0" + id, "Roos " + id,
                new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                "Rood", "Beschrijving", 7L, 99L, active,
                "rose-family", "rose-" + id, website, orderApp,
                Barcodes.none(), "0603",
                new Carton(new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                        6, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("10"), "PO", new BigDecimal("20"),
                new BigDecimal("15"), 2,
                List.of(new Photo(9L, "photo-key", "rose.jpg", "image/jpeg",
                        2, 1, 1, 0)), List.of());
    }

}
