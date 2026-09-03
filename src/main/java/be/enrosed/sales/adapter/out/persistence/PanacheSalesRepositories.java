package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/** The sales-side port adapters onto Panache. */
public final class PanacheSalesRepositories {

    private PanacheSalesRepositories() {}

    @ApplicationScoped
    public static class CustomerAdapter implements SalesRepositories.Customers {

        private final SalesDaos.Customers dao;

        public CustomerAdapter(SalesDaos.Customers dao) {
            this.dao = dao;
        }

        @Override
        public List<Customer> findAll() {
            return dao.listAll().stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public Optional<Customer> findById(long id) {
            return Optional.ofNullable(dao.findById(id)).map(SalesMapper::toDomain);
        }

        @Override
        public Customer save(Customer customer) {
            CustomerEntity entity = customer.id() == null ? null : dao.findById(customer.id());
            if (entity == null) entity = new CustomerEntity();
            SalesMapper.apply(customer, entity);
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        @Override
        public void deleteById(long id) {
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

        public OrderAdapter(SalesDaos.Orders dao) {
            this.dao = dao;
        }

        @Override
        public List<SalesOrder> findAll() {
            return dao.list("order by id desc").stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public Optional<SalesOrder> findById(long id) {
            return Optional.ofNullable(dao.findById(id)).map(SalesMapper::toDomain);
        }

        @Override
        public void lockById(long id) {
            dao.findById(id, LockModeType.PESSIMISTIC_WRITE);
        }

        @Override
        public Optional<SalesOrder> findByPortalToken(String token) {
            return dao.find("portalToken", token).firstResultOptional().map(SalesMapper::toDomain);
        }

        @Override
        public long countByCustomer(long customerId) {
            return dao.count("customerId", customerId);
        }

        @Override
        public boolean existsBySourceQuoteId(long sourceQuoteId) {
            return dao.count("sourceQuoteId", sourceQuoteId) > 0;
        }

        @Override
        public SalesOrder save(SalesOrder order) {
            SalesOrderEntity entity = order.id() == null ? null : dao.findById(order.id());
            if (entity == null) entity = new SalesOrderEntity();
            SalesMapper.apply(order, entity);
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return SalesMapper.toDomain(entity);
        }

        @Override
        public void deleteById(long id) {
            dao.deleteById(id);
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
            return dao.list("status = ?1 order by proposedAt asc", RevisionStatus.IN_AFWACHTING)
                    .stream().map(SalesMapper::toDomain).toList();
        }

        @Override
        public List<QuoteRevision> findApproved() {
            return dao.list("status = ?1", RevisionStatus.GOEDGEKEURD)
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

        @Override
        public QuoteEvent add(QuoteEvent event) {
            SalesEntities.QuoteEventEntity entity = new SalesEntities.QuoteEventEntity();
            entity.salesOrderId = event.salesOrderId();
            entity.type = event.type();
            entity.at = event.at() == null ? java.time.Instant.now() : event.at();
            entity.actor = event.actor();
            entity.byCustomer = event.byCustomer();
            entity.summary = event.summary();
            entity.detail = event.detail();
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
