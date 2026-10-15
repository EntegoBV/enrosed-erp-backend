package be.enrosed.finance.forecast;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/** The Kasprognose's company settings: today the buffer for "krap". A 404 tells the ERP the server predates this round. */
@Path("/api/cash-forecast/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CashForecastSettingsResource {
    @Inject CashForecastSettingsService settings;

    @GET
    public CashForecastSettingsService.Settings get() {
        return settings.get();
    }

    /** Body {"cashBufferEur": 12500.00}; null goes back to the default. */
    @PUT
    public CashForecastSettingsService.Settings save(CashForecastSettingsService.Request request) {
        return settings.save(request);
    }
}
