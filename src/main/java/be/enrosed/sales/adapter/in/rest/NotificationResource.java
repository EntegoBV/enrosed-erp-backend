package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.NotificationService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** What is waiting on us, for the bell in the top right. */
@Path("/api/notifications")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    private final NotificationService notifications;

    public NotificationResource(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GET
    public NotificationService.Feed feed() {
        return notifications.feed();
    }
}
