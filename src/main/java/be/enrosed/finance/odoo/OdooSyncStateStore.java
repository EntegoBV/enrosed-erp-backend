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
    static final int BANK_INTERVAL_HOURS=12;
    static final int BANK_RETRY_MINUTES=30;
    @Inject EntityManager em;
    public record Snapshot(Instant lastCheckAt,String lastCheckCode,Instant lastSyncAt,String lastSyncJson) {}
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Snapshot snapshot(String id) {
        var row=em.find(OdooSyncStateEntity.class,id);
        return row==null?new Snapshot(null,null,null,null):new Snapshot(row.lastCheckAt,row.lastCheckCode,row.lastSyncAt,row.lastSyncJson);
    }
    public record BankSchedule(Instant lastAttemptAt,Instant lastSuccessAt,Instant nextRunAt,String lastCode) {}

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public BankSchedule bankSchedule(String id) {
        var row=em.find(OdooSyncStateEntity.class,id);
        return row==null?new BankSchedule(null,null,null,null)
                :new BankSchedule(row.bankLastAttemptAt,row.bankLastSuccessAt,row.bankNextAutoAt,row.bankLastCode);
    }

    /** Due check and the existing bank lease are claimed in one transaction on the same row. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean acquireBank(String id,String owner,boolean automatic) {
        var row=locked(id);Instant now=Instant.now();
        if(automatic&&row.bankNextAutoAt!=null&&row.bankNextAutoAt.isAfter(now))return false;
        if(row.leaseUntil!=null&&row.leaseUntil.isAfter(now)) {
            if(automatic)return false;
            throw new OdooFailure("BUSY");
        }
        row.leaseOwner=owner;row.leaseUntil=now.plusSeconds(180);
        row.bankLastAttemptAt=now;row.bankLastCode="RUNNING";
        // Persist the recovery time before network I/O. A restart cannot immediately repeat a claimed run.
        row.bankNextAutoAt=now.plusSeconds(BANK_RETRY_MINUTES*60L);
        em.flush();return true;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void finishBank(String id,String owner,String report,boolean complete,String code) {
        var row=em.find(OdooSyncStateEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!Objects.equals(row.leaseOwner,owner)||row.leaseUntil==null||row.leaseUntil.isBefore(Instant.now()))
            throw new OdooFailure("LEASE_LOST");
        Instant now=Instant.now();
        row.leaseOwner=null;row.leaseUntil=null;row.lastSyncAt=now;row.lastSyncJson=report;
        row.bankLastCode=code;
        if(complete)row.bankLastSuccessAt=now;
        row.bankNextAutoAt=now.plusSeconds(complete?BANK_INTERVAL_HOURS*3600L:BANK_RETRY_MINUTES*60L);
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
