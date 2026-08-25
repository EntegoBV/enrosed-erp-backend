package be.enrosed.push;

import be.enrosed.push.PushEntities.PushKeysEntity;
import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.jboss.logging.Logger;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sends web-push notifications to every registered device.
 *
 * The phone shows them natively - Android directly, iOS once the app is on
 * the home screen. A {@code kind} travels in the payload so the service
 * worker can do more than show text: a new sale plays the cash register.
 *
 * Sending is fire-and-forget on a worker thread: a dead push endpoint or a
 * slow push service must never slow down or fail the actual work.
 */
@ApplicationScoped
@Startup
public class WebPushNotifier {

    private static final Logger LOG = Logger.getLogger(WebPushNotifier.class);

    private volatile String publicKey;
    private volatile String privateKey;

    public WebPushNotifier() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** The base64url public key the browser needs to subscribe. */
    @Transactional
    public synchronized String vapidPublicKey() {
        if (publicKey != null) return publicKey;
        PushKeysEntity keys = PushKeysEntity.<PushKeysEntity>findAll().firstResult();
        if (keys == null) {
            keys = generateKeys();
        }
        publicKey = keys.publicKey;
        privateKey = keys.privateKey;
        return publicKey;
    }

    private PushKeysEntity generateKeys() {
        try {
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("prime256v1");
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(spec);
            KeyPair pair = generator.generateKeyPair();
            PushKeysEntity keys = new PushKeysEntity();
            keys.publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) pair.getPublic()));
            keys.privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey) pair.getPrivate()));
            keys.persist();
            LOG.info("VAPID-sleutels aangemaakt voor pushmeldingen");
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("VAPID-sleutels aanmaken mislukt", e);
        }
    }

    /** Registers or refreshes one device. */
    @Transactional
    public void subscribe(String endpoint, String p256dh, String auth, String userAgent) {
        PushSubscriptionEntity existing = PushSubscriptionEntity
                .<PushSubscriptionEntity>find("endpoint", endpoint).firstResult();
        if (existing == null) {
            existing = new PushSubscriptionEntity();
            existing.endpoint = endpoint;
        }
        existing.p256dh = p256dh;
        existing.auth = auth;
        existing.userAgent = userAgent;
        existing.persist();
    }

    @Transactional
    public void unsubscribe(String endpoint) {
        PushSubscriptionEntity.delete("endpoint", endpoint);
    }

    public long subscriptionCount() {
        return PushSubscriptionEntity.count();
    }

    /** Fire-and-forget to every device; dead endpoints clean themselves up. */
    public void notifyAll(String kind, String title, String body, String url) {
        List<PushSubscriptionEntity> subscriptions =
                PushSubscriptionEntity.<PushSubscriptionEntity>listAll();
        if (subscriptions.isEmpty()) return;
        vapidPublicKey();
        String payload = """
                {"kind":"%s","title":"%s","body":"%s","url":"%s"}"""
                .formatted(escape(kind), escape(title), escape(body), escape(url));
        CompletableFuture.runAsync(() -> deliver(subscriptions, payload));
    }

    private void deliver(List<PushSubscriptionEntity> subscriptions, String payload) {
        try {
            PushService sender = new PushService(publicKey, privateKey, "mailto:hello@enrosed.com");
            for (PushSubscriptionEntity subscription : subscriptions) {
                try {
                    var response = sender.send(new Notification(subscription.endpoint,
                            subscription.p256dh, subscription.auth,
                            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    int status = response.getStatusLine().getStatusCode();
                    if (status == 404 || status == 410) {
                        removeGone(subscription.endpoint);
                    } else if (status >= 400) {
                        LOG.warnf("Pushmelding geweigerd (%d) voor %s", status,
                                subscription.endpoint.substring(0,
                                        Math.min(48, subscription.endpoint.length())));
                    }
                } catch (Exception e) {
                    LOG.debugf("Pushmelding niet afgeleverd: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Pushdienst niet beschikbaar: %s", e.getMessage());
        }
    }

    @Transactional
    void removeGone(String endpoint) {
        PushSubscriptionEntity.delete("endpoint", endpoint);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ");
    }
}
