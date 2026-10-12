package be.enrosed.finance.odoo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;

/** Short local transactions only. A renewable lease serializes imports across application instances. */
@ApplicationScoped
public class OdooSyncStateStore {
    @Inject EntityManager em;
    public record Snapshot(Instant lastCheckAt,String lastCheckCode,Instant lastSyncAt,String lastSyncJson) {}
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Snapshot snapshot(String id) {
        var row=em.find(OdooSyncStateEntity.class,id);
        return row==null?new Snapshot(null,null,null,null):new Snapshot(row.lastCheckAt,row.lastCheckCode,row.lastSyncAt,row.lastSyncJson);
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void acquire(String id,String owner) {
        acquireFor(id,owner,180);
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void acquireFor(String id,String owner,int seconds) {
        if(seconds<1||seconds>900)throw new OdooFailure("INVALID_REQUEST");
        var row=locked(id);
        if(row.leaseUntil!=null&&row.leaseUntil.isAfter(Instant.now()))throw new OdooFailure("BUSY");
        row.leaseOwner=owner;row.leaseUntil=Instant.now().plusSeconds(seconds);em.flush();
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void renew(String id,String owner) {
        var row=em.find(OdooSyncStateEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!Objects.equals(row.leaseOwner,owner)||row.leaseUntil==null||row.leaseUntil.isBefore(Instant.now()))
            throw new OdooFailure("LEASE_LOST");
        row.leaseUntil=Instant.now().plusSeconds(180);
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void finish(String id,String owner,String report) {
        var row=em.find(OdooSyncStateEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!Objects.equals(row.leaseOwner,owner))throw new OdooFailure("LEASE_LOST");
        row.leaseOwner=null;row.leaseUntil=null;row.lastSyncAt=Instant.now();row.lastSyncJson=report;
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void checked(String id,String code) {
        var row=locked(id);row.lastCheckAt=Instant.now();row.lastCheckCode=code;
    }
    private OdooSyncStateEntity locked(String id) {
        var row=em.find(OdooSyncStateEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null) { row=new OdooSyncStateEntity();row.id=id;em.persist(row);em.flush(); }
        return row;
    }
}
