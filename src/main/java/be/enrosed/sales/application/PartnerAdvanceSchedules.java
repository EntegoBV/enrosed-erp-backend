package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceAgreementEntity;
import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceInstalmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class PartnerAdvanceSchedules {
    @Inject EntityManager entities;
    public record Agreement(long purchaseOrderId, long partnerCustomerId, BigDecimal externalCostEur,
                            BigDecimal financingPct, BigDecimal agreedAmountEur, PartnerAdvanceBasis.Kind financingBasis,
                            BigDecimal financingBasisEur) {
        public Agreement {
            financingBasis = financingBasis == null ? PartnerAdvanceBasis.Kind.EXTERNAL_FORECAST : financingBasis;
            financingBasisEur = externalCostEur;
        }
        public Agreement(long purchaseOrderId, long partnerCustomerId, BigDecimal externalCostEur,
                         BigDecimal financingPct, BigDecimal agreedAmountEur, PartnerAdvanceBasis.Kind financingBasis) {
            this(purchaseOrderId, partnerCustomerId, externalCostEur, financingPct, agreedAmountEur, financingBasis, externalCostEur);
        }
        /** Historical fixtures and stored agreements used external cost before the pricing-total correction. */
        public Agreement(long purchaseOrderId, long partnerCustomerId, BigDecimal externalCostEur,
                         BigDecimal financingPct, BigDecimal agreedAmountEur) {
            this(purchaseOrderId, partnerCustomerId, externalCostEur, financingPct, agreedAmountEur,
                    PartnerAdvanceBasis.Kind.EXTERNAL_FORECAST);
        }
    }
    public record Row(Long id, long purchaseOrderId, int position, String label, BigDecimal percentage,
                      BigDecimal amountEur, LocalDate dueDate, Long invoiceId) {}

    public Agreement find(long purchaseId) {
        var row = entities.find(PartnerAdvanceAgreementEntity.class, purchaseId);
        return row == null ? null : new Agreement(row.purchaseOrderId, row.partnerCustomerId,
                row.externalCostEur, row.financingPct, row.agreedAmountEur, row.financingBasis);
    }
    public void save(Agreement agreement) {
        var row = entities.find(PartnerAdvanceAgreementEntity.class, agreement.purchaseOrderId());
        boolean fresh = row == null;
        if (fresh) row = new PartnerAdvanceAgreementEntity();
        row.purchaseOrderId = agreement.purchaseOrderId(); row.partnerCustomerId = agreement.partnerCustomerId();
        row.externalCostEur = agreement.externalCostEur(); row.financingPct = agreement.financingPct();
        row.agreedAmountEur = agreement.agreedAmountEur(); row.financingBasis = agreement.financingBasis(); row.updatedAt = Instant.now();
        if (fresh) entities.persist(row);
        entities.flush();
    }
    public List<Row> rows(long purchaseId) {
        return entities.createQuery("from PartnerAdvanceInstalmentEntity r where r.purchaseOrderId=:id order by r.position,r.id", PartnerAdvanceInstalmentEntity.class)
                .setParameter("id", purchaseId).getResultList().stream().map(PartnerAdvanceSchedules::domain).toList();
    }
    public Row forInvoice(long invoiceId) {
        return entities.createQuery("from PartnerAdvanceInstalmentEntity r where r.salesOrderId=:id", PartnerAdvanceInstalmentEntity.class)
                .setParameter("id", invoiceId).getResultStream().findFirst().map(PartnerAdvanceSchedules::domain).orElse(null);
    }
    public Row save(Row value) {
        var row = value.id() == null ? new PartnerAdvanceInstalmentEntity() : entities.find(PartnerAdvanceInstalmentEntity.class, value.id());
        row.purchaseOrderId = value.purchaseOrderId(); row.position = value.position(); row.label = value.label();
        row.percentage = value.percentage(); row.amountEur = value.amountEur(); row.dueDate = value.dueDate(); row.salesOrderId = value.invoiceId();
        if (row.id == null) entities.persist(row);
        entities.flush(); return domain(row);
    }
    public void delete(long rowId) {
        var row = entities.find(PartnerAdvanceInstalmentEntity.class, rowId);
        if (row != null) entities.remove(row);
    }
    public void detachInvoice(long invoiceId) {
        entities.createQuery("from PartnerAdvanceInstalmentEntity r where r.salesOrderId=:id", PartnerAdvanceInstalmentEntity.class)
                .setParameter("id", invoiceId).getResultList().forEach(row -> row.salesOrderId = null);
        entities.flush();
    }
    /** A plan without documents may be removed with its purchase order. Financial history may not. */
    public void deleteForPurchase(long purchaseId) {
        if (rows(purchaseId).stream().anyMatch(row -> row.invoiceId() != null))
            throw new be.enrosed.shared.BusinessRuleException("De termijnplanning bevat facturen; archiveer de container om de historie te bewaren");
        entities.createQuery("delete from PartnerAdvanceInstalmentEntity r where r.purchaseOrderId=:id")
                .setParameter("id", purchaseId).executeUpdate();
        var agreement = entities.find(PartnerAdvanceAgreementEntity.class, purchaseId);
        if (agreement != null) entities.remove(agreement);
        entities.flush();
    }
    private static Row domain(PartnerAdvanceInstalmentEntity row) {
        return new Row(row.id, row.purchaseOrderId, row.position, row.label, row.percentage, row.amountEur, row.dueDate, row.salesOrderId);
    }
}
