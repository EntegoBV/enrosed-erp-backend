package be.enrosed.finance.banking;

import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.finance.odoo.OdooPurchaseImportService;
import be.enrosed.finance.odoo.OdooCostImportStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/bank-statements") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BankStatementResource {
    @Inject BankStatementService service;
    @Inject OdooPurchaseImportService incomingDocuments;
    @GET public List<BankStatementLineEntity> list(){return service.list();}
    @POST public BankStatementLineEntity create(BankStatementService.ManualRequest request){return service.create(request);}
    @GET @Path("/{id}/suggestions") public List<BankStatementService.Match> suggestions(@PathParam("id") long id){return service.suggestions(id);}
    @POST @Path("/{id}/allocation") public BankStatementLineEntity allocate(@PathParam("id") long id,BankStatementService.Allocation request){return service.allocate(id,request);}
    @DELETE @Path("/{id}/allocation") public void unallocate(@PathParam("id") long id){service.unallocate(id);}
    @POST @Path("/{id}/links") public BankStatementLineEntity link(@PathParam("id") long id,BankStatementService.OutgoingLinks request){return service.linkExisting(id,request);}
    @DELETE @Path("/{id}/links") public void unlink(@PathParam("id") long id){service.unlinkExisting(id);}
    @POST @Path("/{id}/cost-payment") public BankStatementLineEntity costPayment(@PathParam("id") long id,BankStatementService.CostAllocation request){return service.recordCostPayment(id,request);}
    @DELETE @Path("/{id}") public void delete(@PathParam("id") long id){service.delete(id);}

    @GET @Path("/{id}/odoo-purchase-invoices")
    public OdooPurchaseImportService.Page incomingDocuments(@PathParam("id") long id,@QueryParam("afterId") @DefaultValue("0") long afterId,@QueryParam("limit") @DefaultValue("50") int limit) {return incomingDocuments.list(id,afterId,limit);}
    @GET @Path("/{id}/odoo-purchase-invoices/{documentId}/preview")
    public OdooPurchaseImportService.Preview incomingPreview(@PathParam("id") long id,@PathParam("documentId") long documentId,@QueryParam("existingCompanyCostId") Long costId) {return incomingDocuments.preview(id,documentId,costId);}
    @POST @Path("/{id}/odoo-purchase-invoice")
    public OdooCostImportStore.Result registerIncoming(@PathParam("id") long id,OdooPurchaseImportService.Request request) {return incomingDocuments.register(id,request);}
}
