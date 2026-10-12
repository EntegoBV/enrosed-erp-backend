package be.enrosed.finance.odoo;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/odoo/outbox") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class OdooOutboxResource {
    @Inject OdooOutboxService service;
    @GET @Path("/{id}") public OdooOutboxService.View view(@PathParam("id") long id){return service.view(id);}
    @GET @Path("/{id}/partners") public OdooOutboxService.Partners partners(@PathParam("id") long id){return service.partners(id);}
    @POST @Path("/{id}/preview") public OdooOutboxService.View preview(@PathParam("id") long id,OdooOutboxService.PreviewRequest request){return service.preview(id,request);}
    @POST @Path("/{id}/draft") public OdooOutboxService.View draft(@PathParam("id") long id,OdooOutboxService.MutationRequest request){return service.draft(id,request);}
    @POST @Path("/{id}/send") public OdooOutboxService.View send(@PathParam("id") long id,OdooOutboxService.MutationRequest request){return service.send(id,request);}
    @POST @Path("/{id}/refresh") public OdooOutboxService.View refresh(@PathParam("id") long id){return service.refresh(id);}
}
