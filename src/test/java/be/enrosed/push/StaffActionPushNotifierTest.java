package be.enrosed.push;

import be.enrosed.shared.security.ActorRef;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StaffActionPushNotifierTest {

    private static final ActorRef BERAT = new ActorRef("berat", "Berat");

    @Test
    void preservesProductAndPlannerMeaningAndAddsTheAuthenticatedActor() {
        assertEquals(new StaffActionPushNotifier.Message(
                        "product", "\uD83C\uDF39 Product toegevoegd: Bowl Rose XL",
                        "ENR-BOWL-XL · door Berat", "/products/82"),
                StaffActionPushNotifier.message(StaffActionPushNotifier.Ready.productCreated(
                        82L, "Bowl Rose XL", "ENR-BOWL-XL", BERAT)));

        assertEquals(new StaffActionPushNotifier.Message(
                        "agenda", "\uD83D\uDCC5 In de agenda gezet",
                        "Op 03/09/2026 om 10:30 · door Berat", "/"),
                StaffActionPushNotifier.message(StaffActionPushNotifier.Ready.plannerCreated(
                        14L, LocalDate.of(2026, 9, 3), "10:30", BERAT)));
    }

    @Test
    void plannerPayloadCannotCarryATitleNoteOrCustomerDetails() {
        StaffActionPushNotifier.Ready ready = StaffActionPushNotifier.Ready.plannerCreated(
                14L, null, null, BERAT);

        assertNull(ready.productName());
        assertNull(ready.productSku());
        assertFalse(Arrays.stream(StaffActionPushNotifier.Ready.class.getRecordComponents())
                .map(component -> component.getName())
                .anyMatch(name -> name.equals("title") || name.equals("note")
                        || name.equals("customer") || name.equals("email")));
    }

    @Test
    void sendsAfterCommitInANewBestEffortTransaction() throws Exception {
        WebPushNotifier phones = mock(WebPushNotifier.class);
        StaffActionPushNotifier notifier = new StaffActionPushNotifier(phones);
        StaffActionPushNotifier.Ready ready = StaffActionPushNotifier.Ready.productCreated(
                82L, "Bowl Rose XL", "ENR-BOWL-XL", BERAT);

        notifier.afterCommit(ready);
        verify(phones).notifyAll("product", "\uD83C\uDF39 Product toegevoegd: Bowl Rose XL",
                "ENR-BOWL-XL · door Berat", "/products/82");

        doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> notifier.afterCommit(ready));

        Method method = StaffActionPushNotifier.class.getDeclaredMethod(
                "afterCommit", StaffActionPushNotifier.Ready.class);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(observes);
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
