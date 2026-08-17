package be.enrosed.shared.company;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * The general terms and conditions, readable without logging in.
 *
 * The quote PDF and the customer portal link here. Terms that require an
 * account to read are not terms anyone agreed to.
 */
@Path("/api/public/terms")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class PublicTermsResource {

    private final CompanyProfileService company;

    public PublicTermsResource(CompanyProfileService company) {
        this.company = company;
    }

    @GET
    public Map<String, String> terms() {
        CompanyProfile profile = company.get();
        return Map.of(
                "companyName", profile.name() == null ? "Enrosed" : profile.name(),
                "text", profile.termsOrDefault());
    }
}
