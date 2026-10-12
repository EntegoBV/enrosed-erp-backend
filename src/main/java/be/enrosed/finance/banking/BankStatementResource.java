package be.enrosed.finance.banking;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/bank-statements") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankStatementResource {
    @Inject BankStatementService service;
    @GET public List<BankStatementLineEntity> list(){return service.list();}
    @POST public BankStatementLineEntity create(BankStatementService.ManualRequest request){return service.create(request);}
    @GET @Path("/{id}/suggestions") public List<BankStatementService.Match> suggestions(@PathParam("id") long id){return service.suggestions(id);}
    @POST @Path("/{id}/allocation") public BankStatementLineEntity allocate(@PathParam("id") long id,BankStatementService.Allocation request){return service.allocate(id,request);}
    @DELETE @Path("/{id}/allocation") public void unallocate(@PathParam("id") long id){service.unallocate(id);}
    @POST @Path("/{id}/links") public BankStatementLineEntity link(@PathParam("id") long id,BankStatementService.OutgoingLinks request){return service.linkExisting(id,request);}
    @DELETE @Path("/{id}/links") public void unlink(@PathParam("id") long id){service.unlinkExisting(id);}
    @POST @Path("/{id}/cost-payment") public BankStatementLineEntity costPayment(@PathParam("id") long id,BankStatementService.CostAllocation request){return service.recordCostPayment(id,request);}
    @DELETE @Path("/{id}") public void delete(@PathParam("id") long id){service.delete(id);}
}
