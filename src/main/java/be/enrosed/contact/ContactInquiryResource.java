package be.enrosed.contact;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/contact-inquiries")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContactInquiryResource {
    private final ContactInquiryService inquiries;

    public ContactInquiryResource(ContactInquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @GET
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("size") @DefaultValue("50") int size) {
        return Response.ok(inquiries.list(status, page, size))
                .header("Cache-Control", "no-store").build();
    }

    @PATCH
    @Path("/{id}/status")
    public Response status(@PathParam("id") long id,
                           ContactDtos.StatusRequest request) {
        return Response.ok(inquiries.updateStatus(id, request))
                .header("Cache-Control", "no-store").build();
    }
}
