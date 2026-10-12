package be.enrosed.finance.odoo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;

@ApplicationScoped
public class OdooOutboxStore {
    @Inject EntityManager em;
    public record Saved(long salesOrderId,String sourceNamespace,String marker,String snapshotHash,String snapshotJson,
                        String state,Long odooMoveId,String peppolState,String messageCode,Instant updatedAt) {}
    @Transactional(Transactional.TxType.REQUIRES_NEW) public Saved get(long id) {
        var row=em.find(OdooOutboxEntity.class,id);return row==null?null:saved(row);
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Saved preview(long id,String namespace,String marker,String hash,String json) {
        var row=em.find(OdooOutboxEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row!=null) {
            if(!Objects.equals(namespace,row.sourceNamespace))throw new OdooFailure("OUTBOX_SOURCE_CHANGED");
            if(!"PREVIEW".equals(row.state))throw new OdooFailure("OUTBOX_RETRY_BLOCKED");
        } else {row=new OdooOutboxEntity();row.salesOrderId=id;em.persist(row);}
        row.sourceNamespace=namespace;row.marker=marker;row.snapshotHash=hash;row.snapshotJson=json;
        row.state="PREVIEW";row.messageCode=null;row.updatedAt=Instant.now();em.flush();return saved(row);
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Saved transition(long id,String hash,String state,Long remoteId,String peppolState,String code) {
        var row=em.find(OdooOutboxEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!Objects.equals(hash,row.snapshotHash))throw new OdooFailure("OUTBOX_CHANGED");
        row.state=state;if(remoteId!=null)row.odooMoveId=remoteId;
        row.peppolState=peppolState;row.messageCode=code;row.updatedAt=Instant.now();em.flush();return saved(row);
    }
    private Saved saved(OdooOutboxEntity row) {return new Saved(row.salesOrderId,row.sourceNamespace,row.marker,row.snapshotHash,
            row.snapshotJson,row.state,row.odooMoveId,row.peppolState,row.messageCode,row.updatedAt);}
}
