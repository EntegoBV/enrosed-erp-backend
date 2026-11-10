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
        /** Newest first; {@code before} is an exclusive id cursor. One extra row tells whether more exist. */
        public List<PartnerSaleReportEntity> page(long customerId, Long before, int limit) {
            if (before == null) {
                return find("customerId", Sort.descending("id"), customerId).page(0, limit).list();
            }
            return find("customerId = ?1 and id < ?2", Sort.descending("id"), customerId, before)
                    .page(0, limit).list();
        }

        public Optional<PartnerSaleReportEntity> findByClientRef(long customerId, String clientRef) {
            if (clientRef == null || clientRef.isBlank()) return Optional.empty();
            return find("customerId = ?1 and clientRef = ?2", customerId, clientRef).firstResultOptional();
        }

        public boolean existsForCustomer(long customerId) {
            return count("customerId", customerId) > 0;
        }

        /** Every partner's meldingen (or one partner's), newest first, {@code before} an exclusive id cursor. */
        public List<PartnerSaleReportEntity> pageAll(Long customerId, Long before, int limit) {
            StringBuilder where = new StringBuilder("1 = 1");
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

        /** Non-voided meldingen made at or after {@code since}, newest first. */
        public List<PartnerSaleReportEntity> liveCreatedSince(Instant since) {
            return list("voidedAt is null and createdAt >= ?1", Sort.descending("createdAt", "id"), since);
        }

        /** Non-voided meldingen sold in [from, to] (either end may be null), oldest first. */
        public List<PartnerSaleReportEntity> liveSoldBetween(LocalDate from, LocalDate to) {
            StringBuilder where = new StringBuilder("voidedAt is null");
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
                            where r.voidedAt is null group by r.customerId""", Object[].class).getResultList()) {
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
                            where r.id = l.reportId and r.voidedAt is null and l.lineType = :buyback
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
                            where r.id = l.reportId and r.customerId = :customer and r.voidedAt is null
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
                            where r.id = l.reportId and r.voidedAt is null and l.customerId = :customer
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
                            where r.id = l.reportId and r.customerId = :customer and r.voidedAt is null
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
                            where r.id = l.reportId and r.voidedAt is null and l.lineType = :buyback
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
                            where r.id = l.reportId and r.voidedAt is null and l.invoiceId is not null
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
                            where r.id = l.reportId and r.voidedAt is null and l.lineType in :types
                              and (l.invoiceId is null""" + (released ? " or l.invoiceId in :released" : "") + ")",
                            Object[].class)
                    .setParameter("types", List.of(PartnerSaleReportLineEntity.SALE, PartnerSaleReportLineEntity.BUYBACK));
            if (released) query.setParameter("released", releasedInvoiceIds);
            return query.getResultList();
        }
    }

    @ApplicationScoped
    public static class InvoiceParts implements PanacheRepositoryBase<PartnerSaleInvoicePartEntity, Long> {
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
}
