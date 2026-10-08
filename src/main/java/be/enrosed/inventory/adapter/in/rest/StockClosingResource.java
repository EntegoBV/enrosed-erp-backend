package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.ClosingOverview;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.ClosingView;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.CreateRequest;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.DateRequest;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.DecisionWrite;
import be.enrosed.inventory.application.InventoryRefusal;
import be.enrosed.inventory.application.StockClosingService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The year-end closing while it is a concept: create, read, recompute, decide, change the date, delete. */
@Path("/api/stock-closings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockClosingResource {

    private final StockClosingService closings;

    public StockClosingResource(StockClosingService closings) {
        this.closings = closings;
    }

    @GET
    public ClosingOverview overview() {
        return ClosingOverview.from(closings.overview());
    }

    @POST
    public Response create(CreateRequest request) {
        CreateRequest asked = request == null ? new CreateRequest(null, null) : request;
        try {
            return Response.status(Response.Status.CREATED)
                    .entity(ClosingView.from(closings.create(asked.closingYear(), asked.closingDate()))).build();
        } catch (BusinessRuleException | NotFoundException refused) {
            throw refused;
        } catch (PersistenceException raced) {
            /* Two first closings at once: the unique index on year and version let one through. */
            throw new InventoryRefusal("BESTAAT_AL", "Voor " + asked.closingYear() + " bestaat al een afsluiting."
                    + " Open ze, of maak een nieuwe versie");
        }
    }

    @GET
    @Path("/{id}")
    public ClosingView get(@PathParam("id") long id) {
        return ClosingView.from(closings.view(id));
    }

    @PUT
    @Path("/{id}")
    public ClosingView setClosingDate(@PathParam("id") long id, DateRequest request) {
        return ClosingView.from(closings.setClosingDate(id, request == null ? null : request.closingDate()));
    }

    @POST
    @Path("/{id}/recompute")
    public ClosingView recompute(@PathParam("id") long id) {
        return ClosingView.from(closings.recompute(id));
    }

    @PUT
    @Path("/{id}/decisions")
    public ClosingView saveDecision(@PathParam("id") long id, DecisionWrite write) {
        return ClosingView.from(closings.saveDecision(id, write == null ? null : write.toWrite()));
    }

    @DELETE
    @Path("/{id}/decisions/{decisionId}")
    public ClosingView deleteDecision(@PathParam("id") long id, @PathParam("decisionId") long decisionId) {
        return ClosingView.from(closings.deleteDecision(id, decisionId));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        closings.delete(id);
        return Response.noContent().build();
    }
}
