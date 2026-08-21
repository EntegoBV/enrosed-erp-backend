package be.enrosed.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.hibernate.Session;

import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes first-start seed/outbox creation across replicas.
 *
 * PostgreSQL's transaction-scoped advisory lock spans application instances. H2 tests use the
 * same transaction lifetime with a JVM lock so concurrent contract tests exercise the invariant.
 */
@ApplicationScoped
public class CatalogMutationLock {
    private static final long LOCK_KEY = 0x454E524F534544L; // "ENROSED"
    private static final ReentrantLock LOCAL = new ReentrantLock(true);

    private final EntityManager entityManager;
    private final TransactionSynchronizationRegistry transactions;

    public CatalogMutationLock(
            EntityManager entityManager,
            TransactionSynchronizationRegistry transactions) {
        this.entityManager = entityManager;
        this.transactions = transactions;
    }

    /** Must be called inside the caller's mutation transaction. */
    public void acquire() {
        String database = entityManager.unwrap(Session.class).doReturningWork(connection ->
                connection.getMetaData().getDatabaseProductName());
        if (database != null && database.toLowerCase(Locale.ROOT).contains("postgresql")) {
            entityManager.createNativeQuery("select pg_advisory_xact_lock(?1)")
                    .setParameter(1, LOCK_KEY).getSingleResult();
            return;
        }
        if (LOCAL.isHeldByCurrentThread()) return;
        LOCAL.lock();
        try {
            int status = transactions.getTransactionStatus();
            if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
                throw new IllegalStateException("Catalogusmutatielock vereist een actieve transactie");
            }
            transactions.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() {}
                @Override public void afterCompletion(int ignored) { LOCAL.unlock(); }
            });
        } catch (RuntimeException exception) {
            LOCAL.unlock();
            throw exception;
        }
    }
}
