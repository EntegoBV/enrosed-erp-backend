package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceQuoteEntity;
import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceQuoteRowEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The arrangements actually quoted to the customer, independent of subsequent plan edits. */
@ApplicationScoped
public class PartnerAdvanceQuotes {
    @Inject EntityManager entities;

    public record Row(Long scheduleRowId, String label, BigDecimal percentage, BigDecimal amountEur, LocalDate dueDate) {}
    public record Snapshot(long purchaseOrderId, BigDecimal financingPct, BigDecimal agreedAmountEur,
                           BigDecimal sharePct, List<Row> rows) {
        public Snapshot { rows = List.copyOf(rows); }
    }

    public Snapshot find(long salesOrderId) {
        var entity = entities.find(PartnerAdvanceQuoteEntity.class, salesOrderId);
        if (entity == null) return null;
        var rows = entities.createQuery("from PartnerAdvanceQuoteRowEntity r where r.salesOrderId=:id order by r.position,r.id",
                PartnerAdvanceQuoteRowEntity.class).setParameter("id", salesOrderId).getResultList();
        return new Snapshot(entity.purchaseOrderId, entity.financingPct, entity.agreedAmountEur, entity.sharePct,
                rows.stream().map(row -> new Row(row.scheduleRowId, row.label, row.percentage, row.amountEur, row.dueDate)).toList());
    }

    public void save(long salesOrderId, Snapshot snapshot) {
        if (find(salesOrderId) != null) throw new IllegalStateException("Quoted payment arrangements are immutable");
        var entity = new PartnerAdvanceQuoteEntity();
        entity.salesOrderId = salesOrderId; entity.purchaseOrderId = snapshot.purchaseOrderId();
        entity.financingPct = snapshot.financingPct(); entity.agreedAmountEur = snapshot.agreedAmountEur();
        entity.sharePct = snapshot.sharePct(); entities.persist(entity); entities.flush();
        int position = 0;
        for (var row : snapshot.rows()) {
            var detail = new PartnerAdvanceQuoteRowEntity(); detail.salesOrderId = salesOrderId;
            detail.scheduleRowId = row.scheduleRowId(); detail.position = position++; detail.label = row.label();
            detail.percentage = row.percentage(); detail.amountEur = row.amountEur(); detail.dueDate = row.dueDate();
            entities.persist(detail);
        }
        entities.flush();
    }

    public List<Long> quoteIds(long purchaseId) {
        String orders = entities.getMetamodel().entity(be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity.class).getName();
        // Keep the immutable snapshot for restore, but never let a trashed quote govern the active plan.
        return entities.createQuery("select q.salesOrderId from PartnerAdvanceQuoteEntity q where q.purchaseOrderId=:id"
                        + " and exists (select o.id from " + orders + " o where o.id=q.salesOrderId) order by q.salesOrderId desc", Long.class)
                .setParameter("id", purchaseId).getResultList();
    }

    public void delete(long salesOrderId) {
        entities.createQuery("delete from PartnerAdvanceQuoteRowEntity r where r.salesOrderId=:id")
                .setParameter("id", salesOrderId).executeUpdate();
        var entity = entities.find(PartnerAdvanceQuoteEntity.class, salesOrderId);
        if (entity != null) entities.remove(entity);
    }
}
