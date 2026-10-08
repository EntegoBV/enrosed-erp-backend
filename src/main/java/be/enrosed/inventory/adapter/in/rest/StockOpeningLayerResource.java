package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.OpeningLayer;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.OpeningLayerWrite;
import be.enrosed.inventory.application.StockOpeningLayerService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Opening values: stock from before the containers in the ERP, each with its documented value and source. */
@Path("/api/stock-opening-layers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockOpeningLayerResource {

    private final StockOpeningLayerService layers;

    public StockOpeningLayerResource(StockOpeningLayerService layers) {
        this.layers = layers;
    }

    @GET
    public List<OpeningLayer> list() {
        return layers.active().stream().map(OpeningLayer::from).toList();
    }

    @POST
    public Response save(OpeningLayerWrite write) {
        List<OpeningLayer> saved = layers.save(write == null ? null : write.toWrite()).stream().map(OpeningLayer::from).toList();
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @DELETE
    @Path("/{id}")
    public Response retire(@PathParam("id") long id) {
        layers.retire(id);
        return Response.noContent().build();
    }
}
