package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** One Panache repository per partner portal table. */
public final class PartnerPortalRepositories {

    private PartnerPortalRepositories() {}

    @ApplicationScoped
    public static class Logins implements PanacheRepositoryBase<PartnerLoginEntity, Long> {
        /** By the canonical (lower-case) username. */
        public Optional<PartnerLoginEntity> findByUsername(String canonicalUsername) {
            if (canonicalUsername == null || canonicalUsername.isBlank()) return Optional.empty();
            return find("username", canonicalUsername).firstResultOptional();
        }

        public Optional<PartnerLoginEntity> findByCustomer(long customerId) {
            return find("customerId", customerId).firstResultOptional();
        }
    }

    @ApplicationScoped
    public static class Sessions implements PanacheRepositoryBase<PartnerSessionEntity, Long> {
        public Optional<PartnerSessionEntity> findLive(long loginId, String tokenHash, Instant now) {
            return find("loginId = ?1 and tokenHash = ?2 and expiresAt > ?3", loginId, tokenHash, now)
                    .firstResultOptional();
        }

        public long deleteByLogin(long loginId) {
            return delete("loginId", loginId);
        }

        public long deleteExpired(long loginId, Instant now) {
            return delete("loginId = ?1 and expiresAt <= ?2", loginId, now);
        }

        /** Keeps the newest {@code max} sessions of the login and deletes the rest. */
        public void keepNewest(long loginId, int max) {
            List<PartnerSessionEntity> sessions = list("loginId", Sort.descending("createdAt", "id"), loginId);
            for (int index = max; index < sessions.size(); index++) sessions.get(index).delete();
        }
    }

    @ApplicationScoped
    public static class Settings implements PanacheRepositoryBase<PartnerPortalSettingsEntity, Long> {
        private static final Logger LOG = Logger.getLogger(Settings.class);

        /**
         * The partner lock: the settings row of this partner FOR UPDATE, created first (in a
         * transaction of its own, so a concurrent creator only loses the insert) when missing.
         * Must run inside the caller's transaction and before any container or product lock.
         */
        public PartnerPortalSettingsEntity lockOrCreate(long customerId) {
            if (findById(customerId) == null) {
                try {
                    QuarkusTransaction.requiringNew().run(() -> {
                        if (findById(customerId) != null) return;
                        PartnerPortalSettingsEntity row = new PartnerPortalSettingsEntity();
                        row.customerId = customerId;
                        row.updatedAt = Instant.now();
                        persist(row);
                        flush();
                    });
                } catch (RuntimeException concurrentInsert) {
                    /* Someone else created it a moment ago; the lock below finds theirs. */
                    LOG.debugf("Partnerinstellingen %d bestonden al", customerId);
                }
            }
            PartnerPortalSettingsEntity locked = findById(customerId, LockModeType.PESSIMISTIC_WRITE);
            if (locked == null) throw new IllegalStateException("Partner settings row could not be created");
            getEntityManager().refresh(locked);
            return locked;
        }
    }

    @ApplicationScoped
    public static class Reports implements PanacheRepositoryBase<PartnerSaleReportEntity, Long> {
        /**
         * Newest first; {@code before} is an exclusive id cursor. One extra row tells whether more exist.
         * Archive rows (revision_of set) are never listed, here or in any other list below.
         */
        public List<PartnerSaleReportEntity> page(long customerId, Long before, int limit) {
            if (before == null) {
                return find("customerId = ?1 and revisionOf is null", Sort.descending("id"), customerId)
                        .page(0, limit).list();
            }
            return find("customerId = ?1 and id < ?2 and revisionOf is null", Sort.descending("id"), customerId, before)
                    .page(0, limit).list();
        }

        public Optional<PartnerSaleReportEntity> findByClientRef(long customerId, String clientRef) {
            if (clientRef == null || clientRef.isBlank()) return Optional.empty();
            return find("customerId = ?1 and clientRef = ?2", customerId, clientRef).firstResultOptional();
        }

        public boolean existsForCustomer(long customerId) {
            return count("customerId", customerId) > 0;
        }

        /** Meldingen (voided included, archive rows not) that name this customer as the buyer, oldest first. */
        public List<Long> idsWithBuyer(long buyerCustomerId) {
            return getEntityManager().createQuery("""
                            select r.id from PartnerSaleReportEntity r
                            where r.buyerCustomerId = :buyer and r.revisionOf is null order by r.id""", Long.class)
                    .setParameter("buyer", buyerCustomerId)
                    .getResultList();
        }

        /** Every partner's meldingen (or one partner's), newest first, {@code before} an exclusive id cursor. */
        public List<PartnerSaleReportEntity> pageAll(Long customerId, Long before, int limit) {
            StringBuilder where = new StringBuilder("revisionOf is null");
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            if (customerId != null) {
                where.append(" and customerId = :customer");
                parameters.put("customer", customerId);
            }
            if (before != null) {
                where.append(" and id < :before");
                parameters.put("before", before);
            }
            return find(where.toString(), Sort.descending("id"), parameters).page(0, limit).list();
        }

        /**
         * The staff list's filter (round 2): every melding (withdrawn ones included, archive rows never) of
         * this partner, channel, sold-on period (both ends inclusive) and buyer, each criterion optional;
         * newest sold-on day first, then the higher id.
         */
        public List<PartnerSaleReportEntity> filtered(Long customerId, String channel, LocalDate from, LocalDate to,
                                                      Long buyerCustomerId) {
            StringBuilder where = new StringBuilder("revisionOf is null");
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            if (customerId != null) {
                where.append(" and customerId = :customer");
                parameters.put("customer", customerId);
            }
            if (channel != null) {
                where.append(" and channel = :channel");
                parameters.put("channel", channel);
            }
            if (from != null) {
                where.append(" and soldOn >= :from");
                parameters.put("from", from);
            }
            if (to != null) {
                where.append(" and soldOn <= :to");
                parameters.put("to", to);
            }
            if (buyerCustomerId != null) {
                where.append(" and buyerCustomerId = :buyer");
                parameters.put("buyer", buyerCustomerId);
            }
            return find(where.toString(), Sort.descending("soldOn", "id"), parameters).list();
        }

        /** Non-voided meldingen made at or after {@code since}, newest first. */
        public List<PartnerSaleReportEntity> liveCreatedSince(Instant since) {
            return list("voidedAt is null and revisionOf is null and createdAt >= ?1", Sort.descending("createdAt", "id"), since);
        }

        /**
         * Non-voided meldingen made or edited at or after {@code since} (greatest(created_at, updated_at)), newest
         * first by creation: the bell's window.
         */
        public List<PartnerSaleReportEntity> liveTouchedSince(Instant since) {
            return list("voidedAt is null and revisionOf is null and (createdAt >= ?1 or updatedAt >= ?1)",
                    Sort.descending("createdAt", "id"), since);
        }

        /** The buyers of this partner's live meldingen, the most recent melding first (at most {@code limit}). */
        public List<Long> recentBuyers(long customerId, int limit) {
            return getEntityManager().createQuery("""
                            select r.buyerCustomerId from PartnerSaleReportEntity r
                            where r.customerId = :customer and r.revisionOf is null and r.voidedAt is null
                              and r.buyerCustomerId is not null
                            group by r.buyerCustomerId order by max(r.id) desc""", Long.class)
                    .setParameter("customer", customerId)
                    .setMaxResults(limit)
                    .getResultList();
        }

        /** Non-voided meldingen sold in [from, to] (either end may be null), oldest first. */
        public List<PartnerSaleReportEntity> liveSoldBetween(LocalDate from, LocalDate to) {
            StringBuilder where = new StringBuilder("voidedAt is null and revisionOf is null");
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            if (from != null) {
                where.append(" and soldOn >= :from");
                parameters.put("from", from);
            }
            if (to != null) {
                where.append(" and soldOn <= :to");
                parameters.put("to", to);
            }
            return find(where.toString(), Sort.ascending("soldOn", "id"), parameters).list();
        }

        /** The newest created_at of a non-voided melding per partner. */
        public java.util.Map<Long, Instant> lastCreatedPerCustomer() {
            java.util.Map<Long, Instant> result = new java.util.HashMap<>();
            for (Object[] row : getEntityManager().createQuery("""
                            select r.customerId, max(r.createdAt) from PartnerSaleReportEntity r
                            where r.voidedAt is null and r.revisionOf is null group by r.customerId""", Object[].class)
                    .getResultList()) {
                if (row[0] != null) result.put(((Number) row[0]).longValue(), (Instant) row[1]);
            }
            return result;
        }
    }

    @ApplicationScoped
    public static class Lines implements PanacheRepositoryBase<PartnerSaleReportLineEntity, Long> {
        public List<PartnerSaleReportLineEntity> byReports(Collection<Long> reportIds) {
            if (reportIds == null || reportIds.isEmpty()) return List.of();
            return list("reportId in ?1", Sort.ascending("reportId", "entryNo", "id"), reportIds);
        }

        /**
         * Buyback parts of non-voided meldingen that mirror one of these sale parts, as
         * {mirrorsLineId, reportId} pairs.
         */
        public List<Object[]> liveBuybacksMirroring(Collection<Long> saleLineIds) {
            if (saleLineIds == null || saleLineIds.isEmpty()) return List.of();
            return getEntityManager().createQuery("""
                            select l.mirrorsLineId, l.reportId from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.lineType = :buyback
                              and l.mirrorsLineId in :ids""", Object[].class)
                    .setParameter("buyback", PartnerSaleReportLineEntity.BUYBACK)
                    .setParameter("ids", saleLineIds)
                    .getResultList();
        }

        /**
         * Sale parts of this partner's non-voided meldingen sold on or after {@code from}, as
         * {line, soldOn} pairs, newest melding first.
         */
        public List<Object[]> saleParts(long customerId, LocalDate from) {
            return getEntityManager().createQuery("""
                            select l, r.soldOn from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.customerId = :customer and r.voidedAt is null and r.revisionOf is null
                              and l.lineType = :sale and r.soldOn >= :from
                            order by r.soldOn desc, r.id desc, l.entryNo, l.id""", Object[].class)
                    .setParameter("customer", customerId)
                    .setParameter("sale", PartnerSaleReportLineEntity.SALE)
                    .setParameter("from", from)
                    .getResultList();
        }

        public boolean productReported(long customerId, long productId) {
            return count("customerId = ?1 and productId = ?2", customerId, productId) > 0;
        }

        public List<PartnerSaleReportLineEntity> byReport(long reportId) {
            return list("reportId", Sort.ascending("entryNo", "id"), reportId);
        }

        /**
         * SALE and BUYBACK parts of this partner's non-voided meldingen on these containers and products (open or
         * not: the caller applies the open-part rule).
         */
        public List<PartnerSaleReportLineEntity> containerParts(long customerId, Collection<Long> purchaseOrderIds,
                                                                Collection<Long> productIds) {
            if (purchaseOrderIds == null || purchaseOrderIds.isEmpty() || productIds == null || productIds.isEmpty()) {
                return List.of();
            }
            return getEntityManager().createQuery("""
                            select l from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.customerId = :customer
                              and l.lineType in :types and l.purchaseOrderId in :orders and l.productId in :products""",
                            PartnerSaleReportLineEntity.class)
                    .setParameter("customer", customerId)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK))
                    .setParameter("orders", purchaseOrderIds)
                    .setParameter("products", productIds)
                    .getResultList();
        }

        /**
         * Sale parts of one product in this partner's non-voided meldingen, as {line, soldOn} pairs, newest first
         * (sold_on, then melding, then the last-filled part): the order a buyback mirrors them in.
         */
        public List<Object[]> salePartsOfProduct(long customerId, long productId) {
            return getEntityManager().createQuery("""
                            select l, r.soldOn from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.customerId = :customer and r.voidedAt is null and r.revisionOf is null
                              and l.lineType = :sale and l.productId = :product
                            order by r.soldOn desc, r.id desc, l.id desc""", Object[].class)
                    .setParameter("customer", customerId)
                    .setParameter("sale", PartnerSaleReportLineEntity.SALE)
                    .setParameter("product", productId)
                    .getResultList();
        }

        /** Per sale part: the quantity live buyback parts (non-voided meldingen) already mirror. */
        public java.util.Map<Long, Integer> mirroredQuantities(Collection<Long> saleLineIds) {
            java.util.Map<Long, Integer> result = new java.util.HashMap<>();
            if (saleLineIds == null || saleLineIds.isEmpty()) return result;
            for (Object[] row : getEntityManager().createQuery("""
                            select l.mirrorsLineId, sum(l.quantity) from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.lineType = :buyback
                              and l.mirrorsLineId in :ids group by l.mirrorsLineId""", Object[].class)
                    .setParameter("buyback", PartnerSaleReportLineEntity.BUYBACK)
                    .setParameter("ids", saleLineIds)
                    .getResultList()) {
                result.put(((Number) row[0]).longValue(), row[1] == null ? 0 : ((Number) row[1]).intValue());
            }
            return result;
        }

        /**
         * Per sale part: the stock movements that took back out the pieces of withdrawn buybacks that
         * mirrored it (their REVERSAL rows). Those takes now hold pieces of the sale part, so pieces
         * of that part coming back restore into them too.
         */
        public java.util.Map<Long, List<Long>> reversedBuybackMovements(Collection<Long> saleLineIds) {
            java.util.Map<Long, List<Long>> result = new java.util.HashMap<>();
            if (saleLineIds == null || saleLineIds.isEmpty()) return result;
            for (Object[] row : getEntityManager().createQuery("""
                            select b.mirrorsLineId, rev.stockMovementId from PartnerSaleReportLineEntity rev,
                                   PartnerSaleReportLineEntity b
                            where rev.lineType = :reversal and rev.mirrorsLineId = b.id and b.lineType = :buyback
                              and b.mirrorsLineId in :ids and rev.stockMovementId is not null
                            order by rev.id""", Object[].class)
                    .setParameter("reversal", PartnerSaleReportLineEntity.REVERSAL)
                    .setParameter("buyback", PartnerSaleReportLineEntity.BUYBACK)
                    .setParameter("ids", saleLineIds)
                    .getResultList()) {
                result.computeIfAbsent(((Number) row[0]).longValue(), key -> new java.util.ArrayList<>())
                        .add(((Number) row[1]).longValue());
            }
            return result;
        }

        /** Invoice ids that SALE/BUYBACK parts of non-voided meldingen point at (the open-part rule checks them). */
        public List<Long> liveInvoiceIds() {
            return getEntityManager().createQuery("""
                            select distinct l.invoiceId from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.invoiceId is not null
                              and l.lineType in :types""", Long.class)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK))
                    .getResultList();
        }

        /**
         * SALE/BUYBACK parts of non-voided meldingen that are not invoiced or whose invoice is one of
         * {@code releasedInvoiceIds} (gone, trashed or cancelled): the open parts, as {line, report} pairs.
         */
        public List<Object[]> openParts(Collection<Long> releasedInvoiceIds) {
            boolean released = releasedInvoiceIds != null && !releasedInvoiceIds.isEmpty();
            var query = getEntityManager().createQuery("""
                            select l, r from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.lineType in :types
                              and (l.invoiceId is null""" + (released ? " or l.invoiceId in :released" : "") + ")",
                            Object[].class)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK));
            if (released) query.setParameter("released", releasedInvoiceIds);
            return query.getResultList();
        }
        /**
         * SALE and BUYBACK parts of non-voided meldingen on this container (any partner, open or not:
         * the caller applies the open-part rule).
         */
        public List<PartnerSaleReportLineEntity> partsOnContainer(long purchaseOrderId) {
            return getEntityManager().createQuery("""
                            select l from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and l.lineType in :types
                              and l.purchaseOrderId = :order""", PartnerSaleReportLineEntity.class)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK))
                    .setParameter("order", purchaseOrderId)
                    .getResultList();
        }

        /**
         * Among these meldingen, those with a SALE/BUYBACK part on a document that still holds it (invoiced,
         * and the invoice is not one of {@code releasedInvoiceIds}): such a melding counts as confirmed.
         */
        public List<Long> reportsWithHeldParts(Collection<Long> reportIds, Collection<Long> releasedInvoiceIds) {
            if (reportIds == null || reportIds.isEmpty()) return List.of();
            boolean released = releasedInvoiceIds != null && !releasedInvoiceIds.isEmpty();
            var query = getEntityManager().createQuery("select distinct l.reportId from PartnerSaleReportLineEntity l"
                            + " where l.reportId in :ids and l.lineType in :types and l.invoiceId is not null"
                            + (released ? " and l.invoiceId not in :released" : ""), Long.class)
                    .setParameter("ids", reportIds)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK));
            if (released) query.setParameter("released", releasedInvoiceIds);
            return query.getResultList();
        }

        /** Invoice ids that SALE/BUYBACK parts of this partner's non-voided meldingen point at. */
        public List<Long> liveInvoiceIdsOf(long customerId) {
            return getEntityManager().createQuery("""
                            select distinct l.invoiceId from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and r.customerId = :customer
                              and l.invoiceId is not null and l.lineType in :types""", Long.class)
                    .setParameter("customer", customerId)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK))
                    .getResultList();
        }

        /**
         * The ids of this partner's non-voided meldingen with a part that is not invoiced or whose
         * invoice is one of {@code releasedInvoiceIds} (gone, trashed or cancelled), sold on or before
         * {@code until} (null = any day): the meldingen Factureren takes by default.
         */
        public List<Long> reportsWithOpenParts(long customerId, Collection<Long> releasedInvoiceIds, LocalDate until) {
            boolean released = releasedInvoiceIds != null && !releasedInvoiceIds.isEmpty();
            var query = getEntityManager().createQuery("""
                            select distinct r.id from PartnerSaleReportLineEntity l, PartnerSaleReportEntity r
                            where r.id = l.reportId and r.voidedAt is null and r.revisionOf is null and r.customerId = :customer
                              and l.lineType in :types""" + (until == null ? "" : " and r.soldOn <= :until")
                            + " and (l.invoiceId is null" + (released ? " or l.invoiceId in :released" : "") + ")",
                            Long.class)
                    .setParameter("customer", customerId)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK));
            if (until != null) query.setParameter("until", until);
            if (released) query.setParameter("released", releasedInvoiceIds);
            return query.getResultList().stream().sorted().toList();
        }
    }

    @ApplicationScoped
    public static class InvoiceParts implements PanacheRepositoryBase<PartnerSaleInvoicePartEntity, Long> {
        /** The rows of one document, in the order they were written. */
        public List<PartnerSaleInvoicePartEntity> byOrder(long salesOrderId) {
            return list("salesOrderId", Sort.ascending("id"), salesOrderId);
        }

        /**
         * Per document, product and part type: the quantity its rows link, as {salesOrderId, productId,
         * lineType, quantity}. {@code salesOrderIds} null reads every report-backed document (one query
         * for a whole list); otherwise only these.
         */
        public List<Object[]> linkedQuantities(Collection<Long> salesOrderIds) {
            boolean some = salesOrderIds != null;
            if (some && salesOrderIds.isEmpty()) return List.of();
            var query = getEntityManager().createQuery("""
                            select p.salesOrderId, l.productId, l.lineType, sum(p.quantity)
                            from PartnerSaleInvoicePartEntity p, PartnerSaleReportLineEntity l
                            where l.id = p.lineId""" + (some ? " and p.salesOrderId in :ids" : "")
                            + " group by p.salesOrderId, l.productId, l.lineType", Object[].class);
            if (some) query.setParameter("ids", salesOrderIds);
            return query.getResultList();
        }

        /** The newest stock line of the parts linked to this document; null when none. */
        public Instant lastMovementAt(long salesOrderId) {
            return getEntityManager().createQuery("""
                            select max(m.at) from StockMovementEntity m where m.id in (
                              select l.stockMovementId from PartnerSaleInvoicePartEntity p, PartnerSaleReportLineEntity l
                              where l.id = p.lineId and p.salesOrderId = :id)""", Instant.class)
                    .setParameter("id", salesOrderId)
                    .getSingleResult();
        }

        /** The ids among these documents that are report-backed. */
        public List<Long> reportBacked(Collection<Long> salesOrderIds) {
            if (salesOrderIds == null || salesOrderIds.isEmpty()) return List.of();
            return getEntityManager().createQuery("""
                            select distinct p.salesOrderId from PartnerSaleInvoicePartEntity p
                            where p.salesOrderId in :ids""", Long.class)
                    .setParameter("ids", salesOrderIds)
                    .getResultList();
        }
    }

    /* ------------------------------------------------------------------ round 2 */

    @ApplicationScoped
    public static class Costs implements PanacheRepositoryBase<PartnerSaleReportCostEntity, Long> {
        /** The cost lines of these meldingen, per melding in line order. */
        public List<PartnerSaleReportCostEntity> byReports(Collection<Long> reportIds) {
            if (reportIds == null || reportIds.isEmpty()) return List.of();
            return list("reportId in ?1", Sort.ascending("reportId", "lineNo", "id"), reportIds);
        }

        public List<PartnerSaleReportCostEntity> byReport(long reportId) {
            return list("reportId", Sort.ascending("lineNo", "id"), reportId);
        }

        /** Re-points the cost lines of one melding to another (an edit moves the old ones to its archive row). */
        public int moveToReport(long fromReportId, long toReportId) {
            return update("reportId = ?1 where reportId = ?2", toReportId, fromReportId);
        }
    }

    @ApplicationScoped
    public static class ProductUnits implements PanacheRepositoryBase<PartnerProductUnitEntity, Long> {
        /** The stored default units of one partner, by product id. */
        public java.util.Map<Long, String> unitsOf(long customerId) {
            java.util.Map<Long, String> result = new java.util.HashMap<>();
            for (PartnerProductUnitEntity row : list("customerId", customerId)) {
                if (row.productId != null && row.unit != null) result.put(row.productId, row.unit);
            }
            return result;
        }

        /** The stored rows of one partner, product id ascending. */
        public List<PartnerProductUnitEntity> rowsOf(long customerId) {
            return list("customerId", Sort.ascending("productId", "id"), customerId);
        }

        public Optional<PartnerProductUnitEntity> find(long customerId, long productId) {
            return find("customerId = ?1 and productId = ?2", customerId, productId).firstResultOptional();
        }

        public long deleteByCustomer(long customerId) {
            return delete("customerId", customerId);
        }
    }

    @ApplicationScoped
    public static class CustomerLinks implements PanacheRepositoryBase<PartnerCustomerLinkEntity, Long> {
        public Optional<PartnerCustomerLinkEntity> find(long partnerCustomerId, long customerId) {
            return find("partnerCustomerId = ?1 and customerId = ?2", partnerCustomerId, customerId).firstResultOptional();
        }

        /** The links that name this customer as the buyer (one per partner that sold to it). */
        public List<PartnerCustomerLinkEntity> byCustomer(long customerId) {
            return list("customerId", Sort.ascending("id"), customerId);
        }

        public List<PartnerCustomerLinkEntity> byPartner(long partnerCustomerId) {
            return list("partnerCustomerId", Sort.ascending("id"), partnerCustomerId);
        }

        /** The customers this partner added itself, newest first. */
        public List<PartnerCustomerLinkEntity> addedBy(long partnerCustomerId) {
            return list("partnerCustomerId = ?1 and addedByPartner = true", Sort.descending("createdAt", "id"),
                    partnerCustomerId);
        }

        /** How many customers this partner added at or after {@code since}. */
        public long addedSince(long partnerCustomerId, Instant since) {
            return count("partnerCustomerId = ?1 and addedByPartner = true and createdAt >= ?2", partnerCustomerId, since);
        }

        /** Every customer a partner added that staff have not marked reviewed yet, oldest first (the bell). */
        public List<PartnerCustomerLinkEntity> awaitingReview() {
            return list("addedByPartner = true and reviewedAt is null", Sort.ascending("createdAt", "id"));
        }

        /** The partner-made links of these customers (null = of every customer), oldest first. */
        public List<PartnerCustomerLinkEntity> addedFor(Collection<Long> customerIds) {
            if (customerIds == null) return list("addedByPartner = true", Sort.ascending("createdAt", "id"));
            if (customerIds.isEmpty()) return List.of();
            return list("addedByPartner = true and customerId in ?1", Sort.ascending("createdAt", "id"), customerIds);
        }

        /** On a customer delete: every link where it is the partner or the buyer. */
        public long deleteByEither(long customerId) {
            return delete("partnerCustomerId = ?1 or customerId = ?1", customerId);
        }
    }

    @ApplicationScoped
    public static class InvoiceFigures implements PanacheRepositoryBase<PartnerSaleInvoiceFigureEntity, Long> {
        public List<PartnerSaleInvoiceFigureEntity> byOrder(long salesOrderId) {
            return list("salesOrderId", Sort.ascending("id"), salesOrderId);
        }
    }
}
