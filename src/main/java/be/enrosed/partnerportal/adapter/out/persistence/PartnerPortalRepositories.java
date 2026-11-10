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
