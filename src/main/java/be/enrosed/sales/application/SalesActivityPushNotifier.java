package be.enrosed.sales.application;

import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

/** Sends an operational sales notification only after its business transaction committed. */
@ApplicationScoped
public class SalesActivityPushNotifier {

    private final WebPushNotifier phones;

    public SalesActivityPushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            Message message = message(ready);
            phones.notifyAll(message.kind(), message.title(), message.body(),
                    "/sales/" + ready.orderId());
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved sales action. */
        }
    }

    private static Message message(Ready ready) {
        return switch (ready.activity()) {
            case STAFF_QUOTE_SENT -> new Message(
                    "sale-quote",
                    "Offerte " + ready.orderNumber() + " verstuurd",
                    "Verstuurd door " + ready.actorDisplayName());
            case STAFF_INVOICE_SENT -> new Message(
                    "sale-invoice",
                    "Factuur " + ready.orderNumber() + " verstuurd",
                    "Verstuurd door " + ready.actorDisplayName());
            case CUSTOMER_OPENED -> new Message(
                    "info",
                    "\uD83D\uDC40 Offerte " + ready.orderNumber() + " geopend",
                    "De klant bekijkt de offerte in het portaal");
            case CUSTOMER_ACCEPTED -> new Message(
                    "sale-signed",
                    "\u270D\uFE0F Offerte " + ready.orderNumber() + " getekend",
                    "De klant heeft getekend - tijd om te leveren");
            case CUSTOMER_REJECTED -> new Message(
                    "info",
                    "\u274C Offerte " + ready.orderNumber() + " afgewezen",
                    "De klant wees de offerte af - bekijk de reden in Verkoop");
            case CUSTOMER_CHANGE_REQUESTED -> new Message(
                    "info",
                    "\u270F\uFE0F Wijziging gevraagd op " + ready.orderNumber(),
                    "De klant stelt een aanpassing voor - beoordeel het voorstel");
        };
    }

    enum Activity {
        STAFF_QUOTE_SENT,
        STAFF_INVOICE_SENT,
        CUSTOMER_OPENED,
        CUSTOMER_ACCEPTED,
        CUSTOMER_REJECTED,
        CUSTOMER_CHANGE_REQUESTED
    }

    /**
     * Minimal after-commit payload. Free customer text, contact details and credentials cannot be
     * carried to a device by this type; customer events deliberately have no actor field value.
     */
    record Ready(long orderId, Activity activity, String orderNumber, String actorDisplayName) {

        Ready {
            if (orderId <= 0) throw new IllegalArgumentException("Sales order id is required");
            if (activity == null) throw new IllegalArgumentException("Sales activity is required");
            orderNumber = required(orderNumber, "Sales order number");
            if (activity == Activity.STAFF_QUOTE_SENT
                    || activity == Activity.STAFF_INVOICE_SENT) {
                actorDisplayName = required(actorDisplayName, "Staff actor");
            } else {
                actorDisplayName = null;
            }
        }

        static Ready staffQuoteSent(long orderId, String orderNumber, ActorRef actor) {
            return staff(orderId, Activity.STAFF_QUOTE_SENT, orderNumber, actor);
        }

        static Ready staffInvoiceSent(long orderId, String orderNumber, ActorRef actor) {
            return staff(orderId, Activity.STAFF_INVOICE_SENT, orderNumber, actor);
        }

        static Ready customerOpened(long orderId, String orderNumber) {
            return customer(orderId, Activity.CUSTOMER_OPENED, orderNumber);
        }

        static Ready customerAccepted(long orderId, String orderNumber) {
            return customer(orderId, Activity.CUSTOMER_ACCEPTED, orderNumber);
        }

        static Ready customerRejected(long orderId, String orderNumber) {
            return customer(orderId, Activity.CUSTOMER_REJECTED, orderNumber);
        }

        static Ready customerChangeRequested(long orderId, String orderNumber) {
            return customer(orderId, Activity.CUSTOMER_CHANGE_REQUESTED, orderNumber);
        }

        private static Ready staff(long orderId, Activity activity, String orderNumber,
                                   ActorRef actor) {
            if (actor == null) throw new IllegalArgumentException("Staff actor is required");
            return new Ready(orderId, activity, orderNumber, actor.displayName());
        }

        private static Ready customer(long orderId, Activity activity, String orderNumber) {
            return new Ready(orderId, activity, orderNumber, null);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.strip();
        }
    }

    private record Message(String kind, String title, String body) {}
}
