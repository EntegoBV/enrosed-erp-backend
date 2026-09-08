package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.PartnerSettlementEntity;
import be.enrosed.sales.adapter.out.persistence.PartnerSettlementLineEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class PartnerSettlements {
    @Inject EntityManager entities;
    public record Line(Long productId, int quantity, BigDecimal proceedsEur, BigDecimal costEur,
                       BigDecimal revenueEur, BigDecimal advanceEur) {}
    public record Snapshot(BigDecimal revenueEur, BigDecimal costEur, BigDecimal advanceEur,
                           boolean finalSettlement, List<Line> lines) {
        public Snapshot(BigDecimal revenueEur, BigDecimal costEur, BigDecimal advanceEur) {
            this(revenueEur, costEur, advanceEur, true, List.of());
        }
    }
    public Snapshot find(long id) {
        var row = entities.find(PartnerSettlementEntity.class, id);
        return row == null ? null : new Snapshot(row.revenueEur, row.costEur, row.advanceEur,
                row.finalSettlement == null || row.finalSettlement,
                entities.createQuery("from PartnerSettlementLineEntity l where l.salesOrderId=:id order by l.id", PartnerSettlementLineEntity.class)
                        .setParameter("id", id).getResultList().stream().map(line -> new Line(line.productId, line.quantity,
                                line.proceedsEur, line.costEur, line.revenueEur, line.advanceEur)).toList());
    }
    public void save(long id, long purchaseId, BigDecimal revenue, BigDecimal cost, BigDecimal advance) {
        save(id, purchaseId, new Snapshot(revenue, cost, advance));
    }
    public void save(long id, long purchaseId, Snapshot snapshot) {
        var row = new PartnerSettlementEntity(); row.salesOrderId = id; row.purchaseOrderId = purchaseId;
        row.revenueEur = snapshot.revenueEur(); row.costEur = snapshot.costEur(); row.advanceEur = snapshot.advanceEur();
        row.finalSettlement = snapshot.finalSettlement();
        entities.persist(row); entities.flush();
        for (var line : snapshot.lines()) {
            var detail = new PartnerSettlementLineEntity(); detail.salesOrderId = id; detail.productId = line.productId();
            detail.quantity = line.quantity(); detail.proceedsEur = line.proceedsEur(); detail.costEur = line.costEur();
            detail.revenueEur = line.revenueEur(); detail.advanceEur = line.advanceEur(); entities.persist(detail);
        }
        entities.flush();
    }
    public void delete(long id) {
        entities.createQuery("delete from PartnerSettlementLineEntity l where l.salesOrderId=:id").setParameter("id", id).executeUpdate();
        var row = entities.find(PartnerSettlementEntity.class, id); if (row != null) entities.remove(row);
    }
}
