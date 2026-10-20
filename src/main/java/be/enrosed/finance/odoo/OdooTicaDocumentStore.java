package be.enrosed.finance.odoo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps source revisions and pending reasons separate from immutable settlement and financial records. */
@ApplicationScoped
public class OdooTicaDocumentStore {
    @Inject EntityManager em;
    public record Observed(String acceptedHash,Long settlementId,String acceptedSourceState) {
        public Observed(String acceptedHash,Long settlementId) {this(acceptedHash,settlementId,null);}
    }
    public record DocumentStatus(long moveId,String reference,String sourceState,String code,Instant lastSeenAt,Long settlementId) {}

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Observed observe(OdooDocumentService.PurchaseSnapshot source) {
        String id=OdooTicaCreditnoteImportService.sourceKey(source.namespace(),source.document().id());
        var row=em.find(OdooTicaDocumentEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        boolean created=row==null;
        if(created) {
            row=new OdooTicaDocumentEntity();row.id=id;row.sourceNamespace=source.namespace();row.moveId=source.document().id();
            row.code="DISCOVERED";
        }
        row.reference=limited(source.document().reference(),200);row.sourceState=limited(source.document().state(),32);
        row.observedHash=source.hash();row.sourceUpdatedAt=limited(source.sourceUpdatedAt(),64);row.lastSeenAt=Instant.now();
        if(created)em.persist(row);
        return new Observed(row.acceptedHash,row.settlementId,row.acceptedSourceState);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void mark(String sourceKey,String code,Long settlementId,boolean accepted) {
        var row=em.find(OdooTicaDocumentEntity.class,sourceKey,LockModeType.PESSIMISTIC_WRITE);
        if(row==null)throw new OdooFailure("INVALID_RESPONSE");
        row.code=code;row.lastSeenAt=Instant.now();
        if(settlementId!=null)row.settlementId=settlementId;
        if(accepted) {row.acceptedHash=row.observedHash;row.acceptedSourceState=row.sourceState;row.lastImportedAt=Instant.now();}
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<DocumentStatus> pending(String namespace) {
        return em.createQuery("from OdooTicaDocumentEntity d where d.sourceNamespace=:namespace and d.code<>'IMPORTED' order by d.lastSeenAt desc",OdooTicaDocumentEntity.class)
                .setParameter("namespace",namespace).setMaxResults(100).getResultList().stream()
                .map(row->new DocumentStatus(row.moveId,row.reference,row.sourceState,row.code,row.lastSeenAt,row.settlementId)).toList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<String,Long> pendingReasons(String namespace) {
        Map<String,Long> counts=new LinkedHashMap<>();
        for(var row:em.createQuery("select d.code,count(d) from OdooTicaDocumentEntity d where d.sourceNamespace=:namespace and d.code<>'IMPORTED' group by d.code",Object[].class)
                .setParameter("namespace",namespace).getResultList())counts.put((String)row[0],(Long)row[1]);
        return Map.copyOf(counts);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> knownMoves(String namespace) {
        return em.createQuery("select d.moveId from OdooTicaDocumentEntity d where d.sourceNamespace=:namespace",Long.class)
                .setParameter("namespace",namespace).setMaxResults(5000).getResultList();
    }
    private static String limited(String value,int max) {return value==null?null:value.substring(0,Math.min(value.length(),max));}
}
