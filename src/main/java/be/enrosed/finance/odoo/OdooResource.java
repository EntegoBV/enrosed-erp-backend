package be.enrosed.finance.odoo;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.Map;

@Path("/api/odoo") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class OdooResource {
    @Inject OdooService service;
    @Inject OdooDocumentService documents;
    @Inject OdooTicaCreditnoteImportService ticaCreditnotes;
    @Inject OdooLincCostMatchService lincCosts;
    @GET @Path("/tica-creditnotes/status") public OdooTicaCreditnoteImportService.Status ticaCreditnotesStatus() { return ticaCreditnotes.status(); }
    @GET @Path("/linc-costs/status") public OdooLincCostMatchService.Status lincCostsStatus() { return lincCosts.status(); }
    @GET @Path("/status") public OdooService.Status status() { return service.status(); }
    @POST @Path("/check") public OdooService.Status check() { return service.check(); }
    @POST @Path("/bank-sync") public OdooService.SyncReport sync() { return service.syncBank(); }
    @GET @Path("/documents") public OdooDocumentService.Page documents(@QueryParam("direction") @DefaultValue("incoming") String direction,
            @QueryParam("afterId") @DefaultValue("0") long afterId,@QueryParam("limit") @DefaultValue("50") int limit) {
        return documents.documents(direction,afterId,limit);
    }
    @GET @Path("/documents/{id}/attachments/{attachmentId}") @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("id") long id,@PathParam("attachmentId") long attachmentId) {
        var file=documents.download(id,attachmentId);
        String safeName=file.name().replaceAll("[^A-Za-z0-9._ -]","_");
        if(safeName.isBlank())safeName="odoo-bijlage";
        return Response.ok(file.bytes(),file.mimeType()).header("Content-Disposition","attachment; filename=\""+safeName+"\"")
                .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").build();
    }
    @Provider public static class FailureMapper implements ExceptionMapper<OdooFailure> {
        @Override public Response toResponse(OdooFailure failure) {
            int status=switch(failure.code) {
                case "INVALID_REQUEST","PURCHASE_CURRENCY_UNSUPPORTED","PURCHASE_DOCUMENT_UNAVAILABLE" -> 400;case "NOT_FOUND" -> 404;case "BUSY","LEASE_LOST","PURCHASE_PREVIEW_CHANGED" -> 409;
                case "ATTACHMENT_TOO_LARGE" -> 413;case "DISABLED","NOT_CONFIGURED","INVALID_CONFIGURATION","OUTBOUND_DISABLED","OUTBOUND_NOT_CONFIGURED" -> 503;
                default -> failure.code.startsWith("OUTBOX_")||failure.code.equals("CREDIT_REFERENCE_MISSING")?409:502;
            };
            return Response.status(status).type(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
                    .entity(Map.of("code",failure.code,"message",failure.userMessage())).build();
        }
    }
}
