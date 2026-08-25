package be.enrosed.push;

import be.enrosed.shared.BusinessRuleException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** Registering this device for notifications, and a test button. */
@Path("/api/push")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
public class PushResource {

    private final WebPushNotifier push;

    public PushResource(WebPushNotifier push) {
        this.push = push;
    }

    public record SubscriptionDto(String endpoint, String p256dh, String auth, String userAgent) {}

    @GET
    @Path("/public-key")
    public Map<String, Object> publicKey() {
        return Map.of("publicKey", push.vapidPublicKey(),
                "subscriptions", push.subscriptionCount());
    }

    @POST
    @Path("/subscriptions")
    public Map<String, Object> subscribe(SubscriptionDto dto) {
        if (dto == null || dto.endpoint() == null || dto.p256dh() == null || dto.auth() == null) {
            throw new BusinessRuleException("Onvolledige pushregistratie");
        }
        push.subscribe(dto.endpoint(), dto.p256dh(), dto.auth(), dto.userAgent());
        return Map.of("subscriptions", push.subscriptionCount());
    }

    @DELETE
    @Path("/subscriptions")
    public Map<String, Object> unsubscribe(SubscriptionDto dto) {
        if (dto != null && dto.endpoint() != null) push.unsubscribe(dto.endpoint());
        return Map.of("subscriptions", push.subscriptionCount());
    }

    public record DeviceDto(Long id, String device, String since, Integer lastStatus,
                            String lastAt) {}

    /** Every registered device with its latest delivery result. */
    @GET
    @Path("/subscriptions")
    public java.util.List<DeviceDto> devices() {
        return push.subscriptions().stream()
                .map(row -> new DeviceDto(row.id, deviceName(row.userAgent),
                        row.createdAt == null ? null : row.createdAt.toString(),
                        row.lastStatus, row.lastAt == null ? null : row.lastAt.toString()))
                .toList();
    }

    private static String deviceName(String userAgent) {
        if (userAgent == null) return "Onbekend toestel";
        if (userAgent.contains("iPhone")) return "iPhone";
        if (userAgent.contains("iPad")) return "iPad";
        if (userAgent.contains("Android")) return "Android";
        if (userAgent.contains("Macintosh")) return "Mac";
        if (userAgent.contains("Windows")) return "Windows-pc";
        return "Browser";
    }

    /** Sends the sale notification to every device - kaching included. */
    @POST
    @Path("/test")
    public Map<String, Object> test() {
        push.notifyAll("sale-quote", "\uD83D\uDD14 Testmelding van Enrosed",
                "Zo klinkt en oogt een nieuwe verkoop op dit toestel.", "/sales");
        return Map.of("sent", push.subscriptionCount());
    }
}
