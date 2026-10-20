package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.application.PurchasePaymentOriginService;
import be.enrosed.sourcing.domain.PurchasePaymentOrigin;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/purchase-payment-origins")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PurchasePaymentOriginResource {
    @Inject PurchasePaymentOriginService service;
    @PUT @Path("/{paymentId}")
    public PurchasePaymentOrigin update(@PathParam("paymentId") long paymentId, PurchasePaymentOrigin request) {
        if (request == null) throw new be.enrosed.shared.BusinessRuleException("Kies een betaalbron");
        return service.update(paymentId, request);
    }
    @DELETE @Path("/{paymentId}")
    public void clear(@PathParam("paymentId") long paymentId) { service.update(paymentId, null); }
}
