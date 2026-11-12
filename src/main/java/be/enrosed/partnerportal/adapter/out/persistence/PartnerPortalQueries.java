package be.enrosed.partnerportal.adapter.out.persistence;

import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesPurpose;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small read-only projections the portal needs from other features' tables, so it never loads
 * the eager sales-order graph or the whole family content: the state of linked sales documents,
 * the partner settlements that were issued without a shipment, and family names.
 */
@ApplicationScoped
public class PartnerPortalQueries {

    /** A sales document as the open-part rule sees it; trashed documents are never returned. */
    public record DocumentState(long id, String number, QuoteStatus status) {}

    /** An issued partner settlement whose pieces were never booked out of stock. */
    public record UnshippedSettlement(long id, String number) {}

    private static final List<QuoteStatus> ISSUED = List.of(
            QuoteStatus.UITGEREIKT, QuoteStatus.VERZONDEN, QuoteStatus.BEKEKEN, QuoteStatus.BETAALD);

    private final EntityManager entityManager;

    public PartnerPortalQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The documents with these ids that still exist outside the trash (the entity's SQL restriction). */
    public Map<Long, DocumentState> documentStates(Collection<Long> ids) {
        Map<Long, DocumentState> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) return result;
        List<Object[]> rows = entityManager.createQuery("select o.id, o.number, o.status from " + orders()
                        + " o where o.id in :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        for (Object[] row : rows) {
            long id = ((Number) row[0]).longValue();
            result.put(id, new DocumentState(id, (String) row[1], (QuoteStatus) row[2]));
        }
        return result;
    }

    /**
     * Issued partner settlements of this partner that are not report-backed and have no
     * goodsShippedAt: their pieces still lie in stock although they are sold. A legacy row
     * without a purpose counts when it is flagged as the settlement of a partner container.
     */
    public List<UnshippedSettlement> unshippedSettlements(long customerId) {
        String query = "select o.id, o.number from " + orders() + " o"
                + " where o.customerId = :customer and o.docType = :invoice and o.status in :issued"
                + " and o.goodsShippedAt is null"
                + " and (o.purpose = :settlement"
                + " or (o.purpose is null and o.partnerPurchaseOrderId is not null and o.partnerSettlement = true))"
                + " and not exists (select p.id from PartnerSaleInvoicePartEntity p where p.salesOrderId = o.id)"
                + " order by o.id";
        List<Object[]> rows = entityManager.createQuery(query, Object[].class)
                .setParameter("customer", customerId)
                .setParameter("invoice", DocumentType.FACTUUR)
                .setParameter("issued", ISSUED)
                .setParameter("settlement", SalesPurpose.PARTNER_SETTLEMENT)
                .getResultList();
        return rows.stream()
                .map(row -> new UnshippedSettlement(((Number) row[0]).longValue(), (String) row[1]))
                .toList();
    }

    /** The sales order entity is a nested class: its JPQL name comes from the metamodel, as elsewhere. */
    private String orders() {
        return entityManager.getMetamodel().entity(SalesEntities.SalesOrderEntity.class).getName();
    }

    /** Ids of the (not trashed) containers this customer is the partner of, ascending: the lock order. */
    public List<Long> partnerContainerIds(long customerId) {
        String purchases = entityManager.getMetamodel()
                .entity(be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity.class).getName();
        return entityManager.createQuery("select o.id from " + purchases
                        + " o where o.partnerCustomerId = :customer order by o.id", Long.class)
                .setParameter("customer", customerId)
                .getResultList();
    }

    /** Family id to its name, for the tiles' grouping. */
    public Map<Long, String> familyNames(Collection<Long> familyIds) {
        Map<Long, String> result = new HashMap<>();
        if (familyIds == null || familyIds.isEmpty()) return result;
        List<Object[]> rows = entityManager.createQuery("""
                        select f.id, f.name from ProductFamilyEntity f where f.id in :ids""", Object[].class)
                .setParameter("ids", familyIds)
                .getResultList();
        for (Object[] row : rows) result.put(((Number) row[0]).longValue(), (String) row[1]);
        return result;
    }
}
