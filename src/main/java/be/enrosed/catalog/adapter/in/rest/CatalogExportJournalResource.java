package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogExportJournal;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** One journaled catalogue PDF with the price each product printed. */
@Path("/api/catalog/exports")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogExportJournalResource {

    private final CatalogExportJournal journal;

    public CatalogExportJournalResource(CatalogExportJournal journal) {
        this.journal = journal;
    }

    @GET
    @Path("/{id}")
    public CatalogExportJournal.Detail get(@PathParam("id") long id) {
        return journal.detail(id);
    }
}
