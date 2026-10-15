package be.enrosed.finance.banking;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/bank-accounts") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankAccountResource {
    @Inject BankAccountService service;
    @GET public List<BankAccountService.Account> list(){return service.list();}
    @PUT @Path("/{key}") public BankAccountService.Account update(@PathParam("key") String key,BankAccountService.Update request){return service.update(key,request);}
    @DELETE @Path("/{key}") public void archive(@PathParam("key") String key){service.archive(key);}
    @POST @Path("/{key}/restore") public BankAccountService.Account restore(@PathParam("key") String key){return service.restore(key);}
    /** Body {"counts": false}: the account leaves the Kasprognose's start; true brings it back. */
    @PUT @Path("/{key}/cash") public BankAccountService.Account cash(@PathParam("key") String key,CashRequest request){return service.setCountsInCash(key,request==null?null:request.counts());}
    public record CashRequest(Boolean counts) {}
}
