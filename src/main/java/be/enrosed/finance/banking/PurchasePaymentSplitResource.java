package be.enrosed.finance.banking;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A container payment paid in several transfers, and the inspection paid in
 * the goods' transfer. A prefix of its own on purpose: a class path under
 * /api/purchase-orders would shadow the payment methods of the sourcing
 * resource.
 */
@Path("/api/purchase-payment-splits/{paymentId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PurchasePaymentSplitResource {
    @Inject PurchasePaymentSplitService service;

    @POST
    @Path("/bank-lines")
    public PurchasePaymentSplitService.SplitResult bankLines(@PathParam("paymentId") long paymentId,
                                                           PurchasePaymentSplitService.LinesRequest request) {
        return service.linkLines(paymentId, request);
    }

    @POST
    @Path("/bank-part")
    public PurchasePaymentSplitService.SplitResult bankPart(@PathParam("paymentId") long paymentId,
                                                          PurchasePaymentSplitService.PartRequest request) {
        return service.linkPart(paymentId, request);
    }

    @POST
    @Path("/inspection")
    public PurchasePaymentSplitService.SplitResult inspection(@PathParam("paymentId") long paymentId,
                                                            PurchasePaymentSplitService.InspectionRequest request) {
        return service.splitInspection(paymentId, request);
    }
}
