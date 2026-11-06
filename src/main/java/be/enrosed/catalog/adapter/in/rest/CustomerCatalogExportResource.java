package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogExportJournal;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** The catalogue PDFs made for one customer (Prijslijsten on the customer sheet), newest first. */
@Path("/api/customers/{id}/catalog-exports")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CustomerCatalogExportResource {

    private final CatalogExportJournal journal;

    public CustomerCatalogExportResource(CatalogExportJournal journal) {
        this.journal = journal;
    }

    @GET
    public List<CatalogExportJournal.Header> list(@PathParam("id") long id,
                                                  @QueryParam("limit") @DefaultValue("20") int limit) {
        return journal.forCustomer(id, limit);
    }
}
