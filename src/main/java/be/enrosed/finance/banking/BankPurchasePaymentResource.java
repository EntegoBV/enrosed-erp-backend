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

@Path("/api/bank-statements/{id}/purchase-payment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankPurchasePaymentResource {
    @Inject BankPurchasePaymentService service;

    @POST
    public BankPurchasePaymentService.Result record(@PathParam("id") long id, BankPurchasePaymentService.Request request) {
        return service.record(id, request);
    }
}
