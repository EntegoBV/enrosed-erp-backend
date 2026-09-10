package be.enrosed.shared.trash;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/deleted-items")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class DeletedItemsResource {
    private final DeletedItemsService service;
    public DeletedItemsResource(DeletedItemsService service) { this.service = service; }
    @GET public DeletedItemDtos.Page list() { return service.list(); }
    @GET @Path("/{id}") public DeletedItemDtos.Detail get(@PathParam("id") long id) { return service.detail(id); }
    @GET @Path("/{id}/attachments/{attachmentId}/file") @Produces(MediaType.WILDCARD)
    public jakarta.ws.rs.core.Response attachment(@PathParam("id") long id, @PathParam("attachmentId") long attachmentId) {
        var file = service.attachment(id, attachmentId);
        String name = file.name().replaceAll("[\\r\\n\\\"\\\\]", "_");
        return jakarta.ws.rs.core.Response.ok(file.content()).type(file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").build();
    }
    @POST @Path("/{id}/restore") public DeletedItemDtos.Restored restore(@PathParam("id") long id) {
        return service.restore(id);
    }
}
