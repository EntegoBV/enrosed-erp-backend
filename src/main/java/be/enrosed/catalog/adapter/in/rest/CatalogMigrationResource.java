package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogMigrationService;
import be.enrosed.catalog.application.CanonicalManifestPayload;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/** Explicit two-step administrator boundary for the one-time canonical import. */
@Path("/api/admin/catalog-migration")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogMigrationResource {
    private final CatalogMigrationService migration;
    private final CanonicalManifestPayload payloads;
    private final ObjectMapper json;

    public CatalogMigrationResource(CatalogMigrationService migration,
                                    CanonicalManifestPayload payloads,
                                    ObjectMapper json) {
        this.migration = migration;
        this.payloads = payloads;
        this.json = json;
    }

    @POST
    @Path("/preflight")
    public CatalogMigrationPreflight preflight(JsonNode rawManifest) {
        CanonicalManifestPayload.Parsed parsed = payloads.parse(rawManifest);
        return migration.preflight(parsed.manifest(), parsed.verifiedPayloadSha256());
    }

    @POST
    @Path("/apply")
    public CatalogMigrationResult apply(JsonNode rawRequest) {
        if (rawRequest == null || !rawRequest.isObject() || rawRequest.get("manifest") == null) {
            throw new BusinessRuleException("Geen canoniek catalogusmanifest meegestuurd");
        }
        CanonicalManifestPayload.Parsed parsed = payloads.parse(rawRequest.get("manifest"));
        try {
            CatalogMigrationApplyRequest decoded = json.treeToValue(
                    rawRequest, CatalogMigrationApplyRequest.class);
            CatalogMigrationApplyRequest verified = new CatalogMigrationApplyRequest(
                    parsed.manifest(), decoded.replaceExistingProducts(),
                    decoded.deleteReferencingTestGraphs(), decoded.fullReset(), decoded.confirmation());
            return migration.apply(verified, parsed.verifiedPayloadSha256());
        } catch (BusinessRuleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessRuleException("Importverzoek kon niet worden gelezen");
        }
    }
}
