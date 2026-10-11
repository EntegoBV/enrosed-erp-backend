package be.enrosed.shared.security;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/auth/accounts")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StaffContactResource {
    private final StaffContactService contacts;

    public StaffContactResource(StaffContactService contacts) {
        this.contacts = contacts;
    }

    @GET
    public List<StaffContactService.Contact> list() {
        return contacts.list();
    }

    @PUT
    @Path("/{username}/phone")
    public StaffContactService.Contact savePhone(@PathParam("username") String username, PhoneRequest request) {
        if (request == null) throw new BadRequestException("Contactgegevens ontbreken");
        return contacts.savePhone(username, request.phone());
    }

    public record PhoneRequest(String phone) {}
}
