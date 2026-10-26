package be.enrosed.finance.invoices;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Runs an Odoo call beside the ENROSED read and waits for it only as long as the request's budget allows. The call needs
 * no request context (the Odoo beans are application scoped), as in {@code GoogleReportingService}.
 */
final class OdooDeadline {
    /** One shared executor for the whole application; never closed per request (closing would wait for a slow Odoo). */
    private static final ExecutorService ODOO = Executors.newVirtualThreadPerTaskExecutor();

    private OdooDeadline() {}

    static <T> Future<T> start(Supplier<T> call) {
        return ODOO.submit(call::get);
    }

    /** The result within the remaining time; TimeoutException cancels the future (cancel(true)); OdooFailure is unwrapped. */
    static <T> T await(Future<T> future, Instant deadline) throws TimeoutException {
        Duration left = Duration.between(Instant.now(), deadline);
        try {
            if (left.isNegative() || left.isZero()) {
                if (future.isDone()) return future.get();
                throw new TimeoutException("Odoo deadline passed");
            }
            return future.get(Math.max(1, left.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            future.cancel(true);
            throw late;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TimeoutException("Interrupted while waiting for Odoo");
        } catch (CancellationException cancelled) {
            throw new TimeoutException("Odoo call cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }
}
