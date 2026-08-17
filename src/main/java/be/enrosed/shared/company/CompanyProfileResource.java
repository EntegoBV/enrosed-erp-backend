package be.enrosed.shared.company;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/company")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompanyProfileResource {

    private final CompanyProfileService company;

    public CompanyProfileResource(CompanyProfileService company) {
        this.company = company;
    }

    @GET
    public CompanyProfile get() {
        return company.get();
    }

    @PUT
    public CompanyProfile save(CompanyProfile profile) {
        return company.save(profile);
    }
}
