package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OdooDeadlineTest {

    @Test
    void aSlowCallTimesOutWithinTheBudgetAndIsCancelled() {
        Future<String> slow = OdooDeadline.start(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        long started = System.nanoTime();
        assertThrows(TimeoutException.class, () -> OdooDeadline.await(slow, Instant.now().plus(Duration.ofMillis(100))));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(tookMs < 500, "took " + tookMs + " ms");
        assertTrue(slow.isCancelled());
    }

    @Test
    void anOdooFailureComesOutUnwrapped() {
        Future<String> failing = OdooDeadline.start(() -> {
            throw new OdooFailure("RATE_LIMITED");
        });
        OdooFailure failure = assertThrows(OdooFailure.class,
                () -> OdooDeadline.await(failing, Instant.now().plus(Duration.ofSeconds(2))));
        assertEquals("RATE_LIMITED", failure.code);
    }

    @Test
    void aFastResultPasses() throws Exception {
        Future<String> fast = OdooDeadline.start(() -> "ok");
        assertEquals("ok", OdooDeadline.await(fast, Instant.now().plus(Duration.ofSeconds(2))));
    }

    @Test
    void aPassedDeadlineStillAnswersAFinishedCall() throws Exception {
        Future<String> fast = OdooDeadline.start(() -> "done");
        while (!fast.isDone()) Thread.onSpinWait();
        assertEquals("done", OdooDeadline.await(fast, Instant.now().minusSeconds(1)));
        Future<String> slow = OdooDeadline.start(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        assertThrows(TimeoutException.class, () -> OdooDeadline.await(slow, Instant.now().minusSeconds(1)));
    }
}
