package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Catalogus als PDF, met een zelfgekozen productselectie. */
@Path("/api/catalog")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogExportResource {

    private final CatalogExportService export;

    public CatalogExportResource(CatalogExportService export) {
        this.export = export;
    }

    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/pdf")
    public Response exportPdf(CatalogExportService.Request request) {
        CatalogDocumentRenderer.Document document = export.export(request);
        return Response.ok(document.content())
                .header("Content-Disposition", "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }
}
