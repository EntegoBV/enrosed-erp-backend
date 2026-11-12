package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.catalog.adapter.in.rest.PhotoResponses;
import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.partnerportal.application.PartnerCustomerService;
import be.enrosed.partnerportal.application.PartnerPortalReadService;
import be.enrosed.partnerportal.application.PartnerPortalViews;
import be.enrosed.partnerportal.application.PartnerSaleReportService;
import be.enrosed.partnerportal.application.PartnerSaleRequests;
import be.enrosed.partnerportal.application.PartnerSessionService;
import be.enrosed.shared.security.PartnerIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The partner's own API. Role partner only (a staff login gets 403 here, a partner login gets
 * 403 on every staff resource); the customer comes from the identity alone.
 */
@Path("/api/partner-portal")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(PartnerIdentity.ROLE)
public class PartnerPortalResource {

    private final SecurityIdentity identity;
    private final PartnerPortalReadService portal;
    private final PartnerSessionService sessions;
    private final PartnerSaleReportService meldingen;
    private final PartnerCustomerService buyers;

    public PartnerPortalResource(SecurityIdentity identity, PartnerPortalReadService portal,
                                 PartnerSessionService sessions, PartnerSaleReportService meldingen,
                                 PartnerCustomerService buyers) {
        this.identity = identity;
        this.portal = portal;
        this.sessions = sessions;
        this.meldingen = meldingen;
        this.buyers = buyers;
    }

    /**
     * The buyer picker of a direct sale: ERP customers by company or city (at least 2 letters, at most 20),
     * only {customerId, company, city, countryCode, addedByYou}; no q = the last 8 buyers.
     */
    @GET
    @Path("/customers")
    public List<PartnerPortalViews.PartnerCustomerTile> customers(@QueryParam("q") String q) {
        PartnerCaller caller = PartnerCaller.of(identity);
        return buyers.search(caller.customerId(), caller.loginId(), q);
    }

    /** Nieuwe klant: {company, countryCode?, city?, email?, phone?}; 201, or 200 with the one already added under that name. */
    @POST
    @Path("/customers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addCustomer(PartnerSaleRequests.NewCustomer request) {
        PartnerCaller caller = PartnerCaller.of(identity);
        PartnerCustomerService.Added added = buyers.add(caller.customerId(), caller.loginId(), request);
        return Response.status(added.created() ? Response.Status.CREATED : Response.Status.OK).entity(added.tile()).build();
    }

    /**
     * A new melding: 201 with the PartnerReport; the same clientRef again answers 200 with the
     * melding that already exists, and nothing is booked twice.
     */
    @POST
    @Path("/reports")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response report(PartnerSaleRequests.Report request) {
        PartnerCaller caller = PartnerCaller.of(identity);
        PartnerSaleReportService.Outcome outcome = meldingen.create(caller.customerId(), caller.loginId(), request, false);
        return Response.status(outcome.created() ? Response.Status.CREATED : Response.Status.OK)
                .entity(portal.report(caller.customerId(), outcome.reportId())).build();
    }

    /** Ongedaan maken: withdraws an own melding while nothing of it is invoiced. */
    @DELETE
    @Path("/reports/{id}")
    public PartnerPortalViews.PartnerReport undo(@PathParam("id") long id) {
        PartnerCaller caller = PartnerCaller.of(identity);
        meldingen.withdraw(caller.customerId(), id, null, false);
        return portal.report(caller.customerId(), id);
    }

    @GET
    @Path("/overview")
    public PartnerPortalViews.Overview overview() {
        PartnerCaller caller = PartnerCaller.of(identity);
        return portal.overview(caller.customerId(), caller.loginId());
    }

    @GET
    @Path("/reports")
    public PartnerPortalViews.ReportPage reports(@QueryParam("before") Long before, @QueryParam("limit") Integer limit) {
        return portal.reports(PartnerCaller.of(identity).customerId(), before, limit);
    }

    /** One own melding (Bewerken loads it); 404 for another partner's and for an old version. */
    @GET
    @Path("/reports/{id}")
    public PartnerPortalViews.PartnerReport reportById(@PathParam("id") long id) {
        return portal.report(PartnerCaller.of(identity).customerId(), id);
    }

    /** small (480 px) or medium (1280 px); v is the tile's photoVersion and only makes the URL change. */
    @GET
    @Path("/products/{productId}/photo")
    @Produces(MediaType.WILDCARD)
    public Response photo(@PathParam("productId") long productId, @QueryParam("size") String size,
                          @HeaderParam(HttpHeaders.IF_NONE_MATCH) String ifNoneMatch) {
        PartnerPortalReadService.PhotoAnswer answer = portal.photo(PartnerCaller.of(identity).customerId(), productId, size);
        if (ifNoneMatch != null && ifNoneMatch.contains(answer.etag())) {
            return Response.notModified().header("ETag", answer.etag())
                    .header("Cache-Control", "private, max-age=86400").build();
        }
        SalesPhotoService.Image image = answer.image().get();
        return PhotoResponses.inline(image.data(), image.contentType(), image.filename())
                .header("Cache-Control", "private, max-age=86400")
                .header("ETag", answer.etag())
                .build();
    }

    /** Signs this device out: the session behind the key is deleted (nothing to delete on a password call). */
    @POST
    @Path("/logout")
    public Response logout() {
        PartnerCaller caller = PartnerCaller.of(identity);
        if (PartnerIdentity.AUTH_SESSION.equals(caller.auth()) && caller.sessionId() != null) {
            sessions.delete(caller.sessionId());
        }
        return Response.noContent().build();
    }
}
