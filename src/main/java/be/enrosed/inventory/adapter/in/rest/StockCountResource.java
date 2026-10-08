package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.inventory.adapter.in.rest.StockCountDtos.AddLineRequest;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.BookRequest;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.BookingCheck;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.CountLine;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.CountLineWrite;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.CountOverview;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.CountView;
import be.enrosed.inventory.adapter.in.rest.StockCountDtos.StartRequest;
import be.enrosed.inventory.application.InventoryRefusal;
import be.enrosed.inventory.application.StockCountService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** The yearly count per stock location: start, count on two phones, check, book or cancel. */
@Path("/api/stock-counts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockCountResource {

    private final StockCountService counts;

    public StockCountResource(StockCountService counts) {
        this.counts = counts;
    }

    @GET
    public CountOverview overview(@QueryParam("year") Integer year) {
        return CountOverview.from(counts.overview(year));
    }

    @POST
    public Response start(StartRequest request) {
        StartRequest asked = request == null ? new StartRequest(null, null, null, null) : request;
        var started = counts.start(asked.countYear(), asked.locationId(), asked.note(), asked.correctsCountId());
        return Response.status(Response.Status.CREATED).entity(CountView.from(started)).build();
    }

    @GET
    @Path("/{id}")
    public CountView get(@PathParam("id") long id) {
        return CountView.from(counts.view(id));
    }

    @PUT
    @Path("/{id}/lines/{lineId}")
    public CountLine saveLine(@PathParam("id") long id, @PathParam("lineId") long lineId, CountLineWrite write) {
        try {
            return CountLine.from(counts.saveLine(id, lineId, write == null ? null : write.toWrite()));
        } catch (InventoryRefusal refusal) {
            /* The other phone was first: the answer carries the line as it is now, in the shape of every other line. */
            if (refusal.details().get("line") instanceof StockCountService.Line current) {
                throw new InventoryRefusal(refusal.code(), refusal.getMessage(), Map.of("line", CountLine.from(current)));
            }
            throw refusal;
        }
    }

    @POST
    @Path("/{id}/lines")
    public Response addLine(@PathParam("id") long id, AddLineRequest request) {
        var added = counts.addLine(id, request == null ? null : request.productId());
        return Response.status(added.created() ? Response.Status.CREATED : Response.Status.OK)
                .entity(CountLine.from(added.line())).build();
    }

    @GET
    @Path("/{id}/booking-check")
    public BookingCheck bookingCheck(@PathParam("id") long id) {
        return BookingCheck.from(counts.bookingCheck(id));
    }

    @POST
    @Path("/{id}/book")
    public CountView book(@PathParam("id") long id, BookRequest request) {
        return CountView.from(counts.book(id, request == null ? null : request.checkToken()));
    }

    @POST
    @Path("/{id}/cancel")
    public CountView cancel(@PathParam("id") long id) {
        return CountView.from(counts.cancel(id));
    }
}
