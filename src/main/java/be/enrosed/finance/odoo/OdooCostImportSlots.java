package be.enrosed.finance.odoo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;

@ApplicationScoped
public class OdooCostImportSlots {
    @Inject EntityManager em;
    /** Reserve only a source identity. A later failed financial transaction leaves a safe, retryable empty slot. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void reserve(String namespace,long companyId,long moveId) {
        String id=namespace+":"+moveId;
        if(em.find(OdooCostImportEntity.class,id)!=null)return;
        var row=new OdooCostImportEntity();row.id=id;row.namespace=namespace;row.companyId=companyId;row.moveId=moveId;row.createdAt=Instant.now();em.persist(row);em.flush();
    }
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean exists(String id) {return em.find(OdooCostImportEntity.class,id)!=null;}
}
