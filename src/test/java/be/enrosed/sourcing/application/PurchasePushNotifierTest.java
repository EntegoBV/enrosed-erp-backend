package be.enrosed.sourcing.application;

import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PurchasePushNotifierTest {

    private static final ActorRef EMRE = new ActorRef("emre", "Emre");

    @Test
    void formatsEveryPurchaseMomentWithTheAuthenticatedDisplayName() {
        assertEquals(new PurchasePushNotifier.Message(
                        "\uD83D\uDCE6 Nieuwe inkooporder PO-2026-041",
                        "Calculatie aangemaakt door Emre"),
                PurchasePushNotifier.message(ready(PurchasePushNotifier.Kind.CREATED, false)));
        assertEquals(new PurchasePushNotifier.Message(
                        "\uD83D\uDCE6 Inkooporder PO-2026-041 besteld",
                        "Bij de leverancier geplaatst door Emre"),
                PurchasePushNotifier.message(ready(PurchasePushNotifier.Kind.ORDERED, false)));
        assertEquals(new PurchasePushNotifier.Message(
                        "\uD83D\uDEA2 Container vertrokken \u00b7 PO-2026-041",
                        "Onderweg naar Antwerpen \u00b7 door Emre"),
                PurchasePushNotifier.message(ready(PurchasePushNotifier.Kind.DEPARTED, false)));
        assertEquals(new PurchasePushNotifier.Message(
                        "\uD83D\uDCE6 Container ontvangen \u00b7 PO-2026-041",
                        "Ontvangen door Emre \u00b7 voorraad wordt bijgeboekt"),
                PurchasePushNotifier.message(ready(PurchasePushNotifier.Kind.RECEIVED, true)));
    }

    @Test
    void sendsOnlyStaffSafeTextAndNeverPropagatesPushFailure() {
        WebPushNotifier phones = mock(WebPushNotifier.class);
        PurchasePushNotifier notifier = new PurchasePushNotifier(phones);
        PurchasePushNotifier.Ready ready = ready(PurchasePushNotifier.Kind.CREATED, false);

        notifier.afterCommit(ready);

        verify(phones).notifyAll("purchase", "\uD83D\uDCE6 Nieuwe inkooporder PO-2026-041",
                "Calculatie aangemaakt door Emre", "/purchasing/41");

        doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> notifier.afterCommit(ready));
    }

    @Test
    void observerRunsOnlyAfterTheOwningPurchaseTransactionSucceeded() throws Exception {
        Method method = PurchasePushNotifier.class.getDeclaredMethod(
                "afterCommit", PurchasePushNotifier.Ready.class);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertNotNull(observes);
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }

    private static PurchasePushNotifier.Ready ready(PurchasePushNotifier.Kind kind, boolean bookStock) {
        return new PurchasePushNotifier.Ready(kind, 41L, "PO-2026-041", "Antwerpen", bookStock, EMRE);
    }
}
