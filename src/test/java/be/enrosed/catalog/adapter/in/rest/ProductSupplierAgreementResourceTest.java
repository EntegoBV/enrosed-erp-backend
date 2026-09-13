package be.enrosed.catalog.adapter.in.rest;
import be.enrosed.catalog.application.ProductSupplierAgreementService;
import be.enrosed.catalog.application.ProductSupplierAgreementService.*;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhoto;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProductSupplierAgreementResourceTest {
    @Test void privateDtoUsesEffectiveSourceUrlsAndNoStore() {
        var service=mock(ProductSupplierAgreementService.class);
        var photo=new AgreementPhoto(7,1,9,0,"Reference","real.png","image/png",123,80,60);
        var value=new Snapshot(2,1,9L,8L,"product:1:supplier:9",true,true,"revision","Private",List.of(photo),List.of(),List.of());
        when(service.get(2)).thenReturn(value);
        var response=new ProductSupplierAgreementResource(service).get(2);
        assertEquals("no-store",response.getHeaderString("Cache-Control"));
        var dto=(ProductSupplierAgreementResource.AgreementDto)response.getEntity();
        assertEquals(1,dto.sourceProductId()); assertTrue(dto.inherited());
        assertEquals("/api/products/1/supplier-agreement/photos/7",dto.photos().getFirst().viewUrl());
        assertArrayEquals(new String[]{AdminIdentityProvider.ADMIN_ROLE},
                ProductSupplierAgreementResource.class.getAnnotation(RolesAllowed.class).value());
    }
    @Test void mutationsForwardRevisionAndExactVariantSet() {
        var service=mock(ProductSupplierAgreementService.class);
        var value=new Snapshot(2,2,9L,8L,"product:2:supplier:9",false,true,"revision",null,List.of(),List.of(),List.of());
        var request=new ApplicabilityRequest("old",List.of(2L,3L)); var unlink=new UnlinkRequest("old2");
        when(service.updateApplicability(2,request)).thenReturn(value); when(service.unlink(2,unlink)).thenReturn(value);
        var resource=new ProductSupplierAgreementResource(service);
        resource.update(2,request); resource.unlink(2,unlink);
        verify(service).updateApplicability(2,request); verify(service).unlink(2,unlink);
    }
}
