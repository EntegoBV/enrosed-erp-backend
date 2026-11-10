package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.catalog.adapter.in.rest.PhotoResponses;
import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.partnerportal.application.PartnerPortalReadService;
import be.enrosed.partnerportal.application.PartnerPortalViews;
import be.enrosed.partnerportal.application.PartnerSessionService;
import be.enrosed.shared.security.PartnerIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
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

    public PartnerPortalResource(SecurityIdentity identity, PartnerPortalReadService portal,
                                 PartnerSessionService sessions) {
        this.identity = identity;
        this.portal = portal;
        this.sessions = sessions;
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
