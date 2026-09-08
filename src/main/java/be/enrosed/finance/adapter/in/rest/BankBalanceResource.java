package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.BankBalanceService;
import be.enrosed.finance.domain.BankBalance;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Bank readings: what each account held on a day. */
@Path("/api/bank-balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankBalanceResource {

    private final BankBalanceService balances;

    public BankBalanceResource(BankBalanceService balances) {
        this.balances = balances;
    }

    @GET
    public List<BankBalance> list() {
        return balances.list();
    }

    @POST
    public Response create(BankBalance balance) {
        return Response.status(Response.Status.CREATED).entity(balances.create(balance)).build();
    }

    @PUT
    @Path("/{id}")
    public BankBalance update(@PathParam("id") long id, BankBalance balance) {
        return balances.update(id, balance);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        balances.delete(id);
        return Response.noContent().build();
    }
}
