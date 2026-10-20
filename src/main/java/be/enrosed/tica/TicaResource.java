package be.enrosed.tica;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.Map;

@Path("/api/tica") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
public class TicaResource {
    private final TicaService service;
    private final TicaStockService stock;
    public TicaResource(TicaService service, TicaStockService stock) { this.service = service; this.stock = stock; }
    @GET @Path("/status") public TicaService.StatusDto status() { return service.status(); }
    @GET @Path("/settings") public TicaService.SettingsDto settings() { return service.settings(); }
    @GET @Path("/analytics") public java.util.List<TicaService.AnalyticsDay> analytics() { return service.analytics(); }
    @PUT @Path("/settings") public TicaService.SettingsDto settings(TicaService.SettingsDto request) { return service.updateSettings(request); }
    @POST @Path("/sync") public Response sync() { service.requestSync(); return Response.accepted(Map.of("queued", true)).build(); }
    @GET @Path("/stock-settings") public TicaStockService.SettingsDto stockSettings() {
        service.ensureSettings(); return stock.settings();
    }
    @PUT @Path("/stock-settings") public TicaStockService.SettingsDto stockSettings(TicaStockService.SettingsDto request) {
        service.ensureSettings(); return stock.update(request);
    }
    @GET @Path("/sales/{id}/history") public TicaStockService.HistoryDto history(@PathParam("id") long id) {
        return stock.history(id);
    }
    @GET @Path("/sales") public TicaService.SalesDto sales(@QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("100") int size) {
        try { return service.sales(from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to), page, size); }
        catch (java.time.DateTimeException exception) { throw new BadRequestException("Gebruik datums in YYYY-MM-DD-formaat."); }
    }
}
