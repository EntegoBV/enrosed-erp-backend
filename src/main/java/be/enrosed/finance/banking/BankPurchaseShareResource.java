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

/** "Verdelen over containers": one bank transaction over open payments and new ones, in one call. */
@Path("/api/bank-statements/{id}/purchase-share")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankPurchaseShareResource {
    @Inject BankPurchaseShareService service;

    @POST
    public BankPurchaseShareService.ShareResult share(@PathParam("id") long id, BankPurchaseShareService.ShareRequest request) {
        return service.share(id, request);
    }
}
