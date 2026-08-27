package be.enrosed.sourcing.application;

import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

/** Sends staff-only purchase notifications after the owning transaction commits. */
@ApplicationScoped
public class PurchasePushNotifier {

    private final WebPushNotifier phones;

    public PurchasePushNotifier(WebPushNotifier phones) {
        this.phones = phones;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) Ready ready) {
        try {
            Message message = message(ready);
            phones.notifyAll("purchase", message.title(), message.body(),
                    "/purchasing/" + ready.orderId());
        } catch (RuntimeException ignored) {
            /* A push subscription or VAPID problem may never affect the saved order. */
        }
    }

    static Message message(Ready ready) {
        ActorRef actor = ready.actor() == null ? ActorRef.SYSTEM : ready.actor();
        String name = actor.displayName();
        String number = ready.number() == null ? "" : ready.number();
        String destination = ready.destinationPort() == null || ready.destinationPort().isBlank()
                ? "Rotterdam" : ready.destinationPort().strip();
        return switch (ready.kind()) {
            case CREATED -> new Message(
                    "\uD83D\uDCE6 Nieuwe inkooporder " + number,
                    "Calculatie aangemaakt door " + name);
            case ORDERED -> new Message(
                    "\uD83D\uDCE6 Inkooporder " + number + " besteld",
                    "Bij de leverancier geplaatst door " + name);
            case DEPARTED -> new Message(
                    "\uD83D\uDEA2 Container vertrokken \u00b7 " + number,
                    "Onderweg naar " + destination + " \u00b7 door " + name);
            case RECEIVED -> new Message(
                    "\uD83D\uDCE6 Container ontvangen \u00b7 " + number,
                    ready.bookStock()
                            ? "Ontvangen door " + name + " \u00b7 voorraad wordt bijgeboekt"
                            : "Ontvangst geregistreerd door " + name);
        };
    }

    public enum Kind { CREATED, ORDERED, DEPARTED, RECEIVED }

    public record Ready(Kind kind, Long orderId, String number, String destinationPort,
                        boolean bookStock, ActorRef actor) {}

    record Message(String title, String body) {}
}
