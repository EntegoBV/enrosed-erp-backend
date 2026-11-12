package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.partnerportal.application.PartnerCustomerService;
import be.enrosed.partnerportal.application.PartnerPortalAdminService;
import be.enrosed.partnerportal.application.PartnerPortalViews;
import be.enrosed.sales.application.CustomerPartnerOrigins;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
    private final PartnerCustomerService buyers;

    public PartnerPortalAdminResource(PartnerPortalAdminService admin, PartnerCustomerService buyers) {
        this.admin = admin;
        this.buyers = buyers;
    }

    /** "Nagekeken" on the customer sheet of a customer a partner added: answers its partnerOrigin (404 for any other). */
    @POST
    @Path("/customers/{customerId}/reviewed")
    public CustomerPartnerOrigins.Origin reviewed(@PathParam("customerId") long customerId) {
        return buyers.reviewed(customerId);
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

    /** The stored default count units of this partner (only rows; no row = per stuk). */
    @GET
    @Path("/{customerId}/units")
    public List<PartnerPortalViews.UnitDefault> units(@PathParam("customerId") long customerId) {
        return admin.units(customerId);
    }

    /** {"changes": [{productId, unit|null}]}: a partial update; answers the stored rows after it. */
    @PUT
    @Path("/{customerId}/units")
    public List<PartnerPortalViews.UnitDefault> saveUnits(@PathParam("customerId") long customerId,
                                                          PartnerPortalViews.UnitsRequest request) {
        return admin.saveUnits(customerId, request);
    }

    @PUT
    @Path("/{customerId}/settings")
    public PartnerPortalViews.AdminRow saveSettings(@PathParam("customerId") long customerId,
                                                    PartnerPortalViews.SettingsRequest request) {
        return admin.saveSettings(customerId, request);
    }
}
