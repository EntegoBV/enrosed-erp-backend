package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** Retired one-time import boundary. Product maintenance now belongs to the ERP. */
@Path("/api/admin/catalog-migration")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogMigrationResource {
    @POST
    @Path("/preflight")
    public CatalogMigrationPreflight preflight(JsonNode rawManifest) {
        throw retired();
    }

    @POST
    @Path("/apply")
    public CatalogMigrationResult apply(JsonNode rawRequest) {
        throw retired();
    }

    private static WebApplicationException retired() {
        return new WebApplicationException(Response.status(Response.Status.GONE)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", "CATALOG_MIGRATION_RETIRED", "message",
                        "De oude catalogusimport is afgesloten. Beheer producten, prijzen, voorraad en foto's in het ERP."))
                .build());
    }
}
