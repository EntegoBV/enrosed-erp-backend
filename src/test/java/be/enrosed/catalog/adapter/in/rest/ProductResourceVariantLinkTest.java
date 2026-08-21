package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductVariantLinkService;
import be.enrosed.catalog.domain.PublicationState;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductResourceVariantLinkTest {

    @Test
    void mapsTheSelectedProductToTheLinkCommandAndReturnsTheFullFamilyProjection() {
        ProductService products = mock(ProductService.class);
        ProductVariantLinkService links = mock(ProductVariantLinkService.class);
        ProductFamilyDtoFactory familyDtos = mock(ProductFamilyDtoFactory.class);
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 71L;
        ProductFamilyDto expected = familyDto();
        when(links.link(11L, 22L))
                .thenReturn(new ProductVariantLinkService.Result(family, true));
        when(familyDtos.from(family)).thenReturn(expected);

        ProductFamilyDto response = new ProductResource(
                products, mock(BarcodeValidator.class), links, familyDtos)
                .linkVariant(11L, new ProductVariantLinkRequest(22L));

        assertSame(expected, response);
        verify(links).link(11L, 22L);
        verify(familyDtos).from(family);
    }

    @Test
    void rejectsAMissingSelectedProductBeforeCallingTheService() {
        ProductVariantLinkService links = mock(ProductVariantLinkService.class);
        ProductResource resource = new ProductResource(
                mock(ProductService.class), mock(BarcodeValidator.class), links,
                mock(ProductFamilyDtoFactory.class));

        assertThrows(BadRequestException.class,
                () -> resource.linkVariant(11L, new ProductVariantLinkRequest(null)));

        verifyNoInteractions(links);
    }

    private static ProductFamilyDto familyDto() {
        return new ProductFamilyDto(
                71L, "model-11-22", null,
                null, null, null, 0, null, List.of(), 0, null, List.of(),
                PublicationState.DRAFT, PublicationState.DRAFT, PublicationState.DRAFT,
                true, "Model", null, null, null, List.of(), null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 0);
    }
}
