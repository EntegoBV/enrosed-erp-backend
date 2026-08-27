package be.enrosed.sales.application;

import be.enrosed.push.WebPushNotifier;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class WebsiteQuotePushNotifierTest {
    @Test
    void sendsOneNonPiiStaffNotificationAndNeverFailsTheRequest() {
        WebPushNotifier phones = mock(WebPushNotifier.class);
        WebsiteQuotePushNotifier notifier = new WebsiteQuotePushNotifier(phones);
        WebsiteQuotePushNotifier.Ready ready =
                new WebsiteQuotePushNotifier.Ready(41L, "ENR-2026-0041");

        notifier.afterCommit(ready);

        verify(phones).notifyAll("sale-quote",
                "Nieuwe websiteaanvraag ENR-2026-0041",
                "Klaar voor beoordeling in Verkoop", "/sales/41");

        doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> notifier.afterCommit(ready));
    }

    @Test
    void observerRunsOnlyAfterTheOwningQuoteTransactionSucceeded() throws Exception {
        Method method = WebsiteQuotePushNotifier.class.getDeclaredMethod(
                "afterCommit", WebsiteQuotePushNotifier.Ready.class);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertNotNull(observes);
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
