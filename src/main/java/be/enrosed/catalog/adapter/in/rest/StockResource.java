package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Stock locations, the pieces per location, transfers and stocktakes. */
@Path("/api/stock")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockResource {

    private final StockService stock;

    public StockResource(StockService stock) {
        this.stock = stock;
    }

    public record LocationDto(Long id, String code, String name, StockLocation.Kind kind, String kindLabel,
                              String address, boolean active, boolean countsForWebsite,
                              boolean receivesByDefault, int position,
                              Boolean publicPickupPoint, String publicPickupLabel,
                              String publicPickupAddress, String publicPickupInstructions,
                              Integer publicPickupPosition) {
        static LocationDto from(StockLocation location) {
            return new LocationDto(location.id(), location.code(), location.name(), location.kind(),
                    location.kind().dutchLabel(), location.address(), location.active(),
                    location.countsForWebsite(), location.receivesByDefault(), location.position(),
                    location.publicPickupPoint(), location.publicPickupLabel(),
                    location.publicPickupAddress(), location.publicPickupInstructions(),
                    location.publicPickupPosition());
        }

        StockLocation toDomain(Long id, StockLocation existing) {
            return new StockLocation(id, code, name, kind, address, active, countsForWebsite,
                    receivesByDefault, position,
                    publicPickupPoint != null ? publicPickupPoint
                            : existing != null && existing.publicPickupPoint(),
                    publicPickupLabel != null ? publicPickupLabel
                            : existing == null ? null : existing.publicPickupLabel(),
                    publicPickupAddress != null ? publicPickupAddress
                            : existing == null ? null : existing.publicPickupAddress(),
                    publicPickupInstructions != null ? publicPickupInstructions
                            : existing == null ? null : existing.publicPickupInstructions(),
                    publicPickupPosition != null ? publicPickupPosition
                            : existing == null ? 0 : existing.publicPickupPosition());
        }
    }

    public record LevelDto(long productId, long locationId, int quantity) {
        static LevelDto from(StockLevel level) {
            return new LevelDto(level.productId(), level.location().id(), level.quantity());
        }
    }

    @GET
    @Path("/locations")
    public List<LocationDto> locations() {
        stock.mainLocation();
        return stock.locations().stream().map(LocationDto::from).toList();
    }

    @POST
    @Path("/locations")
    public Response createLocation(LocationDto dto) {
        StockLocation saved = stock.saveLocation(dto.toDomain(null, null));
        return Response.status(Response.Status.CREATED).entity(LocationDto.from(saved)).build();
    }

    @PUT
    @Path("/locations/{id}")
    public LocationDto updateLocation(@PathParam("id") long id, LocationDto dto) {
        return LocationDto.from(stock.saveLocation(dto.toDomain(id, stock.location(id))));
    }

    @DELETE
    @Path("/locations/{id}")
    public Response deleteLocation(@PathParam("id") long id) {
        stock.deleteLocation(id);
        return Response.noContent().build();
    }

    /** Every product's pieces per location - one call for the overview and the list totals. */
    @GET
    @Path("/levels")
    public List<LevelDto> levels() {
        return stock.allLevels().stream().map(LevelDto::from).toList();
    }

    public record StocktakeRequest(Long locationId, String reference, List<Count> counts) {
        public record Count(Long productId, Integer quantity) {}
    }

    /** A recount at one location: every listed product gets its counted figure, in one go. */
    @POST
    @Path("/stocktake")
    public Response stocktake(StocktakeRequest request) {
        if (request == null || request.locationId() == null) {
            throw new BusinessRuleException("Kies een locatie voor de telling");
        }
        if (request.counts() == null || request.counts().isEmpty()) {
            throw new BusinessRuleException("Geen getelde aantallen ontvangen");
        }
        String reference = request.reference() == null || request.reference().isBlank()
                ? "Telling" : request.reference().trim();
        for (StocktakeRequest.Count count : request.counts()) {
            if (count.productId() == null || count.quantity() == null) continue;
            stock.setLevel(count.productId(), request.locationId(), count.quantity(),
                    StockMovement.Kind.STOCKTAKE, reference);
        }
        return Response.noContent().build();
    }
}
