package be.enrosed.shared.company;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Terms and privacy statement, readable without logging in.
 *
 * Dutch and English are maintained; every other language gets English.
 * Maintaining eight legal translations would mean seven silently rotting.
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
    public Map<String, String> terms(@QueryParam("lang") String lang) {
        CompanyProfile profile = company.get();
        boolean dutch = lang == null || lang.isBlank() || lang.equalsIgnoreCase("nl");
        return Map.of(
                "companyName", profile.name() == null ? "Enrosed BV" : profile.name(),
                "language", dutch ? "nl" : "en",
                "terms", dutch ? profile.termsNl() : profile.termsEn(),
                "privacy", dutch ? profile.privacyNl() : profile.privacyEn());
    }
}
