package be.enrosed.sales.application;

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

class SalesCreationPushNotifierTest {

    private static final ActorRef BERAT = new ActorRef("berat", "Berat");

    @Test
    void formatsEveryStaffCreationWithoutCustomerData() {
        assertEquals(new SalesCreationPushNotifier.Message(
                        "sale-quote", "Nieuwe offerte ENR-2026-0042", "Aangemaakt door Berat"),
                SalesCreationPushNotifier.message(
                        SalesCreationPushNotifier.Ready.quoteCreated(
                                42L, "ENR-2026-0042", BERAT)));
        assertEquals(new SalesCreationPushNotifier.Message(
                        "sale-invoice", "Nieuwe factuur F-2026-0012", "Aangemaakt door Berat"),
                SalesCreationPushNotifier.message(
                        SalesCreationPushNotifier.Ready.invoiceCreated(
                                12L, "F-2026-0012", BERAT)));
        assertEquals(new SalesCreationPushNotifier.Message(
                        "sale-invoice", "Nieuwe factuur F-2026-0013",
                        "Vanuit offerte ENR-2026-0042 · door Berat"),
                SalesCreationPushNotifier.message(
                        SalesCreationPushNotifier.Ready.invoiceFromQuoteCreated(
                                13L, "F-2026-0013", "ENR-2026-0042", BERAT)));
        assertEquals(new SalesCreationPushNotifier.Message(
                        "sale-quote", "Nieuwe offerte ENR-2026-0043",
                        "Gekopieerd vanuit ENR-2026-0042 · door Berat"),
                SalesCreationPushNotifier.message(
                        SalesCreationPushNotifier.Ready.quoteDuplicated(
                                43L, "ENR-2026-0043", "ENR-2026-0042", BERAT)));
    }

    @Test
    void sendsTheSafePayloadAndNeverBreaksTheSavedDocument() {
        WebPushNotifier phones = mock(WebPushNotifier.class);
        SalesCreationPushNotifier notifier = new SalesCreationPushNotifier(phones);
        SalesCreationPushNotifier.Ready ready = SalesCreationPushNotifier.Ready.quoteCreated(
                42L, "ENR-2026-0042", BERAT);

        notifier.afterCommit(ready);

        verify(phones).notifyAll("sale-quote", "Nieuwe offerte ENR-2026-0042",
                "Aangemaakt door Berat", "/sales/42");

        doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> notifier.afterCommit(ready));
    }

    @Test
    void observerRunsOnlyAfterTheOwningTransactionSucceeded() throws Exception {
        Method method = SalesCreationPushNotifier.class.getDeclaredMethod(
                "afterCommit", SalesCreationPushNotifier.Ready.class);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertNotNull(observes);
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during(),
                "a rollback must not produce an early or false creation push");
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
