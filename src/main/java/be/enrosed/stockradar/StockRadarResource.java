package be.enrosed.stockradar;

import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The radar screen's data. Personal: only the person the radar notifies can
 * see it; other staff get a 404 as if the route did not exist (partners and
 * anonymous callers are refused earlier by the role check, 401/403).
 */
@Path("/api/stock-radar")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockRadarResource {

    private final StockRadarService service;

    @Inject
    Instance<CurrentActor> actor;

    public StockRadarResource(StockRadarService service) { this.service = service; }

    /**
     * What a "Nu opnieuw proberen" did: the pages asked now (0 when all were
     * asked under a minute ago), the seconds until a page that waits its
     * shop's turn comes (0 = none), and the feed.
     */
    public record CheckResult(int started, long queuedSeconds, StockRadarService.Feed feed) {}

    @GET
    public StockRadarService.Feed feed() {
        guard();
        return service.feed();
    }

    @POST
    @Path("/check")
    public CheckResult checkAll() {
        guard();
        StockRadarService.CheckStart start = service.checkNow(null);
        return new CheckResult(start.started(), start.queuedSeconds(), service.feed());
    }

    /** One watch ("coolblue-be:blauw-256") or one shop ("coolblue-be"). */
    @POST
    @Path("/{key}/check")
    public CheckResult checkOne(@PathParam("key") String key) {
        guard();
        if (StockRadarStores.byKey(key) == null && StockRadarStores.shopByKey(key) == null) throw new NotFoundException("Winkel", key);
        StockRadarService.CheckStart start = service.checkNow(key);
        return new CheckResult(start.started(), start.queuedSeconds(), service.feed());
    }

    /** The extra mail addresses, as a whole list: {"emails": ["…", …]}. */
    public record Recipients(java.util.List<String> emails) {}

    @PUT
    @Path("/mail-recipients")
    @Consumes(MediaType.APPLICATION_JSON)
    public StockRadarService.Feed setRecipients(Recipients body) {
        guard();
        service.setExtraRecipients(body == null ? java.util.List.of() : body.emails());
        return service.feed();
    }

    @POST
    @Path("/test-push")
    public StockRadarService.PushTest testPush() {
        guard();
        return service.sendTestPush();
    }

    @POST
    @Path("/test-mail")
    public StockRadarService.MailTest testMail() {
        guard();
        return service.sendTestMail();
    }

    private void guard() {
        String username = actor != null && actor.isResolvable() ? actor.get().current().username() : null;
        if (username == null || !username.equalsIgnoreCase(service.notifyUser())) throw new NotFoundException("Pagina", "voorraadradar");
    }
}
