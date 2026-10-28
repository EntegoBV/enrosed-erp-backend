package be.enrosed.stockradar;

import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The radar screen's data. Personal: only the person the radar notifies can
 * see it; for anyone else the route does not exist.
 */
@Path("/api/stock-radar")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockRadarResource {

    private final StockRadarService service;

    @Inject
    Instance<CurrentActor> actor;

    public StockRadarResource(StockRadarService service) { this.service = service; }

    @GET
    public StockRadarService.Feed feed() {
        guard();
        return service.feed();
    }

    @POST
    @Path("/check")
    public StockRadarService.Feed checkAll() {
        guard();
        service.checkNow(null);
        return service.feed();
    }

    @POST
    @Path("/{key}/check")
    public StockRadarService.Feed checkOne(@PathParam("key") String key) {
        guard();
        if (StockRadarStores.byKey(key) == null) throw new NotFoundException("Winkel", key);
        service.checkNow(key);
        return service.feed();
    }

    @POST
    @Path("/test-notification")
    public StockRadarService.TestResult test() {
        guard();
        return service.sendTest();
    }

    private void guard() {
        String username = actor != null && actor.isResolvable() ? actor.get().current().username() : null;
        if (username == null || !username.equalsIgnoreCase(service.notifyUser())) throw new NotFoundException("Pagina", "voorraadradar");
    }
}
