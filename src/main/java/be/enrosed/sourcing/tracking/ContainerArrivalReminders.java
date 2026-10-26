package be.enrosed.sourcing.tracking;

import be.enrosed.push.PushEntities.PushSubscriptionEntity;
import be.enrosed.sales.application.NotificationService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.tracking.ContainerArrivalEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One 10-day and one 3-day reminder per purchase voyage, using the latest saved arrival date. */
@ApplicationScoped
public class ContainerArrivalReminders {
    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final List<String> DISPATCHABLE = List.of("PENDING", "WAITING_FOR_DEVICES", "PARTIAL", "PROCESSING");
    private final SourcingRepositories.PurchaseOrders orders;
    private final EntityManager entities;
    @ConfigProperty(name = "enrosed.tracking.enabled", defaultValue = "true") boolean trackingEnabled = true;
    @ConfigProperty(name = "enrosed.tracking.arrival-reminders.enabled", defaultValue = "true") boolean enabled = true;

    public ContainerArrivalReminders(SourcingRepositories.PurchaseOrders orders, EntityManager entities) {
        this.orders = orders; this.entities = entities;
    }

    public record Preview(long orderId, String orderNumber, String containerName, int thresholdDays, long daysRemaining,
                          LocalDate expectedArrival, String title, String detail, String url) {}
    public record Claim(String id, String token, long orderId, LocalDate expectedArrival, String title, String body,
                        Instant expiresAt, List<Long> devices) {}

    /** Read-only dry run; no queues, device registrations or external messages. */
    public List<Preview> preview(Instant now) {
        if (!trackingEnabled || !enabled) return List.of();
        return orders.findAll().stream().map(order -> preview(order, now)).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Preview::expectedArrival).thenComparingLong(Preview::orderId)).toList();
    }

    static Preview preview(PurchaseOrder order, Instant now) {
        Integer threshold = threshold(order, now);
        if (threshold == null) return null;
        long days = ChronoUnit.DAYS.between(now.atZone(ZONE).toLocalDate(), order.expectedArrival());
        String name = order.alias() == null || order.alias().isBlank() ? order.number() : order.alias();
        String title = days == 0 ? "Container vandaag verwacht" : "Container over " + days + (days == 1 ? " dag" : " dagen") + " verwacht";
        String destination = order.destinationPort();
        if (destination == null || destination.isBlank()) destination = "de bestemmingshaven";
        String detail = name + " · aankomst " + destination + " op "
                + order.expectedArrival().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " volgens de huidige planning.";
        return new Preview(order.id(), order.number(), name, threshold, days, order.expectedArrival(), title, detail,
                "/purchasing/" + order.id() + "?tracking=1");
    }

    static Integer threshold(PurchaseOrder order, Instant now) {
        if (order == null || order.id() == null || order.archivedAt() != null || order.receivedOn() != null
                || (order.status() != PurchaseOrderStatus.BESTELD && order.status() != PurchaseOrderStatus.ONDERWEG)
                || order.trackingReference() == null || order.trackingReference().isBlank() || order.expectedArrival() == null) return null;
        long days = ChronoUnit.DAYS.between(now.atZone(ZONE).toLocalDate(), order.expectedArrival());
        return days < 0 || days > 10 ? null : days <= 3 ? 3 : 10;
    }

    static boolean wakingHours(Instant now) {
        int hour = now.atZone(ZONE).getHour();
        return hour >= 9 && hour < 21;
    }
    static String referenceHash(PurchaseOrder order) { return ContainerTracking.hash(shipmentIdentity(order.trackingReference())); }

    /** Presentation-only edits must not replay a threshold notification for the same purchase/container. */
    static String shipmentIdentity(String reference) {
        String raw = Objects.toString(reference, "").strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String containerText = raw.replaceFirst("^(?:B/?L|BOOKING)\\s*:\\s*", "")
                .replaceAll("(?<=[A-Z]{4})\\s*-\\s*(?=\\d{7}(?![A-Z0-9]))", "-");
        var parsed = ContainerTracking.reference(containerText);
        if (parsed != null && parsed.container() && BexyClient.validBox(parsed.number()))
            return "CONTAINER:" + parsed.number();
        var typed = java.util.regex.Pattern.compile("^(B/?L|BOOKING)(?:\\s*:\\s*|\\s+)([A-Z0-9/ -]{6,80})$").matcher(raw);
        // Both BL:ABC123 and BL: ABC123 are accepted by the existing parser.
        parsed = ContainerTracking.reference(raw);
        if (parsed != null && !parsed.container())
            return (raw.startsWith("BOOKING:") ? "BOOKING:" : "BL:") + parsed.number();
        if (typed.matches()) {
            String number = typed.group(2).replace(" ", "");
            if (number.matches("[A-Z0-9/-]{6,60}"))
                return (typed.group(1).equals("BOOKING") ? "BOOKING:" : "BL:") + number;
        }
        return "REFERENCE:" + raw;
    }
    static String id(PurchaseOrder order, int threshold) {
        return ContainerTracking.hash("ARRIVAL:" + order.id() + ":" + referenceHash(order) + ":" + threshold);
    }

    /** Frequent scans catch date corrections/restarts; persisted identities prevent repeat reminders. */
    public void scan(Instant now) {
        if (!trackingEnabled || !enabled) return;
        for (PurchaseOrder order : orders.findAll()) {
            if (order.id() == null) continue;
            QuarkusTransaction.requiringNew().run(() -> reconcile(order.id(), now));
        }
    }

    void reconcile(long orderId, Instant now) {
        if (entities.find(PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE) == null) return;
        PurchaseOrder order = orders.findById(orderId).orElse(null);
        Preview current = preview(order, now);
        List<Notice> notices = Notice.<Notice>find("orderId", orderId).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        String key = current == null ? null : id(order, current.thresholdDays());
        for (Notice old : notices) {
            if (!Objects.equals(old.id, key) && DISPATCHABLE.contains(old.status)) {
                old.status = "SUSPENDED"; old.leaseToken = null;
            }
        }
        if (current == null || !wakingHours(now)) return;
        // After a near-arrival reminder, an ETA moving backwards must not create a new 10-day message.
        if (current.thresholdDays() == 10 && notices.stream().anyMatch(row -> row.thresholdDays == 3
                && row.referenceHash.equals(referenceHash(order)))) return;
        Notice row = notices.stream().filter(value -> value.id.equals(key)).findFirst().orElse(null);
        if (row == null) {
            row = new Notice(); row.id = key; row.orderId = order.id(); row.referenceHash = referenceHash(order);
            row.thresholdDays = current.thresholdDays(); row.createdAt = now; row.nextAttemptAt = now;
            updateMessage(row, current); row.persist();
        } else if (row.acceptedAt == null) {
            boolean changed = !row.expectedArrival.equals(current.expectedArrival());
            updateMessage(row, current);
            if (changed || row.status.equals("SUSPENDED")) {
                row.status = "PENDING"; row.leaseToken = null; row.nextAttemptAt = now;
            }
        }
    }

    private static void updateMessage(Notice row, Preview current) {
        row.expectedArrival = current.expectedArrival(); row.title = current.title(); row.body = current.detail();
    }

    /** Current dates are computed on read so an old queued ETA never appears as today's truth. */
    public List<NotificationService.Notification> feed(Instant now) {
        if (!trackingEnabled || !enabled) return List.of();
        Map<String, Notice> saved = new HashMap<>();
        for (Notice row : Notice.<Notice>listAll()) saved.put(row.id, row);
        List<NotificationService.Notification> result = new ArrayList<>();
        for (PurchaseOrder order : orders.findAll()) {
            Preview current = preview(order, now);
            if (current == null) continue;
            Notice row = saved.get(id(order, current.thresholdDays()));
            if (row == null || row.status.equals("SUSPENDED")) continue;
            result.add(new NotificationService.Notification(NotificationService.Kind.CONTAINER_AANKOMST, order.id(),
                    order.number(), current.containerName(), current.title(), current.detail(), true, row.createdAt, current.url(), row.id));
        }
        return result;
    }

    @Transactional
    public Claim claim(Instant now) {
        if (!trackingEnabled || !enabled || !wakingHours(now)) return null;
        Object[] candidate = entities.createQuery("select n.id, n.orderId from ContainerArrivalNotice n where n.status in :states and n.nextAttemptAt <= :now order by n.createdAt", Object[].class)
                .setParameter("states", DISPATCHABLE).setParameter("now", now).setMaxResults(1).getResultStream().findFirst().orElse(null);
        if (candidate == null) return null;
        // Every path acquires the purchase lock before the notice lock.
        if (entities.find(PurchaseOrderEntity.class, (Long) candidate[1], LockModeType.PESSIMISTIC_WRITE) == null) {
            Notice orphan = Notice.findById(candidate[0], LockModeType.PESSIMISTIC_WRITE);
            if (orphan != null) orphan.status = "SUSPENDED";
            return null;
        }
        Notice row = Notice.findById(candidate[0], LockModeType.PESSIMISTIC_WRITE);
        if (row == null) return null;
        if (!DISPATCHABLE.contains(row.status) || row.nextAttemptAt.isAfter(now)) return null;
        PurchaseOrder order = orders.findById(row.orderId).orElse(null);
        Preview current = preview(order, now);
        if (current == null || !row.id.equals(id(order, current.thresholdDays()))) {
            row.status = "SUSPENDED"; row.leaseToken = null; return null;
        }
        updateMessage(row, current);
        List<PushSubscriptionEntity> subscriptions = PushSubscriptionEntity.listAll();
        List<Delivery> devices = new ArrayList<>();
        for (PushSubscriptionEntity subscription : subscriptions) {
            Delivery delivery = Delivery.<Delivery>find("noticeId = ?1 and subscriptionId = ?2", row.id, subscription.id).firstResult();
            if (delivery == null) {
                delivery = new Delivery(); delivery.noticeId = row.id; delivery.subscriptionId = subscription.id; delivery.persist();
            }
            if (retryable(delivery)) devices.add(delivery);
        }
        if (devices.isEmpty()) {
            boolean accepted = Delivery.<Delivery>list("noticeId", row.id).stream().anyMatch(value -> value.acceptedAt != null);
            row.status = accepted ? "ACCEPTED" : "WAITING_FOR_DEVICES";
            if (accepted) row.acceptedAt = now;
            row.nextAttemptAt = now.plusSeconds(300); return null;
        }
        List<Delivery> batch = devices.stream().sorted(Comparator.comparingInt(value -> value.attemptCount)).limit(5).toList();
        for (Delivery delivery : batch) delivery.attemptCount++;
        row.status = "PROCESSING"; row.leaseToken = UUID.randomUUID().toString(); row.nextAttemptAt = now.plusSeconds(180);
        LocalDate expiresDay = row.thresholdDays == 10 ? row.expectedArrival.minusDays(3) : row.expectedArrival.plusDays(1);
        return new Claim(row.id, row.leaseToken, row.orderId, row.expectedArrival, row.title, row.body,
                expiresDay.atStartOfDay(ZONE).toInstant(), batch.stream().map(value -> value.subscriptionId).toList());
    }

    @Transactional
    public boolean current(Claim claim, Instant now) {
        if (!trackingEnabled || !enabled || !wakingHours(now)) return false;
        PurchaseOrder order = orders.findById(claim.orderId()).orElse(null);
        Preview current = preview(order, now);
        Notice row = Notice.findById(claim.id());
        return current != null && claim.id().equals(id(order, current.thresholdDays()))
                && current.expectedArrival().equals(claim.expectedArrival())
                && row != null && Objects.equals(row.leaseToken, claim.token());
    }

    @Transactional
    public void record(Claim claim, long subscriptionId, int status, Instant now) {
        Notice row = Notice.findById(claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !Objects.equals(row.leaseToken, claim.token())) return;
        Delivery delivery = Delivery.<Delivery>find("noticeId = ?1 and subscriptionId = ?2", claim.id(), subscriptionId).firstResult();
        if (delivery == null) return;
        delivery.lastStatus = status;
        if (status >= 200 && status < 300) delivery.acceptedAt = now;
        PushSubscriptionEntity subscription = PushSubscriptionEntity.findById(subscriptionId);
        if (subscription != null) {
            if (status == 404 || status == 410) subscription.delete();
            else { subscription.lastStatus = status; subscription.lastAt = now; }
        }
    }

    @Transactional
    public void finish(Claim claim, Instant now) {
        Notice row = Notice.findById(claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !Objects.equals(row.leaseToken, claim.token())) return;
        List<Delivery> deliveries = Delivery.list("noticeId", row.id);
        boolean accepted = deliveries.stream().anyMatch(value -> value.acceptedAt != null);
        boolean retry = deliveries.stream().anyMatch(ContainerArrivalReminders::retryable);
        row.leaseToken = null; row.nextAttemptAt = now.plusSeconds(300);
        if (!retry && accepted) { row.status = "ACCEPTED"; row.acceptedAt = now; }
        else row.status = retry ? accepted ? "PARTIAL" : "PENDING" : "WAITING_FOR_DEVICES";
    }

    private static boolean retryable(Delivery value) {
        return value.acceptedAt == null && value.attemptCount < 5
                && !Objects.equals(value.lastStatus, 404) && !Objects.equals(value.lastStatus, 410);
    }
}
