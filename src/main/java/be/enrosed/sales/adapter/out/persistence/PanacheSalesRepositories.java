package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The sales-side port adapters onto Panache. */
public final class PanacheSalesRepositories {

    private PanacheSalesRepositories() {}

    @ApplicationScoped
    public static class CustomerAdapter implements SalesRepositories.Customers {

        private final SalesDaos.Customers dao;
        private final SalesDaos.CustomerTouchpoints touchpoints;

        public CustomerAdapter(SalesDaos.Customers dao, SalesDaos.CustomerTouchpoints touchpoints) {
            this.dao = dao;
            this.touchpoints = touchpoints;
        }

        /** Two queries whatever the number of customers: the list, then every touchpoint row grouped by customer. */
        @Override
        public List<Customer> findAll() {
            List<CustomerEntity> entities = dao.listAll();
            Map<Long, List<CustomerTouchpointEntity>> byCustomer = touchpoints.listAll().stream()
                    .collect(Collectors.groupingBy(row -> row.customerId));
            return entities.stream()
                    .map(entity -> SalesMapper.toDomain(entity, byCustomer.getOrDefault(entity.id, List.of())))
                    .toList();
        }

        @Override
        public Optional<Customer> findById(long id) {
            return Optional.ofNullable(dao.findById(id))
                    .map(entity -> SalesMapper.toDomain(entity, touchpointsOf(entity.id)));
        }

        @Override
        public Customer save(Customer customer) {
            /* Locked for the rest of the transaction, as the Orders adapter does: two saves of one
               customer at once (the phone and the desk ticking the same contact moment) would both
               see no row for the kind and the second would die on uk_customer_touchpoint_kind. */
            CustomerEntity entity = customer.id() == null ? null
                    : dao.findById(customer.id(), LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) entity = new CustomerEntity();
            SalesMapper.apply(customer, entity);
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            if (customer.touchpoints() != null) reconcileTouchpoints(entity.id, customer.touchpoints());
            return SalesMapper.toDomain(entity, touchpointsOf(entity.id));
        }

        /**
         * Brings the rows in line with the kinds the domain object carries: a kind that is new gets
         * a row with the moment and actor it carries, a kind left out loses its row, a kind that is
         * already there is not touched, so its first moment survives a save that changed something
         * else. The service decides the moment; this only stores it.
         */
        private void reconcileTouchpoints(long customerId, List<CustomerTouchpoint> wanted) {
            Map<String, CustomerTouchpointEntity> existing = new LinkedHashMap<>();
            for (CustomerTouchpointEntity row : touchpointsOf(customerId)) existing.put(row.kind, row);
            Set<String> keep = new HashSet<>();
            for (CustomerTouchpoint touchpoint : wanted) {
                if (touchpoint == null || touchpoint.kind() == null || !keep.add(touchpoint.kind())) continue;
                if (existing.containsKey(touchpoint.kind())) continue;
                CustomerTouchpointEntity row = new CustomerTouchpointEntity();
                row.customerId = customerId;
                row.kind = touchpoint.kind();
                row.doneAt = touchpoint.at() == null ? Instant.now() : touchpoint.at();
                row.doneBy = touchpoint.by();
                touchpoints.persist(row);
            }
            for (CustomerTouchpointEntity row : existing.values()) {
                if (!keep.contains(row.kind)) touchpoints.delete(row);
            }
            touchpoints.flush();
        }

        private List<CustomerTouchpointEntity> touchpointsOf(long customerId) {
            return touchpoints.list("customerId = ?1 order by doneAt, id", customerId);
        }

        @Override
        public void deleteById(long id) {
            touchpoints.delete("customerId", id);
            dao.deleteById(id);
        }
    }

    @ApplicationScoped
    public static class CountryAdapter implements SalesRepositories.Countries {

        private final SalesDaos.Countries dao;

        public CountryAdapter(SalesDaos.Countries dao) {
            this.dao = dao;
        }

        @Override
        public List<Country> findAll() {
            return dao.listAll().stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public Optional<Country> findByCode(String code) {
            return Optional.ofNullable(dao.findById(code)).map(SalesMapper::toDomain);
        }

        @Override
        public Country save(Country country) {
            CountryEntity entity = dao.findById(country.code());
            if (entity == null) {
                entity = new CountryEntity();
                SalesMapper.apply(country, entity);
                dao.persist(entity);
            } else {
                SalesMapper.apply(country, entity);
            }
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        @Override
        public void deleteByCode(String code) {
            dao.deleteById(code);
        }
    }

    @ApplicationScoped
    public static class TierAdapter implements SalesRepositories.Tiers {

        private final SalesDaos.Tiers dao;

        public TierAdapter(SalesDaos.Tiers dao) {
            this.dao = dao;
        }

        @Override
        public List<DiscountTier> findByScope(TierScope scope) {
            return dao.list("scope", scope).stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public List<DiscountTier> findByScopeAndProduct(TierScope scope, long productId) {
            return dao.list("scope = ?1 and productId = ?2", scope, productId).stream()
                    .map(SalesMapper::toDomain)
                    .toList();
        }

        @Override
        public void replaceScope(TierScope scope, List<DiscountTier> tiers) {
            dao.delete("scope", scope);
            for (DiscountTier tier : tiers) {
                DiscountTierEntity entity = new DiscountTierEntity();
                entity.scope = scope;
                entity.minQuantity = tier.minQuantity();
                entity.percent = tier.percent();
                entity.productId = scope == TierScope.LINE ? tier.productId() : null;
                dao.persist(entity);
            }
            dao.flush();
        }

        @Override
        public void replaceProduct(TierScope scope, long productId, List<DiscountTier> tiers) {
            dao.delete("scope = ?1 and productId = ?2", scope, productId);
            for (DiscountTier tier : tiers) {
                DiscountTierEntity entity = new DiscountTierEntity();
                entity.scope = scope;
                entity.minQuantity = tier.minQuantity();
                entity.percent = tier.percent();
                entity.productId = productId;
                dao.persist(entity);
            }
            dao.flush();
        }
    }

    @ApplicationScoped
    public static class OrderAdapter implements SalesRepositories.Orders {

        private final SalesDaos.Orders dao;
        @jakarta.inject.Inject SalesNumberingLock numberingLock;

        public OrderAdapter(SalesDaos.Orders dao) {
            this.dao = dao;
        }

        @Override
        public List<SalesOrder> findAll() {
            return dao.list("order by id desc").stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public void lockNumbers() { numberingLock.acquire(); }

        @Override
        public java.util.List<SalesRepositories.Orders.ReservedNumber> numbersIncludingDeleted() {
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = dao.getEntityManager().createNativeQuery("select id, number, docType from sales_order").getResultList();
            return rows.stream().map(r -> new SalesRepositories.Orders.ReservedNumber(((Number) r[0]).longValue(),
                    (String) r[1], reservedType(r[2]))).toList();
        }

        /** A stored type read leniently: rows from before invoices, or an unknown value, count as quotes. */
        private static be.enrosed.sales.domain.DocumentType reservedType(Object raw) {
            if (raw == null) return be.enrosed.sales.domain.DocumentType.OFFERTE;
            try { return be.enrosed.sales.domain.DocumentType.valueOf(String.valueOf(raw)); }
            catch (IllegalArgumentException unknown) { return be.enrosed.sales.domain.DocumentType.OFFERTE; }
        }

        @Override
        public Optional<SalesOrder> findById(long id) {
            return dao.find("id = ?1", id).firstResultOptional().map(SalesMapper::toDomain);
        }

        @Override
        public void lockById(long id) {
            if (dao.getEntityManager().createNativeQuery("select id from sales_order where id = :id and deleted_at is null for update")
                    .setParameter("id", id).setFlushMode(jakarta.persistence.FlushModeType.COMMIT).getResultList().isEmpty())
                throw new be.enrosed.shared.NotFoundException("Document", id);
        }

        @Override
        public Optional<SalesOrder> findByPortalToken(String token) {
            return dao.find("portalToken", token).firstResultOptional().map(SalesMapper::toDomain);
        }

        @Override
        public long countByCustomer(long customerId) {
            return ((Number) dao.getEntityManager().createNativeQuery("select count(*) from sales_order where customerId = :id")
                    .setParameter("id", customerId).getSingleResult()).longValue();
        }

        @Override
        public long countBySalesChannel(String code) {
            return code == null ? 0 : dao.count("upper(salesChannel) = ?1", code.toUpperCase(java.util.Locale.ROOT));
        }

        @Override
        public boolean existsBySourceQuoteId(long sourceQuoteId) {
            return dao.count("sourceQuoteId", sourceQuoteId) > 0;
        }

        @Override
        public SalesOrder save(SalesOrder order) {
            /* Every new document chooses its pallet count rule: a creation that slipped through
               here would otherwise get one nobody chose. */
            if (order.id() == null)
                throw new IllegalStateException("A new sales document is made with create(order, palletRule)");
            lockById(order.id());
            SalesOrderEntity entity = dao.findById(order.id());
            if (entity == null) throw new be.enrosed.shared.NotFoundException("Document", order.id());
            SalesMapper.apply(order, entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        /* The rule is written once, with the row; the column is not updatable, so no save can change it. */
        @Override
        public SalesOrder create(SalesOrder order, PalletCountRule palletRule) {
            if (order.id() != null) throw new IllegalStateException("Document " + order.id() + " already exists; save it");
            if (palletRule == null) throw new IllegalStateException("A new sales document needs its pallet count rule");
            SalesOrderEntity entity = new SalesOrderEntity();
            entity.palletCountRule = palletRule.name();
            SalesMapper.apply(order, entity);
            dao.persist(entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        /* Native, so a deleted document keeps its rule in the trash too; flushing waits for the
           commit, as the lock does, because save() and create() flush themselves. */
        @Override
        public PalletCountRule palletCountRule(long id) {
            List<?> rows = dao.getEntityManager().createNativeQuery("select palletcountrule from sales_order where id = :id")
                    .setParameter("id", id).setFlushMode(jakarta.persistence.FlushModeType.COMMIT).getResultList();
            return PalletCountRule.stored(rows.isEmpty() ? null : (String) rows.getFirst());
        }

        @Override
        public java.util.Map<Long, PalletCountRule> palletCountRules() {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = dao.getEntityManager().createNativeQuery("select id, palletcountrule from sales_order")
                    .setFlushMode(jakarta.persistence.FlushModeType.COMMIT).getResultList();
            java.util.Map<Long, PalletCountRule> rules = new java.util.HashMap<>();
            for (Object[] row : rows) rules.put(((Number) row[0]).longValue(), PalletCountRule.stored((String) row[1]));
            return rules;
        }

        @Override
        public void deleteById(long id) {
            dao.deleteById(id);
        }

        /* The archive moment is never written by save(): only this call moves it. */
        @Override
        public void setArchivedAt(long id, java.time.Instant at) {
            lockById(id);
            SalesOrderEntity entity = dao.findById(id);
            if (entity == null) return;
            entity.archivedAt = at;
            dao.flush();
        }

        /* On the managed line entities (no bulk update), so a later save of the managed order never writes a stale
           value over it; save() itself never writes this column. */
        @Override
        public void setLineStockCost(long orderId, long productId, java.math.BigDecimal unitCostEur) {
            SalesOrderEntity entity = dao.findById(orderId);
            if (entity == null) return;
            for (SalesOrderLineEntity line : entity.lines) {
                if (line.productId != null && line.productId == productId && !line.unavailable) {
                    line.stockCostEur = unitCostEur;
                }
            }
            dao.flush();
        }
    }

    @ApplicationScoped
    public static class RevisionAdapter implements SalesRepositories.Revisions {

        private final SalesDaos.Revisions dao;

        public RevisionAdapter(SalesDaos.Revisions dao) {
            this.dao = dao;
        }

        @Override
        public List<QuoteRevision> findByOrder(long salesOrderId) {
            return dao.list("salesOrderId = ?1 order by proposedAt desc", salesOrderId)
                    .stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public List<QuoteRevision> findPending() {
            return dao.list("status = ?1 and salesOrderId in (select id from " + dao.getEntityManager().getMetamodel().entity(SalesOrderEntity.class).getName() + ") order by proposedAt asc", RevisionStatus.IN_AFWACHTING)
                    .stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public List<QuoteRevision> findApproved() {
            return dao.list("status = ?1 and salesOrderId in (select id from " + dao.getEntityManager().getMetamodel().entity(SalesOrderEntity.class).getName() + ")", RevisionStatus.GOEDGEKEURD)
                    .stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public Optional<QuoteRevision> findById(long id) {
            return Optional.ofNullable(dao.findById(id)).map(SalesMapper::toDomain);
        }

        @Override
        public QuoteRevision save(QuoteRevision revision) {
            QuoteRevisionEntity entity = revision.id() == null ? null : dao.findById(revision.id());
            if (entity == null) entity = new QuoteRevisionEntity();
            SalesMapper.apply(revision, entity);
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        @Override
        public void deleteByOrder(long salesOrderId) {
            dao.list("salesOrderId", salesOrderId).forEach(dao::delete);
        }
    }

    /** The history of a quote: append and read only. */
    @ApplicationScoped
    public static class EventAdapter implements SalesRepositories.Events {

        private final SalesDaos.Events dao;

        public EventAdapter(SalesDaos.Events dao) {
            this.dao = dao;
        }

        @Override
        public List<QuoteEvent> findByOrder(long salesOrderId) {
            /* Newest first: the question is always "what just happened",
               not "how did this start". */
            return dao.list("salesOrderId = ?1 order by at desc, id desc", salesOrderId)
                    .stream().map(SalesMapper::toDomain).toList();
        }

        /** The length of {@code quote_event.detail}. */
        static final int DETAIL_LENGTH = 4000;

        /** The text as far as the column holds it, never ending on half a character. */
        static String fitting(String text, int length) {
            if (text == null || text.length() <= length) return text;
            return text.substring(0, Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length);
        }

        @Override
        public QuoteEvent add(QuoteEvent event) {
            SalesEntities.QuoteEventEntity entity = new SalesEntities.QuoteEventEntity();
            entity.salesOrderId = event.salesOrderId();
            entity.type = event.type();
            entity.at = event.at() == null ? java.time.Instant.now() : event.at();
            entity.actor = event.actor();
            entity.byCustomer = event.byCustomer();
            entity.summary = event.summary();
            /* The history never fails the action it records: a detail longer than its column is cut. */
            entity.detail = fitting(event.detail(), DETAIL_LENGTH);
            dao.persist(entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        @Override
        public void deleteByOrder(long salesOrderId) {
            dao.delete("salesOrderId", salesOrderId);
        }
    }
}
