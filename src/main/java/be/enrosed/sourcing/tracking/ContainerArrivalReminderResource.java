package be.enrosed.sourcing.tracking;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

@Path("/api/container-arrival-reminders")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class ContainerArrivalReminderResource {
    private final ContainerArrivalReminders reminders;
    public ContainerArrivalReminderResource(ContainerArrivalReminders reminders) { this.reminders = reminders; }
    @GET @Path("/preview")
    public List<ContainerArrivalReminders.Preview> preview() { return reminders.preview(Instant.now()); }
}
