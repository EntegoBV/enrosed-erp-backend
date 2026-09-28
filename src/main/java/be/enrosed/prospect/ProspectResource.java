package be.enrosed.prospect;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import static be.enrosed.prospect.ProspectDtos.*;

@Path("/api/prospects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProspectResource {
    private final ProspectService service;

    public ProspectResource(ProspectService service) { this.service = service; }

    @GET
    public Page list(@QueryParam("search") String search, @QueryParam("status") ProspectStatus status,
                     @QueryParam("countryCode") String country, @QueryParam("page") @DefaultValue("0") int page,
                     @QueryParam("size") @DefaultValue("50") int size) {
        return service.list(search, status, country, page, size);
    }

    @GET @Path("/{id}")
    public Detail detail(@PathParam("id") long id) { return service.detail(id); }

    @POST
    public Response create(ProspectInput input) {
        return Response.status(Response.Status.CREATED).entity(service.create(input)).build();
    }

    @PUT @Path("/{id}")
    public Prospect update(@PathParam("id") long id, ProspectInput input) { return service.update(id, input); }

    @POST @Path("/{id}/activities")
    public Activity activity(@PathParam("id") long id, ActivityInput input) { return service.record(id, input); }

    @POST @Path("/{id}/email-reservations")
    public Activity reserve(@PathParam("id") long id, EmailReservationInput input) {
        return service.reserveEmail(id, input);
    }

    @GET @Path("/email-summary")
    public EmailSummary summary(@QueryParam("date") String date) {
        try {
            return service.emailSummary(date == null || date.isBlank() ? null : LocalDate.parse(date));
        } catch (DateTimeParseException invalid) {
            throw new BadRequestException("Datum moet YYYY-MM-DD zijn");
        }
    }
}
