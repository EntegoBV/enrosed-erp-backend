package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhoto;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhotoFile;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSupplierAgreementPhotoResourceTest {

    @Test
    void dtoCarriesStableViewAndDownloadUrls() {
        AgreementPhoto photo = photo();

        ProductSupplierAgreementPhotoResource.AgreementPhotoDto dto =
                ProductSupplierAgreementPhotoResource.AgreementPhotoDto.from(photo);

        assertEquals(8L, dto.id());
        assertEquals(4L, dto.productId());
        assertEquals(6L, dto.supplierId());
        assertEquals("Approved colour reference", dto.caption());
        assertEquals("/api/products/4/supplier-agreement/photos/8", dto.viewUrl());
        assertEquals("/api/products/4/supplier-agreement/photos/8/download", dto.downloadUrl());
    }

    @Test
    void viewAndDownloadUseSupplierScopedServiceLookupAndSafeHeaders() {
        ProductSupplierAgreementPhotoService service =
                mock(ProductSupplierAgreementPhotoService.class);
        AgreementPhoto photo = photo();
        when(service.open(4L, 8L)).thenAnswer(ignored ->
                new AgreementPhotoFile(photo, new ByteArrayInputStream(new byte[] {1, 2, 3})));
        ProductSupplierAgreementPhotoResource resource =
                new ProductSupplierAgreementPhotoResource(service);

        Response view = resource.view(4L, 8L);
        Response download = resource.download(4L, 8L);

        assertEquals("image/png", view.getMediaType().toString());
        assertEquals("private, max-age=60", view.getHeaderString("Cache-Control"));
        assertEquals("inline; filename=\"reference.png\"; filename*=UTF-8''reference.png",
                view.getHeaderString("Content-Disposition"));
        assertEquals("attachment; filename=\"reference.png\"; filename*=UTF-8''reference.png",
                download.getHeaderString("Content-Disposition"));
        verify(service, org.mockito.Mockito.times(2)).open(4L, 8L);
    }

    @Test
    void captionOrderAndDeleteDelegateToTheDedicatedService() {
        ProductSupplierAgreementPhotoService service =
                mock(ProductSupplierAgreementPhotoService.class);
        AgreementPhoto photo = photo();
        when(service.updateCaption(4L, 8L, "Approved colour reference"))
                .thenReturn(photo);
        when(service.reorder(4L, List.of(8L))).thenReturn(List.of(photo));
        ProductSupplierAgreementPhotoResource resource =
                new ProductSupplierAgreementPhotoResource(service);

        resource.updateCaption(4L, 8L,
                new ProductSupplierAgreementPhotoResource.CaptionRequest(
                        "Approved colour reference"));
        resource.reorder(4L, List.of(8L));
        assertEquals(204, resource.delete(4L, 8L).getStatus());

        verify(service).updateCaption(4L, 8L, "Approved colour reference");
        verify(service).reorder(4L, List.of(8L));
        verify(service).delete(4L, 8L);
    }

    private static AgreementPhoto photo() {
        return new AgreementPhoto(
                8L, 4L, 6L, 0, "Approved colour reference", "reference.png",
                "image/png", 123L, 800, 600);
    }
}
