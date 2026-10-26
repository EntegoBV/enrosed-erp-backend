package be.enrosed.sourcing.tracking;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Instant;

/** Backend reminders continue while the ERP is closed; no email or paid notification service. */
@ApplicationScoped
public class ContainerArrivalReminderWorker {
    private static final Logger LOG = Logger.getLogger(ContainerArrivalReminderWorker.class);
    private final ContainerArrivalReminders reminders;
    private final ContainerArrivalPushSender sender;
    @ConfigProperty(name = "enrosed.tracking.arrival-reminders.push-enabled", defaultValue = "false") boolean pushEnabled;
    @ConfigProperty(name = "enrosed.push.enabled", defaultValue = "true") boolean globalPushEnabled;

    public ContainerArrivalReminderWorker(ContainerArrivalReminders reminders, ContainerArrivalPushSender sender) {
        this.reminders = reminders; this.sender = sender;
    }

    @Scheduled(identity = "container-arrival-reminders", every = "5m", delayed = "45s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scan() {
        try { reminders.scan(Instant.now()); }
        catch (RuntimeException error) { LOG.warn("Container arrival reminder scan failed; durable notices are retained."); }
    }

    @Scheduled(identity = "container-arrival-push", every = "30s", delayed = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void dispatch() {
        if (!pushEnabled || !globalPushEnabled) return;
        try {
            var claim = reminders.claim(Instant.now());
            if (claim == null) return;
            for (long device : claim.devices()) {
                if (!reminders.current(claim, Instant.now())) break;
                int ttl = (int) Math.max(1, Math.min(86400, claim.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
                int status = sender.send(claim.id(), claim.orderId(), device, claim.title(), claim.body(), ttl);
                reminders.record(claim, device, status, Instant.now());
            }
            reminders.finish(claim, Instant.now());
        } catch (RuntimeException error) { LOG.warn("Container arrival push attempt failed; its durable retry is retained."); }
    }
}
