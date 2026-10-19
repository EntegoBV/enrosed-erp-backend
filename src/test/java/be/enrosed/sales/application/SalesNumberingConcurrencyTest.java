package be.enrosed.sales.application;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real independent transactions must allocate after the preceding commit or rollback. */
@QuarkusTest
class SalesNumberingConcurrencyTest {
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;

    @Test
    void concurrentInvoicesCommitWithSuccessiveNumbers() throws Exception {
        long customer = customer();
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contender = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> transaction(() -> {
                SalesOrder result = sales.create(customer, "BE", "DAP", DocumentType.FACTUUR);
                selected.countDown();
                await(release);
                return result;
            }));
            try {
                assertTrue(selected.await(10, TimeUnit.SECONDS));
                var next = threads.submit(() -> transaction(() -> {
                    contender.countDown();
                    return sales.create(customer, "BE", "DAP", DocumentType.FACTUUR);
                }));
                assertTrue(contender.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> next.get(150, TimeUnit.MILLISECONDS), "allocation waits for the first transaction");
                release.countDown();
                SalesOrder a = first.get(10, TimeUnit.SECONDS), b = next.get(10, TimeUnit.SECONDS);
                assertNotEquals(a.id(), b.id());
                assertEquals(sequence(a.number()) + 1, sequence(b.number()));
                assertEquals(a.number(), transaction(() -> sales.get(a.id())).number());
                assertEquals(b.number(), transaction(() -> sales.get(b.id())).number());
            } finally { release.countDown(); }
        }
    }

    @Test
    void rollbackReleasesTheNumberForTheWaitingTransaction() throws Exception {
        long customer = customer();
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> rolledBackNumber = new AtomicReference<>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> transaction(() -> {
                rolledBackNumber.set(sales.create(customer, "BE", "DAP", DocumentType.FACTUUR).number());
                selected.countDown();
                await(release);
                throw new IllegalStateException("Intentional rollback");
            }));
            try {
                assertTrue(selected.await(10, TimeUnit.SECONDS));
                var next = threads.submit(() -> transaction(() -> sales.create(customer, "BE", "DAP", DocumentType.FACTUUR)));
                release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
                SalesOrder saved = next.get(10, TimeUnit.SECONDS);
                assertEquals(rolledBackNumber.get(), saved.number(), "a rolled-back draft never reserved a committed number");
            } finally { release.countDown(); }
        }
    }

    private long customer() {
        return transaction(() -> customers.create(new Customer(null, "Number race " + UUID.randomUUID(), "Buyer", null, null,
                "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now())).id());
    }

    private static <T> T transaction(Callable<T> work) {
        var context = Arc.container().requestContext();
        boolean activate = !context.isActive();
        if (activate) context.activate();
        try { return QuarkusTransaction.requiringNew().call(work); }
        finally { if (activate) context.terminate(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static int sequence(String number) { return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1)); }
}
