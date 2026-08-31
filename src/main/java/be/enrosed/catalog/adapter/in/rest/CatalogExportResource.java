package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The catalogue as a PDF, with a hand-picked product selection. */
@Path("/api/catalog")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogExportResource {

    private final CatalogExportService export;

    public CatalogExportResource(CatalogExportService export) {
        this.export = export;
    }

    @POST
    @Path("/preflight")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CatalogExportService.Preflight preflight(CatalogExportService.Request request) {
        return export.preflight(request);
    }

    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/pdf")
    public Response exportPdf(CatalogExportService.Request request,
                              @QueryParam("inline") @DefaultValue("false") boolean inline) {
        CatalogDocumentRenderer.Document document = export.export(request);
        return Response.ok(document.content())
                .header("Content-Disposition", (inline ? "inline" : "attachment")
                        + "; filename=\"" + document.filename() + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
