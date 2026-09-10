package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.LegacyPartnerQuoteDrafts;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A read-only assessment and explicit retry for the guarded legacy draft transition. */
@Path("/api/sales-orders/{id}/partner-invoice-drafts")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class LegacyPartnerQuoteDraftResource {
    @Inject LegacyPartnerQuoteDrafts drafts;

    @GET
    public LegacyPartnerQuoteDrafts.Preview preview(@PathParam("id") long id) {
        return drafts.preview(id);
    }

    @POST
    public LegacyPartnerQuoteDrafts.Result convert(@PathParam("id") long id) {
        return drafts.convert(id);
    }
}
