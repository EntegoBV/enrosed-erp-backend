package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogExportOrderService;
import be.enrosed.shared.security.AdminIdentityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@Path("/api/catalog/order")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Blocking
public class CatalogExportOrderResource {
    private final CatalogExportOrderService orders;

    public CatalogExportOrderResource(CatalogExportOrderService orders) {
        this.orders = orders;
    }

    @GET
    public Response get() {
        return response(orders.get());
    }

    @PUT
    public Response save(JsonNode request) {
        if (request == null || !request.isObject()) throw invalidRequest();
        JsonNode revision = request.get("revision");
        JsonNode ids = request.get("orderedIds");
        if (!integer(revision) || revision.longValue() < 0 || ids == null || !ids.isArray()
                || ids.size() > CatalogExportOrderService.MAX_ORDERED_IDS) throw invalidRequest();
        List<Long> orderedIds = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            if (!integer(id)) throw invalidRequest();
            orderedIds.add(id.longValue());
        }
        return response(orders.save(revision.longValue(), orderedIds));
    }

    private static boolean integer(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private static BadRequestException invalidRequest() {
        return new BadRequestException("Geef revision en orderedIds op met geldige gehele product-ID's");
    }

    private static Response response(CatalogExportOrderService.Order order) {
        return Response.ok(order).header("Cache-Control", "no-store").build();
    }
}
