package be.enrosed.stockradar;

import be.enrosed.push.PushEntities.PushKeysEntity;
import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.push.WebPushNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.jboss.logging.Logger;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pushes radar news to the devices of one person. Deliberately not through
 * {@link WebPushNotifier#notifyAll}: that door is closed on the test
 * environment (ENROSED_PUSH_ENABLED=false keeps copied production devices
 * quiet), while the radar must reach exactly one phone from there.
 */
@ApplicationScoped
public class StockRadarPushSender {

    private static final Logger LOG = Logger.getLogger(StockRadarPushSender.class);
    private final WebPushNotifier push;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public StockRadarPushSender(WebPushNotifier push, ObjectMapper json) {
        this.push = push;
        this.json = json;
    }

    record Target(long id, String endpoint, String p256dh, String auth) {}
    record Keys(String publicKey, String privateKey) {}

    /** The devices of the user; devices registered before usernames existed count as that user's when they have no name. */
    static List<Target> targets(String username) {
        List<PushSubscriptionEntity> all = PushSubscriptionEntity.listAll();
        List<Target> named = new ArrayList<>();
        List<Target> anonymous = new ArrayList<>();
        for (PushSubscriptionEntity row : all) {
            Target target = new Target(row.id, row.endpoint, row.p256dh, row.auth);
            if (row.username != null && row.username.equalsIgnoreCase(username)) named.add(target);
            else if (row.username == null || row.username.isBlank()) anonymous.add(target);
        }
        return named.isEmpty() ? anonymous : named;
    }

    public int deviceCount(String username) {
        return QuarkusTransaction.requiringNew().call(() -> targets(username).size());
    }

    /** Sends to every device of the user; returns one HTTP status per device (-1 = not delivered). */
    public List<Integer> send(String username, String kind, String title, String body, String url, String tag, int ttlSeconds) {
        push.vapidPublicKey();
        record Batch(Keys keys, List<Target> targets) {}
        Batch batch = QuarkusTransaction.requiringNew().call(() -> {
            PushKeysEntity keys = PushKeysEntity.<PushKeysEntity>findAll().firstResult();
            return new Batch(keys == null ? null : new Keys(keys.publicKey, keys.privateKey), targets(username));
        });
        List<Integer> statuses = new ArrayList<>();
        if (batch.keys() == null || batch.targets().isEmpty()) return statuses;
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("kind", kind, "title", title, "body", body, "url", url, "tag", tag));
        } catch (Exception e) {
            throw new IllegalStateException("Pushbericht kon niet worden opgebouwd", e);
        }
        for (Target target : batch.targets()) {
            int status = deliver(batch.keys(), target, payload, tag, ttlSeconds);
            statuses.add(status);
            record(target, status);
        }
        return statuses;
    }

    private int deliver(Keys keys, Target target, String payload, String tag, int ttlSeconds) {
        try {
            Notification notification = Notification.builder().endpoint(target.endpoint()).userPublicKey(target.p256dh())
                    .userAuth(target.auth()).payload(payload).ttl(Math.max(60, ttlSeconds)).topic(topic(tag)).urgency(nl.martijndwars.webpush.Urgency.HIGH).build();
            PushService sender = new PushService(keys.publicKey(), keys.privateKey(), "mailto:hello@enrosed.com");
            var prepared = sender.preparePost(notification, Encoding.AES128GCM);
            HttpRequest.Builder request = HttpRequest.newBuilder(prepared.getURI()).timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(org.apache.http.util.EntityUtils.toByteArray(prepared.getEntity())));
            for (var header : prepared.getAllHeaders()) request.header(header.getName(), header.getValue());
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception failure) {
            LOG.warnf("Voorraadradar-push niet afgeleverd: %s", failure.getClass().getSimpleName());
            return -1;
        }
    }

    /** Web push topics are at most 32 URL-safe characters. */
    private static String topic(String tag) {
        String safe = tag.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.length() > 32 ? safe.substring(0, 32) : safe;
    }

    private static void record(Target target, int status) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (status == 404 || status == 410) { PushSubscriptionEntity.deleteById(target.id()); return; }
                PushSubscriptionEntity row = PushSubscriptionEntity.findById(target.id());
                if (row != null) { row.lastStatus = status; row.lastAt = Instant.now(); row.persist(); }
            });
        } catch (RuntimeException ignored) { /* bookkeeping only */ }
    }
}
