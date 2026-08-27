package be.enrosed.sales.application;

import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

/** Announces a staff-created sales document only after its transaction committed. */
@ApplicationScoped
public class SalesCreationPushNotifier {

    private final WebPushNotifier phones;

    public SalesCreationPushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            Message message = message(ready);
            phones.notifyAll(message.kind(), message.title(), message.body(),
                    "/sales/" + ready.orderId());
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved document. */
        }
    }

    static Message message(Ready ready) {
        return switch (ready.kind()) {
            case QUOTE_CREATED -> new Message(
                    "sale-quote",
                    "Nieuwe offerte " + ready.orderNumber(),
                    "Aangemaakt door " + ready.actor().displayName());
            case INVOICE_CREATED -> new Message(
                    "sale-invoice",
                    "Nieuwe factuur " + ready.orderNumber(),
                    "Aangemaakt door " + ready.actor().displayName());
            case INVOICE_FROM_QUOTE_CREATED -> new Message(
                    "sale-invoice",
                    "Nieuwe factuur " + ready.orderNumber(),
                    "Vanuit offerte " + ready.sourceNumber()
                            + " · door " + ready.actor().displayName());
            case QUOTE_DUPLICATED -> new Message(
                    "sale-quote",
                    "Nieuwe offerte " + ready.orderNumber(),
                    "Gekopieerd vanuit " + ready.sourceNumber()
                            + " · door " + ready.actor().displayName());
            case INVOICE_DUPLICATED -> new Message(
                    "sale-invoice",
                    "Nieuwe factuur " + ready.orderNumber(),
                    "Gekopieerd vanuit " + ready.sourceNumber()
                            + " · door " + ready.actor().displayName());
        };
    }

    enum Kind {
        QUOTE_CREATED,
        INVOICE_CREATED,
        INVOICE_FROM_QUOTE_CREATED,
        QUOTE_DUPLICATED,
        INVOICE_DUPLICATED
    }

    /** Contains operational document references only; customer data never reaches a device. */
    record Ready(Kind kind, long orderId, String orderNumber, String sourceNumber, ActorRef actor) {

        Ready {
            if (kind == null) throw new IllegalArgumentException("Sales creation kind is required");
            if (orderId <= 0) throw new IllegalArgumentException("Sales order id is required");
            orderNumber = required(orderNumber, "Sales order number");
            if (actor == null) throw new IllegalArgumentException("Staff actor is required");
            sourceNumber = kind == Kind.INVOICE_FROM_QUOTE_CREATED
                    || kind == Kind.QUOTE_DUPLICATED
                    || kind == Kind.INVOICE_DUPLICATED
                    ? required(sourceNumber, "Source document number") : null;
        }

        static Ready quoteCreated(long orderId, String orderNumber, ActorRef actor) {
            return new Ready(Kind.QUOTE_CREATED, orderId, orderNumber, null, actor);
        }

        static Ready invoiceCreated(long orderId, String orderNumber, ActorRef actor) {
            return new Ready(Kind.INVOICE_CREATED, orderId, orderNumber, null, actor);
        }

        static Ready invoiceFromQuoteCreated(long orderId, String orderNumber,
                                             String sourceNumber, ActorRef actor) {
            return new Ready(Kind.INVOICE_FROM_QUOTE_CREATED, orderId, orderNumber,
                    sourceNumber, actor);
        }

        static Ready quoteDuplicated(long orderId, String orderNumber,
                                     String sourceNumber, ActorRef actor) {
            return new Ready(Kind.QUOTE_DUPLICATED, orderId, orderNumber, sourceNumber, actor);
        }

        static Ready invoiceDuplicated(long orderId, String orderNumber,
                                       String sourceNumber, ActorRef actor) {
            return new Ready(Kind.INVOICE_DUPLICATED, orderId, orderNumber, sourceNumber, actor);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.strip();
        }
    }

    record Message(String kind, String title, String body) {}
}
