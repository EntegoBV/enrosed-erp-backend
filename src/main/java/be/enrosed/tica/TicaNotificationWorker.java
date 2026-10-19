package be.enrosed.tica;

import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.tica.TicaEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.*;

/** Durable per-device retries. ACCEPTED refers to a 2xx push-provider response, never proof of display. */
@ApplicationScoped
public class TicaNotificationWorker {
    private final TicaPushSender sender;
    @ConfigProperty(name = "enrosed.tica.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "enrosed.push.enabled", defaultValue = "true") boolean pushEnabled;
    public TicaNotificationWorker(TicaPushSender sender) { this.sender = sender; }

    @Scheduled(identity = "tica-notification-outbox", every = "${enrosed.tica.notifications.every:30s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void work() {
        if (!enabled || !pushEnabled) return;
        Claimed claimed = QuarkusTransaction.requiringNew().call(() -> claim(Instant.now()));
        if (claimed == null) return;
        for (long device : claimed.devices()) {
            int status = sender.send(claimed.id(), device, claimed.title(), claimed.body(),
                    (int) Math.max(1, claimed.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
            QuarkusTransaction.requiringNew().run(() -> record(claimed, device, status));
        }
        QuarkusTransaction.requiringNew().run(() -> finish(claimed, Instant.now()));
    }

    private Claimed claim(Instant now) {
        Notice.update("status = 'EXPIRED' where status in ?1 and expiresAt <= ?2",
                List.of("PENDING", "WAITING_FOR_DEVICES", "PARTIAL", "PROCESSING"), now);
        Notice row = Notice.<Notice>find("status in ?1 and nextAttemptAt <= ?2 order by id",
                        List.of("PENDING", "WAITING_FOR_DEVICES", "PARTIAL", "PROCESSING"), now)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (row == null) return null;
        Settings settings = Settings.findById(1L);
        if (settings == null || (row.kind.equals("daily") && !settings.notificationMode.daily())
                || (row.kind.equals("sale") && !settings.notificationMode.sales())) { row.status = "CANCELLED"; return null; }
        List<PushSubscriptionEntity> subscriptions = PushSubscriptionEntity.listAll();
        if (subscriptions.isEmpty()) {
            row.status = "WAITING_FOR_DEVICES"; row.nextAttemptAt = now.plusSeconds(300); return null;
        }
        List<Delivery> devices = new ArrayList<>();
        for (PushSubscriptionEntity subscription : subscriptions) {
            Delivery delivery = Delivery.<Delivery>find("noticeId = ?1 and subscriptionId = ?2", row.id, subscription.id).firstResult();
            if (delivery == null) { delivery = new Delivery(); delivery.noticeId = row.id; delivery.subscriptionId = subscription.id; delivery.persist(); }
            if (delivery.acceptedAt == null && delivery.attemptCount < 5
                    && !Objects.equals(delivery.lastStatus, 404) && !Objects.equals(delivery.lastStatus, 410))
                devices.add(delivery);
        }
        row.status = "PROCESSING"; row.leaseToken = UUID.randomUUID().toString(); row.attemptCount++;
        // A bounded batch cannot outlive the lease even with 20-second timeouts.
        List<Delivery> selected = devices.stream().sorted(Comparator.comparingInt(d -> d.attemptCount)).limit(5).toList();
        for (Delivery delivery : selected) delivery.attemptCount++;
        List<Long> batch = selected.stream().map(d -> d.subscriptionId).toList();
        row.nextAttemptAt = now.plusSeconds(180);
        return new Claimed(row.id, row.leaseToken, row.title, row.body, row.expiresAt, batch);
    }

    private void record(Claimed claim, long subscriptionId, int status) {
        Notice notice = Notice.findById(claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (notice == null || !Objects.equals(notice.leaseToken, claim.token())) return;
        Delivery delivery = Delivery.<Delivery>find("noticeId = ?1 and subscriptionId = ?2", claim.id(), subscriptionId).firstResult();
        delivery.lastStatus = status;
        if (status >= 200 && status < 300) delivery.acceptedAt = Instant.now();
        PushSubscriptionEntity subscription = PushSubscriptionEntity.findById(subscriptionId);
        if (subscription != null) {
            if (status == 404 || status == 410) subscription.delete();
            else { subscription.lastStatus = status; subscription.lastAt = Instant.now(); }
        }
    }

    private void finish(Claimed claim, Instant now) {
        Notice notice = Notice.findById(claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (notice == null || !Objects.equals(notice.leaseToken, claim.token())) return;
        List<Delivery> deliveries = Delivery.list("noticeId", claim.id());
        long accepted = deliveries.stream().filter(d -> d.acceptedAt != null).count();
        long pending = deliveries.stream().filter(d -> d.acceptedAt == null && !Objects.equals(d.lastStatus, 404) && !Objects.equals(d.lastStatus, 410)).count();
        long retryable = deliveries.stream().filter(d -> d.acceptedAt == null && d.attemptCount < 5
                && !Objects.equals(d.lastStatus, 404) && !Objects.equals(d.lastStatus, 410)).count();
        notice.leaseToken = null;
        if (accepted > 0 && pending == 0) { notice.status = "ACCEPTED"; notice.acceptedAt = now; }
        else if (pending == 0) { notice.status = "WAITING_FOR_DEVICES"; notice.nextAttemptAt = now.plusSeconds(300); }
        else { notice.status = retryable == 0 ? "FAILED" : accepted > 0 ? "PARTIAL" : "PENDING"; notice.nextAttemptAt = now.plusSeconds(300); }
    }

    private record Claimed(long id, String token, String title, String body, Instant expiresAt, List<Long> devices) {}
}
