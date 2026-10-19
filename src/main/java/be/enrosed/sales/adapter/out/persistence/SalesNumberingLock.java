package be.enrosed.sales.adapter.out.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.hibernate.Session;

import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * Keeps max-plus-one allocation and a manual number change atomic with the document save.
 * PostgreSQL uses a transaction advisory lock across replicas. H2's local development/test
 * database uses the same transaction lifetime; no schema or historical number is changed.
 */
@ApplicationScoped
public class SalesNumberingLock {
    private static final long LOCK_KEY = 0x454E52534E554DL; // "ENRSNUM", separate from the catalog lock.
    private static final String RESOURCE_KEY = SalesNumberingLock.class.getName();
    private static final Semaphore LOCAL = new Semaphore(1, true);
    private final EntityManager entities;
    private final TransactionSynchronizationRegistry transactions;

    public SalesNumberingLock(EntityManager entities, TransactionSynchronizationRegistry transactions) {
        this.entities = entities;
        this.transactions = transactions;
    }

    public void acquire() {
        int status = transactions.getTransactionStatus();
        // A failed transaction cannot publish a number. Some rollback-only tests continue inspecting it.
        if (status == Status.STATUS_MARKED_ROLLBACK) return;
        if (status != Status.STATUS_ACTIVE)
            throw new IllegalStateException("Sales numbering requires an active transaction");
        if (transactions.getResource(RESOURCE_KEY) != null) return;
        String database = entities.unwrap(Session.class).doReturningWork(connection ->
                connection.getMetaData().getDatabaseProductName()).toLowerCase(Locale.ROOT);
        if (database.contains("postgresql")) {
            entities.createNativeQuery("select pg_advisory_xact_lock(?1)")
                    .setParameter(1, LOCK_KEY).setFlushMode(FlushModeType.COMMIT).getSingleResult();
            transactions.putResource(RESOURCE_KEY, Boolean.TRUE);
            return;
        }
        if (!database.equals("h2")) throw new IllegalStateException("Unsupported sales numbering database");
        try {
            LOCAL.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reserving a sales document number", interrupted);
        }
        try {
            transactions.putResource(RESOURCE_KEY, Boolean.TRUE);
            transactions.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() {}
                @Override public void afterCompletion(int ignored) { LOCAL.release(); }
            });
        } catch (RuntimeException failure) {
            LOCAL.release();
            throw failure;
        }
    }
}
