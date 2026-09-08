package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.PartnerSettlementEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;

@ApplicationScoped
public class PartnerSettlements {
    @Inject EntityManager entities;
    public record Snapshot(BigDecimal revenueEur, BigDecimal costEur, BigDecimal advanceEur) {}
    public Snapshot find(long id) {
        var row = entities.find(PartnerSettlementEntity.class, id);
        return row == null ? null : new Snapshot(row.revenueEur, row.costEur, row.advanceEur);
    }
    public void save(long id, long purchaseId, BigDecimal revenue, BigDecimal cost, BigDecimal advance) {
        var row = new PartnerSettlementEntity(); row.salesOrderId = id; row.purchaseOrderId = purchaseId;
        row.revenueEur = revenue; row.costEur = cost; row.advanceEur = advance;
        entities.persist(row); entities.flush();
    }
    public void delete(long id) { var row = entities.find(PartnerSettlementEntity.class, id); if (row != null) entities.remove(row); }
}
