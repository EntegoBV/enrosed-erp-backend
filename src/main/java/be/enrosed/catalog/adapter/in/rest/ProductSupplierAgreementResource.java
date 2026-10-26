package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductSupplierAgreementService;
import be.enrosed.catalog.application.ProductSupplierAgreementService.*;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/products/{productId}/supplier-agreement")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductSupplierAgreementResource {
    private final ProductSupplierAgreementService agreements;
    public ProductSupplierAgreementResource(ProductSupplierAgreementService agreements) { this.agreements = agreements; }
    /** supplierId: the agreement of that supplier of the product; omitted = the preferred supplier's. */
    @GET public Response get(@PathParam("productId") long id, @QueryParam("supplierId") Long supplierId) {
        return Response.ok(AgreementDto.from(supplierId == null
                ? agreements.get(id) : agreements.get(id, supplierId))).header("Cache-Control", "no-store").build();
    }
    @PUT @Path("/applicability") public AgreementDto update(
            @PathParam("productId") long id, ApplicabilityRequest request) {
        return AgreementDto.from(agreements.updateApplicability(id, request));
    }
    @PUT @Path("/unlink") public AgreementDto unlink(
            @PathParam("productId") long id, UnlinkRequest request) {
        return AgreementDto.from(agreements.unlink(id, request));
    }
    public record AgreementDto(long productId, long sourceProductId, Long supplierId, Long familyId,
            String groupKey, boolean inherited, boolean available, String revision, String note,
            List<ProductSupplierAgreementPhotoResource.AgreementPhotoDto> photos,
            List<Variant> variants, List<Variant> eligibleVariants) {
        static AgreementDto from(Snapshot value) {
            return new AgreementDto(value.productId(), value.sourceProductId(), value.supplierId(), value.familyId(),
                    value.groupKey(), value.inherited(), value.available(), value.revision(), value.note(),
                    value.photos().stream().map(ProductSupplierAgreementPhotoResource.AgreementPhotoDto::from).toList(),
                    value.variants(), value.eligibleVariants());
        }
    }
}
