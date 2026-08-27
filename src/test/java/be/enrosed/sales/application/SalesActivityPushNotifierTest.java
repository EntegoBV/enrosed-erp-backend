package be.enrosed.sales.application;

import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SalesActivityPushNotifierTest {

    @Test
    void sendsActorAwareStaffMessageAndNeverBreaksTheBusinessAction() {
        WebPushNotifier phones = mock(WebPushNotifier.class);
        SalesActivityPushNotifier notifier = new SalesActivityPushNotifier(phones);
        SalesActivityPushNotifier.Ready ready = SalesActivityPushNotifier.Ready.staffQuoteSent(
                42L, "Q-0042", new ActorRef("berat", "Berat"));

        notifier.afterCommit(ready);

        verify(phones).notifyAll("sale-quote", "Offerte Q-0042 verstuurd",
                "Verstuurd door Berat", "/sales/42");

        doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> notifier.afterCommit(ready));
    }

    @Test
    void customerPayloadHasNoFieldForPortalNamesMessagesOrContactDetails() {
        SalesActivityPushNotifier.Ready ready = SalesActivityPushNotifier.Ready.customerRejected(
                42L, "Q-0042");

        assertEquals(SalesActivityPushNotifier.Activity.CUSTOMER_REJECTED, ready.activity());
        assertNull(ready.actorDisplayName());
        assertEquals(
                Arrays.asList("orderId", "activity", "orderNumber", "actorDisplayName"),
                Arrays.stream(SalesActivityPushNotifier.Ready.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertFalse(ready.toString().contains("customer@example.com"));
    }

    @Test
    void observerRunsOnlyAfterTheOwningSalesTransactionSucceeded() throws Exception {
        Method method = SalesActivityPushNotifier.class.getDeclaredMethod(
                "afterCommit", SalesActivityPushNotifier.Ready.class);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertNotNull(observes);
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
