package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.partnerportal.application.PartnerPortalAdminService;
import be.enrosed.partnerportal.application.PartnerPortalViews;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Instellingen › Partnerportaal: staff only, like every staff resource. */
@Path("/api/partner-portal-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PartnerPortalAdminResource {

    private final PartnerPortalAdminService admin;

    public PartnerPortalAdminResource(PartnerPortalAdminService admin) {
        this.admin = admin;
    }

    @GET
    public List<PartnerPortalViews.AdminRow> list() {
        return admin.rows();
    }

    @PUT
    @Path("/{customerId}/login")
    public PartnerPortalViews.AdminRow saveLogin(@PathParam("customerId") long customerId,
                                                 PartnerPortalViews.LoginRequest request) {
        return admin.saveLogin(customerId, request);
    }

    @PUT
    @Path("/{customerId}/settings")
    public PartnerPortalViews.AdminRow saveSettings(@PathParam("customerId") long customerId,
                                                    PartnerPortalViews.SettingsRequest request) {
        return admin.saveSettings(customerId, request);
    }
}
