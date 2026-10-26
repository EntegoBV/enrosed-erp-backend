package be.enrosed.finance.invoices;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.hibernate.JDBCException;

/**
 * Money-free marks on incoming Odoo invoices ("Afgehandeld" / "Hoort bij container"), one per (namespace, move).
 * A mark never creates a cost, payment, bank link or Odoo write; the finder's rules (who may mark what) live in the
 * finder service, this store only keeps the rows valid for the migration's checks.
 */
@ApplicationScoped
public class OdooDocumentMarks {
    public static final String HANDLED="HANDLED";
    public static final String CONTAINER="CONTAINER";
    static final int NOTE_MAX=200;

    @Inject EntityManager em;
    @Inject TransactionManager transactions;

    public record Mark(String namespace, long moveId, String resolution, Long purchaseOrderId, String note,
                       Instant markedAt, String markedBy) {}

    /** Every mark of the namespace by move id; empty when the namespace is blank (Odoo not connected). */
    @Transactional
    public Map<Long, Mark> all(String namespace) {
        if(namespace==null||namespace.isBlank())return Map.of();
        Map<Long,Mark> marks=new LinkedHashMap<>();
        for(OdooDocumentMarkEntity row:em.createQuery(
                        "from OdooDocumentMarkEntity m where m.namespace=:namespace order by m.moveId",OdooDocumentMarkEntity.class)
                .setParameter("namespace",namespace).getResultList())
            marks.put(row.moveId,mark(row));
        return Collections.unmodifiableMap(marks);
    }

    /**
     * Creates or replaces the mark of this move. HANDLED takes no order; CONTAINER needs one. Runs in the caller's
     * transaction, else in its own. Two first marks of one move at once: the one that finds the other's row on the key
     * replaces it (one retry in a new transaction; only possible when this call owns its transaction).
     */
    public Mark put(String namespace, long moveId, String resolution, Long purchaseOrderId, String note, String actor) {
        if(namespace==null||namespace.isBlank()||namespace.length()>64||moveId<=0)
            throw new IllegalArgumentException("A mark needs an Odoo namespace and move id");
        if(!HANDLED.equals(resolution)&&!CONTAINER.equals(resolution))
            throw new IllegalArgumentException("Unknown mark resolution");
        if(CONTAINER.equals(resolution)&&purchaseOrderId==null||HANDLED.equals(resolution)&&purchaseOrderId!=null)
            throw new IllegalArgumentException("Only a container mark carries a purchase order");
        String text=note==null||note.isBlank()?null:note.strip();
        if(text!=null&&text.length()>NOTE_MAX)throw new IllegalArgumentException("A mark note is at most 200 characters");
        String by=actor==null||actor.isBlank()?"systeem":actor.strip();
        String markedBy=by.length()>100?by.substring(0,100):by;
        boolean own=!inTransaction();
        try {
            return QuarkusTransaction.joiningExisting().call(()->write(namespace,moveId,resolution,purchaseOrderId,text,markedBy));
        } catch(RuntimeException failed) {
            if(!own||!duplicateKey(failed))throw failed;
            return QuarkusTransaction.requiringNew().call(()->write(namespace,moveId,resolution,purchaseOrderId,text,markedBy));
        }
    }

    private Mark write(String namespace, long moveId, String resolution, Long purchaseOrderId, String text, String markedBy) {
        String id=key(namespace,moveId);
        OdooDocumentMarkEntity row=em.find(OdooDocumentMarkEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        boolean created=row==null;
        if(created) {
            row=new OdooDocumentMarkEntity();
            row.id=id;row.namespace=namespace;row.moveId=moveId;
        }
        row.resolution=resolution;
        row.purchaseOrderId=purchaseOrderId;
        row.note=text;
        row.markedAt=Instant.now();
        row.markedBy=markedBy;
        if(created) {
            em.persist(row);
            em.flush(); // a concurrent first mark of this move surfaces here, not at commit
        }
        return mark(row);
    }

    private boolean inTransaction() {
        try {
            return transactions.getStatus()!=Status.STATUS_NO_TRANSACTION;
        } catch(SystemException unknown) {
            return true; // unknown: never retry inside someone else's transaction
        }
    }

    /** A unique-key violation (SQLState 23505 in PostgreSQL and H2) anywhere in the cause chain. */
    static boolean duplicateKey(Throwable failure) {
        Throwable cause=failure;
        for(int depth=0;cause!=null&&depth<20;depth++,cause=cause.getCause()) {
            if(cause instanceof SQLException sql&&"23505".equals(sql.getSQLState()))return true;
            if(cause instanceof JDBCException jdbc&&"23505".equals(jdbc.getSQLState()))return true;
        }
        return false;
    }

    /** Removes the mark; false when there was none (idempotent). */
    @Transactional
    public boolean clear(String namespace, long moveId) {
        if(namespace==null||namespace.isBlank()||moveId<=0)return false;
        OdooDocumentMarkEntity row=em.find(OdooDocumentMarkEntity.class,key(namespace,moveId));
        if(row==null)return false;
        em.remove(row);
        return true;
    }

    static String key(String namespace,long moveId) { return namespace+":"+moveId; }

    private static Mark mark(OdooDocumentMarkEntity row) {
        return new Mark(row.namespace,row.moveId,row.resolution,row.purchaseOrderId,row.note,row.markedAt,row.markedBy);
    }
}
