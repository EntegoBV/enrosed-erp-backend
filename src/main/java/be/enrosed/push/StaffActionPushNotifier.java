package be.enrosed.push;

import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

import java.time.LocalDate;

/**
 * Sends the two existing staff master-data/planner notifications only after commit.
 *
 * <p>The planner payload deliberately has no title or note field: agenda text can contain
 * customer details and does not belong on a locked phone screen.</p>
 */
@ApplicationScoped
public class StaffActionPushNotifier {

    private final WebPushNotifier phones;

    public StaffActionPushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            Message message = message(ready);
            phones.notifyAll(message.kind(), message.title(), message.body(), message.url());
        } catch (RuntimeException ignored) {
            /* A device or VAPID failure may never affect an already committed staff action. */
        }
    }

    static Message message(Ready ready) {
        String actor = ready.actor().displayName();
        return switch (ready.kind()) {
            case PRODUCT_CREATED -> {
                String sku = ready.productSku() == null ? "" : ready.productSku();
                String body = sku.isBlank() ? "Toegevoegd door " + actor : sku + " · door " + actor;
                yield new Message("product", "\uD83C\uDF39 Product toegevoegd: " + ready.productName(),
                        body, "/products/" + ready.entityId());
            }
            case PLANNER_CREATED -> {
                String when = ready.onDate() == null ? ""
                        : "Op " + DocumentFormat.be(ready.onDate())
                        + (ready.atTime() == null ? "" : " om " + ready.atTime());
                String body = when.isBlank() ? "Aangemaakt door " + actor : when + " · door " + actor;
                yield new Message("agenda", "\uD83D\uDCC5 In de agenda gezet", body, "/");
            }
        };
    }

    public enum Kind { PRODUCT_CREATED, PLANNER_CREATED }

    /** Minimal, typed, server-created payload; notably no customer or free agenda text. */
    public record Ready(Kind kind, long entityId, String productName, String productSku,
                        LocalDate onDate, String atTime, ActorRef actor) {

        public Ready {
            if (kind == null) throw new IllegalArgumentException("Push kind is required");
            if (entityId <= 0) throw new IllegalArgumentException("Entity id is required");
            actor = actor == null ? ActorRef.SYSTEM : actor;
            if (kind == Kind.PRODUCT_CREATED) {
                productName = required(productName, "Product name");
                productSku = optional(productSku);
                onDate = null;
                atTime = null;
            } else {
                productName = null;
                productSku = null;
                atTime = optional(atTime);
            }
        }

        public static Ready productCreated(long id, String name, String sku, ActorRef actor) {
            return new Ready(Kind.PRODUCT_CREATED, id, name, sku, null, null, actor);
        }

        public static Ready plannerCreated(long id, LocalDate onDate, String atTime, ActorRef actor) {
            return new Ready(Kind.PLANNER_CREATED, id, null, null, onDate, atTime, actor);
        }

        private static String required(String value, String field) {
            String cleaned = optional(value);
            if (cleaned == null) throw new IllegalArgumentException(field + " is required");
            return cleaned;
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    record Message(String kind, String title, String body, String url) {}
}
