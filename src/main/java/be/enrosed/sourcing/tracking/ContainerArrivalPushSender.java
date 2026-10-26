package be.enrosed.sourcing.tracking;

import be.enrosed.push.PushEntities.PushKeysEntity;
import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.push.WebPushNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Explicit provider acceptance, separate from actual device display. Does not log private endpoints. */
@ApplicationScoped
public class ContainerArrivalPushSender {
    private final WebPushNotifier push;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @ConfigProperty(name = "enrosed.push.enabled", defaultValue = "true") boolean enabled = true;
    public ContainerArrivalPushSender(WebPushNotifier push, ObjectMapper json) { this.push = push; this.json = json; }

    public int send(String noticeId, long orderId, long subscriptionId, String title, String body, int ttl) {
        if (!enabled) return -2;
        try {
            push.vapidPublicKey();
            Credentials data = QuarkusTransaction.requiringNew().call(() -> {
                PushSubscriptionEntity subscription = PushSubscriptionEntity.findById(subscriptionId);
                PushKeysEntity keys = PushKeysEntity.<PushKeysEntity>findAll().firstResult();
                return subscription == null || keys == null ? null : new Credentials(subscription.endpoint,
                        subscription.p256dh, subscription.auth, keys.publicKey, keys.privateKey);
            });
            if (data == null) return 410;
            String payload = json.writeValueAsString(Map.of("kind", "container-arrival", "title", title, "body", body,
                    "url", "/purchasing/" + orderId + "?tracking=1", "tag", "arrival-" + noticeId.substring(0, 24)));
            Notification notification = Notification.builder().endpoint(data.endpoint()).userPublicKey(data.p256dh())
                    .userAuth(data.auth()).payload(payload).ttl(Math.max(1, ttl)).topic("arrival-" + noticeId.substring(0, 24)).build();
            PushService sender = new PushService(data.publicKey(), data.privateKey(), "mailto:info@enrosed.com");
            var prepared = sender.preparePost(notification, Encoding.AES128GCM);
            HttpRequest.Builder request = HttpRequest.newBuilder(prepared.getURI()).timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(org.apache.http.util.EntityUtils.toByteArray(prepared.getEntity())));
            for (var header : prepared.getAllHeaders()) request.header(header.getName(), header.getValue());
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); return -1;
        } catch (Exception exception) { return -1; }
    }
    private record Credentials(String endpoint, String p256dh, String auth, String publicKey, String privateKey) {}
}
