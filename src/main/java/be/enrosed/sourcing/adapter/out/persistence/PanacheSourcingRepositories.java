package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.*;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import be.enrosed.shared.security.ActorRef;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PanacheSourcingRepositories {

    private PanacheSourcingRepositories() {}

    @ApplicationScoped
    public static class SupplierDao implements PanacheRepository<SupplierEntity> {}

    @ApplicationScoped
    public static class PurchaseOrderDao implements PanacheRepository<PurchaseOrderEntity> {}

    @ApplicationScoped
    public static class PurchasePaymentDao implements PanacheRepository<SourcingEntities.PurchasePaymentEntity> {}

    @ApplicationScoped
    public static class PurchaseDocumentDao implements PanacheRepository<SourcingEntities.PurchaseDocumentEntity> {}

    @ApplicationScoped
    public static class PurchaseSupplierCreditDao implements PanacheRepository<SourcingEntities.PurchaseSupplierCreditEntity> {}

    @ApplicationScoped
    public static class PurchaseInvoiceDao implements PanacheRepository<PurchaseInvoiceEntity> {}

    @ApplicationScoped
    public static class InvoiceAdapter implements SourcingRepositories.Invoices {
        private final PurchaseInvoiceDao dao;

        public InvoiceAdapter(PurchaseInvoiceDao dao) {
            this.dao = dao;
        }

        @Override
        public List<PurchaseInvoice> forOrder(long orderId) {
            return dao.list("orderId = ?1 order by invoiceDate, id", orderId).stream()
                    .map(InvoiceAdapter::toDomain).toList();
        }

        @Override
        public List<PurchaseInvoice> all() {
            return dao.list("order by orderId, invoiceDate, id").stream().map(InvoiceAdapter::toDomain).toList();
        }

        @Override
        public Optional<PurchaseInvoice> find(long orderId, long invoiceId) {
            return dao.find("id = ?1 and orderId = ?2", invoiceId, orderId).firstResultOptional()
                    .map(InvoiceAdapter::toDomain);
        }

        @Override
        public PurchaseInvoice save(PurchaseInvoice invoice) {
            /* An invoice with an id is the same invoice corrected, never a second one. */
            PurchaseInvoiceEntity entity = invoice.id() == null ? null : dao.findById(invoice.id());
            if (entity == null) entity = new PurchaseInvoiceEntity();
            entity.orderId = invoice.orderId();
            entity.payee = invoice.payee().name();
            entity.number = invoice.number();
            entity.invoiceDate = invoice.invoiceDate();
            entity.amount = invoice.amount();
            entity.currency = invoice.currency() == null ? be.enrosed.shared.Currency.EUR.name() : invoice.currency().name();
            entity.amountEur = invoice.amountEur();
            entity.documentId = invoice.documentId();
            entity.notes = invoice.notes();
            entity.actor = invoice.actor();
            entity.recordedAt = invoice.recordedAt();
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public boolean delete(long orderId, long invoiceId) {
            return dao.delete("id = ?1 and orderId = ?2", invoiceId, orderId) == 1;
        }

        /* On the managed rows, so the same transaction reads the change at once. */
        @Override
        public void clearDocument(long orderId, long documentId) {
            for (PurchaseInvoiceEntity entity : dao.list("orderId = ?1 and documentId = ?2", orderId, documentId)) {
                entity.documentId = null;
            }
            dao.flush();
        }

        private static PurchaseInvoice toDomain(PurchaseInvoiceEntity e) {
            return new PurchaseInvoice(e.id, e.orderId, payee(e.payee), e.number, e.invoiceDate, e.amount,
                    currency(e.currency), e.amountEur, e.documentId, e.notes, e.actor, e.recordedAt);
        }

        private static PurchasePayment.Payee payee(String value) {
            try {
                return value == null ? PurchasePayment.Payee.SUPPLIER : PurchasePayment.Payee.valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return PurchasePayment.Payee.OTHER;
            }
        }

        private static be.enrosed.shared.Currency currency(String value) {
            try {
                return value == null ? be.enrosed.shared.Currency.EUR : be.enrosed.shared.Currency.valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return be.enrosed.shared.Currency.EUR;
            }
        }
    }

    @ApplicationScoped
    public static class SupplierCreditAdapter implements SourcingRepositories.SupplierCredits {
        private final PurchaseSupplierCreditDao dao;

        public SupplierCreditAdapter(PurchaseSupplierCreditDao dao) {
            this.dao = dao;
        }

        @Override
        public List<PurchaseSupplierCredit> forOrder(long orderId) {
            return dao.list("orderId = ?1 order by notedOn, id", orderId).stream()
                    .map(SupplierCreditAdapter::toDomain).toList();
        }

        @Override
        public List<PurchaseSupplierCredit> offsetOnto(long orderId) {
            return dao.list("offsetOrderId = ?1 order by settledOn, id", orderId).stream()
                    .map(SupplierCreditAdapter::toDomain).toList();
        }

        @Override
        public Optional<PurchaseSupplierCredit> find(long orderId, long creditId) {
            return dao.find("id = ?1 and orderId = ?2", creditId, orderId).firstResultOptional()
                    .map(SupplierCreditAdapter::toDomain);
        }

        @Override
        public Optional<PurchaseSupplierCredit> forOffsetPayment(long paymentId) {
            return dao.find("offsetPaymentId = ?1", paymentId).firstResultOptional()
                    .map(SupplierCreditAdapter::toDomain);
        }

        @Override
        public PurchaseSupplierCredit save(PurchaseSupplierCredit credit) {
            /* A credit with an id is the same credit changed, never a second one. */
            SourcingEntities.PurchaseSupplierCreditEntity entity = credit.id() == null ? null : dao.findById(credit.id());
            if (entity == null) entity = new SourcingEntities.PurchaseSupplierCreditEntity();
            entity.orderId = credit.orderId();
            entity.notedOn = credit.notedOn();
            entity.amount = credit.amount();
            entity.currency = credit.currency();
            entity.amountEur = credit.amountEur();
            entity.reason = credit.reason();
            entity.note = credit.note();
            entity.status = credit.status();
            entity.settledOn = credit.settledOn();
            entity.offsetOrderId = credit.offsetOrderId();
            entity.offsetPaymentId = credit.offsetPaymentId();
            entity.actor = credit.actor();
            entity.recordedAt = credit.recordedAt();
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public boolean delete(long orderId, long creditId) {
            return dao.delete("id = ?1 and orderId = ?2", creditId, orderId) == 1;
        }

        @Override
        public java.util.Set<Long> offsetPaymentIds() {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (SourcingEntities.PurchaseSupplierCreditEntity credit : dao.list("offsetPaymentId is not null")) {
                ids.add(credit.offsetPaymentId);
            }
            return ids;
        }

        private static PurchaseSupplierCredit toDomain(SourcingEntities.PurchaseSupplierCreditEntity e) {
            return new PurchaseSupplierCredit(e.id, e.orderId, e.notedOn, e.amount, e.currency, e.amountEur,
                    e.reason, e.note, e.status, e.settledOn, e.offsetOrderId, e.offsetPaymentId, e.actor, e.recordedAt);
        }
    }

    @ApplicationScoped
    public static class DocumentAdapter implements SourcingRepositories.Documents {
        private final PurchaseDocumentDao dao;

        public DocumentAdapter(PurchaseDocumentDao dao) {
            this.dao = dao;
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchaseDocument> forOrder(long orderId) {
            return dao.list("orderId = ?1 order by addedAt, id", orderId).stream().map(DocumentAdapter::toDomain).toList();
        }

        @Override
        public java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> find(long orderId, long documentId) {
            return dao.find("id = ?1 and orderId = ?2", documentId, orderId).firstResultOptional().map(DocumentAdapter::toDomain);
        }

        @Override
        public be.enrosed.sourcing.domain.PurchaseDocument save(be.enrosed.sourcing.domain.PurchaseDocument document) {
            SourcingEntities.PurchaseDocumentEntity entity = new SourcingEntities.PurchaseDocumentEntity();
            entity.orderId = document.orderId();
            entity.kind = document.kind();
            entity.label = document.label();
            entity.originalFilename = document.originalFilename();
            entity.contentType = document.contentType();
            entity.sizeBytes = document.sizeBytes();
            entity.storageKey = document.storageKey();
            entity.paymentId = document.paymentId();
            entity.actor = document.actor();
            entity.addedAt = document.addedAt();
            dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> rename(
                long orderId, long documentId, String label) {
            return dao.find("id = ?1 and orderId = ?2", documentId, orderId).firstResultOptional()
                    .map(entity -> {
                        entity.label = label;
                        dao.flush();
                        return toDomain(entity);
                    });
        }

        @Override
        public boolean delete(long orderId, long documentId) {
            return dao.delete("id = ?1 and orderId = ?2", documentId, orderId) == 1;
        }

        @Override
        public long deleteForOrder(long orderId) {
            return dao.delete("orderId", orderId);
        }

        /* On the managed rows, so the same transaction reads the change at once. */
        @Override
        public void reassignPayment(long orderId, long fromPaymentId, Long toPaymentId) {
            for (SourcingEntities.PurchaseDocumentEntity entity
                    : dao.list("orderId = ?1 and paymentId = ?2", orderId, fromPaymentId)) {
                entity.paymentId = toPaymentId;
            }
            dao.flush();
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchaseDocument> ofKinds(java.util.Collection<PurchaseDocument.Kind> kinds) {
            if (kinds == null || kinds.isEmpty()) return List.of();
            return dao.list("kind in ?1 order by orderId, addedAt, id", List.copyOf(kinds)).stream()
                    .map(DocumentAdapter::toDomain).toList();
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchaseDocument> byIds(java.util.Collection<Long> ids) {
            if (ids == null || ids.isEmpty()) return List.of();
            return dao.list("id in ?1 order by orderId, addedAt, id", List.copyOf(ids)).stream()
                    .map(DocumentAdapter::toDomain).toList();
        }

        private static be.enrosed.sourcing.domain.PurchaseDocument toDomain(SourcingEntities.PurchaseDocumentEntity e) {
            return new be.enrosed.sourcing.domain.PurchaseDocument(e.id, e.orderId, e.kind, e.label, e.originalFilename,
                    e.contentType, e.sizeBytes, e.storageKey, e.paymentId, e.actor, e.addedAt);
        }
    }

    @ApplicationScoped
    public static class PaymentAdapter implements SourcingRepositories.Payments {
        @jakarta.inject.Inject jakarta.persistence.EntityManager em;
        private final PurchasePaymentDao dao;

        public PaymentAdapter(PurchasePaymentDao dao) {
            this.dao = dao;
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchasePayment> forOrder(long orderId) {
            return domains(dao.list("orderId = ?1 order by paidOn, id", orderId));
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchasePayment> since(java.time.LocalDate from) {
            var rows = from == null ? dao.list("order by paidOn, id") : dao.list("paidOn >= ?1 order by paidOn, id", from);
            return domains(rows);
        }

        @Override
        public be.enrosed.sourcing.domain.PurchasePayment save(be.enrosed.sourcing.domain.PurchasePayment payment) {
            /* A payment with an id is the same payment corrected, not a second one. */
            SourcingEntities.PurchasePaymentEntity entity = payment.id() == null ? null : dao.findById(payment.id());
            if (entity == null) entity = new SourcingEntities.PurchasePaymentEntity();
            entity.orderId = payment.orderId();
            entity.paidOn = payment.paidOn();
            entity.amount = payment.amount();
            entity.currency = payment.currency();
            entity.amountEur = payment.amountEur();
            entity.label = payment.label();
            entity.actor = payment.actor();
            entity.recordedAt = payment.recordedAt();
            entity.payee = payment.payee();
            entity.settles = payment.settles();
            entity.instalmentDue = payment.instalmentDue();
            entity.bankStatementLineId = payment.bankStatementLineId();
            entity.groupId = payment.groupId();
            entity.invoiceId = payment.invoiceId();
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public boolean delete(long orderId, long paymentId) {
            em.createQuery("delete from PurchasePaymentOriginEntity o where o.paymentId=:id and o.paymentId in (select p.id from PurchasePaymentEntity p where p.orderId=:order)")
                    .setParameter("id", paymentId).setParameter("order", orderId).executeUpdate();
            return dao.delete("id = ?1 and orderId = ?2", paymentId, orderId) == 1;
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchasePayment> inGroup(long rootId) {
            return domains(dao.list("groupId = ?1 order by paidOn, id", rootId));
        }

        /* The column writes below work on the managed rows, so the same transaction reads them at once. */
        @Override
        public void clearInvoice(long orderId, long invoiceId) {
            for (SourcingEntities.PurchasePaymentEntity entity : dao.list("orderId = ?1 and invoiceId = ?2", orderId, invoiceId)) {
                entity.invoiceId = null;
            }
            dao.flush();
        }

        @Override
        public void setInvoice(java.util.Collection<Long> paymentIds, Long invoiceId) {
            if (paymentIds == null || paymentIds.isEmpty()) return;
            for (SourcingEntities.PurchasePaymentEntity entity : dao.list("id in ?1", List.copyOf(paymentIds))) {
                entity.invoiceId = invoiceId;
            }
            dao.flush();
        }

        @Override
        public void setGroup(java.util.Collection<Long> paymentIds, Long groupId) {
            if (paymentIds == null || paymentIds.isEmpty()) return;
            for (SourcingEntities.PurchasePaymentEntity entity : dao.list("id in ?1", List.copyOf(paymentIds))) {
                entity.groupId = groupId;
            }
            dao.flush();
        }

        @Override
        public long deleteForOrder(long orderId) {
            em.createQuery("delete from PurchasePaymentOriginEntity o where o.paymentId in (select p.id from PurchasePaymentEntity p where p.orderId=:order)")
                    .setParameter("order", orderId).executeUpdate();
            return dao.delete("orderId", orderId);
        }

        private List<be.enrosed.sourcing.domain.PurchasePayment> domains(List<SourcingEntities.PurchasePaymentEntity> rows) {
            if (rows.isEmpty()) return List.of();
            var origins = em.createQuery("from PurchasePaymentOriginEntity o where o.paymentId in :ids", PurchasePaymentOriginEntity.class)
                    .setParameter("ids", rows.stream().map(e -> e.id).toList()).getResultList().stream().collect(java.util.stream.Collectors.toMap(o -> o.paymentId, o -> o.toDomain()));
            return rows.stream().map(e -> toDomain(e, origins.get(e.id))).toList();
        }

        private be.enrosed.sourcing.domain.PurchasePayment toDomain(SourcingEntities.PurchasePaymentEntity e) {
            var origin = em.find(PurchasePaymentOriginEntity.class, e.id);
            return toDomain(e, origin == null ? null : origin.toDomain());
        }

        private static be.enrosed.sourcing.domain.PurchasePayment toDomain(SourcingEntities.PurchasePaymentEntity e,
                be.enrosed.sourcing.domain.PurchasePaymentOrigin origin) {
            return new be.enrosed.sourcing.domain.PurchasePayment(e.id, e.orderId, e.paidOn, e.amount, e.currency,
                    e.amountEur, e.label, e.actor, e.recordedAt, e.payee, Boolean.TRUE.equals(e.settles), e.instalmentDue,
                    e.bankStatementLineId, e.groupId, e.invoiceId, origin);
        }
    }

    @ApplicationScoped
    public static class PaymentBankAdjustmentAdapter implements SourcingRepositories.PaymentBankAdjustments {
        /** Above this many ids one read of the (small) table beats a long IN list. */
        private static final int IN_LIMIT = 500;
        private final jakarta.persistence.EntityManager em;

        public PaymentBankAdjustmentAdapter(jakarta.persistence.EntityManager em) {
            this.em = em;
        }

        @Override
        public java.util.Map<Long, PurchasePaymentBankAdjustment> find(java.util.Collection<Long> paymentIds) {
            java.util.Map<Long, PurchasePaymentBankAdjustment> rows = new java.util.HashMap<>();
            if (paymentIds == null || paymentIds.isEmpty()) return rows;
            var ids = paymentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (ids.isEmpty()) return rows;
            var query = ids.size() > IN_LIMIT
                    ? em.createQuery("from PurchasePaymentBankAdjustmentEntity a", PurchasePaymentBankAdjustmentEntity.class)
                    : em.createQuery("from PurchasePaymentBankAdjustmentEntity a where a.paymentId in :ids",
                            PurchasePaymentBankAdjustmentEntity.class).setParameter("ids", ids);
            var wanted = new java.util.HashSet<>(ids);
            for (var entity : query.getResultList()) {
                if (wanted.contains(entity.paymentId)) rows.put(entity.paymentId, toDomain(entity));
            }
            return rows;
        }

        @Override
        public boolean insertIfAbsent(PurchasePaymentBankAdjustment row) {
            if (em.find(PurchasePaymentBankAdjustmentEntity.class, row.paymentId()) != null) return false;
            var entity = new PurchasePaymentBankAdjustmentEntity();
            entity.paymentId = row.paymentId();
            entity.fromAmountEur = row.fromAmountEur();
            entity.fromPaidOn = row.fromPaidOn();
            entity.bankStatementLineId = row.bankStatementLineId();
            entity.adjustedAt = row.adjustedAt();
            entity.actor = row.actor();
            em.persist(entity);
            em.flush();
            return true;
        }

        @Override
        public void delete(long paymentId) {
            var entity = em.find(PurchasePaymentBankAdjustmentEntity.class, paymentId);
            if (entity != null) {
                em.remove(entity);
                em.flush();
            }
        }

        private static PurchasePaymentBankAdjustment toDomain(PurchasePaymentBankAdjustmentEntity entity) {
            return new PurchasePaymentBankAdjustment(entity.paymentId, entity.fromAmountEur, entity.fromPaidOn,
                    entity.bankStatementLineId, entity.adjustedAt, entity.actor);
        }
    }

    @ApplicationScoped
    public static class SupplierAdapter implements SourcingRepositories.Suppliers {

        private final SupplierDao dao;

        public SupplierAdapter(SupplierDao dao) {
            this.dao = dao;
        }

        @Override
        public List<Supplier> findAll() {
            return dao.listAll().stream().map(SupplierAdapter::toDomain).toList();
        }

        @Override
        public Optional<Supplier> findById(long id) {
            return Optional.ofNullable(dao.findById(id)).map(SupplierAdapter::toDomain);
        }

        @Override
        public Supplier save(Supplier supplier) {
            SupplierEntity entity = supplier.id() == null ? null : dao.findById(supplier.id());
            if (entity == null) entity = new SupplierEntity();
            apply(supplier, entity);
            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        static void apply(Supplier supplier, SupplierEntity entity) {
            entity.name = supplier.name();
            entity.country = supplier.country();
            entity.city = supplier.city();
            entity.addressLine1 = supplier.addressLine1();
            entity.addressLine2 = supplier.addressLine2();
            entity.postalCode = supplier.postalCode();
            entity.region = supplier.region();
            entity.contact = supplier.contact();
            entity.email = supplier.email();
            entity.phone = supplier.phone();
            entity.currency = supplier.currency();
            entity.incoterm = supplier.incoterm();
            entity.portOfLoading = supplier.portOfLoading();
            entity.leadTimeDays = supplier.leadTimeDays();
            entity.notes = supplier.notes();
        }

        @Override
        public void deleteById(long id) {
            dao.deleteById(id);
        }

        static Supplier toDomain(SupplierEntity entity) {
            return new Supplier(entity.id, entity.name, entity.country, entity.city, entity.contact,
                    entity.email, entity.phone, entity.currency, entity.incoterm,
                    entity.portOfLoading, entity.leadTimeDays, entity.notes,
                    entity.addressLine1, entity.addressLine2, entity.postalCode, entity.region);
        }
    }

    @ApplicationScoped
    public static class PurchaseOrderAdapter implements SourcingRepositories.PurchaseOrders {

        private final PurchaseOrderDao dao;

        public PurchaseOrderAdapter(PurchaseOrderDao dao) {
            this.dao = dao;
        }

        @Override
        public List<PurchaseOrder> findAll() {
            return dao.list("order by id desc").stream().map(PurchaseOrderAdapter::toDomain).toList();
        }

        @Override
        public List<String> numbersIncludingDeleted() {
            return dao.getEntityManager().createNativeQuery("select number from purchase_order where number is not null", String.class)
                    .getResultList();
        }

        @Override
        public boolean referencesPartnerIncludingDeleted(long customerId) {
            return ((Number) dao.getEntityManager().createNativeQuery("select count(*) from purchase_order where partner_customer_id = :id")
                    .setParameter("id", customerId).getSingleResult()).longValue() > 0;
        }

        @Override
        public boolean referencesSupplierIncludingDeleted(long supplierId) {
            return ((Number) dao.getEntityManager().createNativeQuery("select count(*) from purchase_order where supplierId = :id")
                    .setParameter("id", supplierId).getSingleResult()).longValue() > 0;
        }

        /* Native, so the soft-delete restriction on the order does not hide trashed containers. */
        @Override
        public long linesUsingProductSupplierIncludingDeleted(long rowId, long productId, long supplierId) {
            return ((Number) dao.getEntityManager().createNativeQuery(
                            "select count(*) from purchase_order_line l join purchase_order o on o.id = l.order_id "
                                    + "where l.product_supplier_id = :row or (l.productid = :product and o.supplierid = :supplier)")
                    .setParameter("row", rowId).setParameter("product", productId).setParameter("supplier", supplierId)
                    .getSingleResult()).longValue();
        }

        @Override
        public Optional<PurchaseOrder> findById(long id) {
            return dao.find("id = ?1", id).firstResultOptional().filter(entity -> entity.deletedAt == null).map(PurchaseOrderAdapter::toDomain);
        }

        /** Three columns per order: a sales list names its containers without loading their lines. */
        @Override
        public java.util.Map<Long, be.enrosed.sourcing.domain.PurchaseOrderName> names(java.util.Collection<Long> ids) {
            java.util.Map<Long, be.enrosed.sourcing.domain.PurchaseOrderName> names = new java.util.HashMap<>();
            List<Long> wanted = ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (wanted.isEmpty()) return names;
            var builder = dao.getEntityManager().getCriteriaBuilder();
            var query = builder.createQuery(Object[].class);
            var order = query.from(PurchaseOrderEntity.class);
            query.select(builder.array(order.get("id"), order.get("number"), order.get("alias")))
                    .where(order.get("id").in(wanted));
            dao.getEntityManager().createQuery(query).getResultList()
                    .forEach(row -> names.put((Long) row[0], new be.enrosed.sourcing.domain.PurchaseOrderName(
                            (Long) row[0], (String) row[1], (String) row[2])));
            return names;
        }

        @Override
        public Optional<PurchaseOrder> findByIdForUpdate(long id) {
            // A cached entity must not outlive a concurrent receipt/deletion while this transaction waits.
            var entities = dao.getEntityManager();
            if (entities.createNativeQuery("select id from purchase_order where id = :id and deleted_at is null for update")
                    .setParameter("id", id).getResultList().isEmpty()) return Optional.empty();
            PurchaseOrderEntity entity = dao.findById(id);
            if (entity == null) return Optional.empty();
            // Native queries flush pending writes first. Reload after locking instead of refresh(),
            // which Hibernate cannot safely cascade through a newly initialized child collection.
            entities.detach(entity);
            entity = dao.findById(id);
            return entity.deletedAt == null ? Optional.of(toDomain(entity)) : Optional.empty();
        }

        @Override
        public PurchaseOrder save(PurchaseOrder order) {
            if (order.id() != null && dao.getEntityManager()
                    .createNativeQuery("select id from purchase_order where id = :id and deleted_at is null for update")
                    .setParameter("id", order.id()).setFlushMode(jakarta.persistence.FlushModeType.COMMIT).getResultList().isEmpty())
                throw new be.enrosed.shared.NotFoundException("Inkooporder", order.id());
            PurchaseOrderEntity entity = order.id() == null ? null : dao.findById(order.id());
            if (order.id() != null && (entity == null || entity.deletedAt != null))
                throw new be.enrosed.shared.NotFoundException("Inkooporder", order.id());
            if (entity == null) entity = new PurchaseOrderEntity();

            entity.number = order.number();
            entity.alias = order.alias();
            entity.supplierId = order.supplierId();
            entity.orderDate = order.orderDate();
            entity.status = order.status();
            entity.containerType = order.containerType() == null ? "40HQ" : order.containerType().code();
            entity.cnyToUsd = order.cnyToUsd();
            entity.usdToEurGoods = order.usdToEurGoods();
            entity.usdToEurTransport = order.usdToEurTransport();
            entity.freightUsd = order.freightUsd();
            entity.originCosts = order.originCosts();
            entity.originCurrency = order.originCurrency();
            entity.destinationCostsEur = order.destinationCostsEur();
            entity.defaultDutyRatePct = order.defaultDutyRatePct();
            entity.extraRevenueEur = order.extraRevenueEur();
            entity.inspectionCostEur = order.inspectionCostEur();
            entity.otherCostsJson = OtherCostsJson.write(order.otherCosts());
            entity.partnerCustomerId = order.partnerCustomerId();
            entity.partnerCostPct = order.partnerCostPct();
            entity.partnerSharePct = order.partnerSharePct();
            entity.allocSeparate = order.allocSeparate();
            entity.payPctOrdered = order.payPctOrdered();
            entity.payPctShipped = order.payPctShipped();
            entity.payPctArrived = order.payPctArrived();
            entity.freightViaSupplier = order.freightViaSupplier();
            entity.clearance = order.clearance() == null ? null : order.clearance().name();
            entity.inspectionViaSupplier = order.inspectionViaSupplier();
            entity.allocFreight = order.allocFreight();
            entity.allocOrigin = order.allocOrigin();
            entity.allocDestination = order.allocDestination();
            entity.allocExtra = order.allocExtra();
            entity.departurePort = order.departurePort();
            entity.destinationPort = order.destinationPort();
            entity.receivingLocationId = order.receivingLocationId();
            entity.groupVariants = order.groupVariants();
            entity.expectedArrival = order.expectedArrival();
            entity.receivedOn = order.receivedOn();
            entity.paidTotalEur = order.paidTotalEur();
            entity.stockBooked = order.stockBooked();
            entity.paymentTerms = order.paymentTerms();
            entity.shippedOn = order.shippedOn();
            entity.trackingReference = order.trackingReference();
            entity.createdBy = order.createdBy() == null ? null : order.createdBy().username();
            entity.createdByDisplayName = order.createdBy() == null ? null : order.createdBy().displayName();
            entity.createdAt = order.createdAt();
            entity.notes = order.notes();

            List<PurchaseOrderLine> wanted = order.lines();
            entity.lines.removeIf(existing -> wanted.stream()
                    .noneMatch(line -> line.id() != null && line.id().equals(existing.id)));

            for (PurchaseOrderLine line : wanted) {
                PurchaseOrderLineEntity target = entity.lines.stream()
                        .filter(existing -> line.id() != null && line.id().equals(existing.id))
                        .findFirst().orElse(null);
                if (target == null) {
                    target = new PurchaseOrderLineEntity();
                    target.order = entity;
                    entity.lines.add(target);
                }
                target.productId = line.productId();
                target.quantity = line.quantity();
                target.orderedQuantity = line.orderedQuantity();
                target.exwPrice = line.exwPrice();
                target.exwCurrency = line.exwCurrency();
                target.extraUnitCost = line.extraUnitCost();
                target.priceBasis = line.priceBasis();
                target.damagedQuantity = line.damagedQuantity();
                target.receiptUnitValueEur = line.receiptUnitValueEur();
                target.issueNote = line.issueNote();
                target.extraShareEur = line.extraShareEur();
                target.productSupplierId = line.productSupplierId();
                target.supplierVersionLabel = line.supplierVersionLabel();
                target.supplierReference = line.supplierReference();
            }

            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public void deleteById(long id) {
            dao.deleteById(id);
        }

        /* The archive moment is never written by save(): only this call moves it. */
        @Override
        public void setArchivedAt(long id, java.time.Instant at) {
            PurchaseOrderEntity entity = dao.findById(id);
            if (entity == null) return;
            entity.archivedAt = at;
            dao.flush();
        }

        static PurchaseOrder toDomain(PurchaseOrderEntity entity) {
            List<PurchaseOrderLine> lines = new ArrayList<>();
            for (PurchaseOrderLineEntity line : entity.lines) {
                lines.add(new PurchaseOrderLine(line.id, line.productId, line.quantity,
                        line.exwPrice, line.exwCurrency, line.extraUnitCost,
                        line.orderedQuantity, line.priceBasis, line.damagedQuantity,
                        line.receiptUnitValueEur, line.issueNote, line.extraShareEur,
                        line.productSupplierId, line.supplierVersionLabel, line.supplierReference));
            }
            return new PurchaseOrder(entity.id, entity.number, entity.alias,
                    entity.supplierId, entity.orderDate,
                    entity.status, ContainerType.fromCode(entity.containerType),
                    entity.cnyToUsd, entity.usdToEurGoods, entity.usdToEurTransport,
                    entity.freightUsd, entity.originCosts, entity.originCurrency,
                    entity.destinationCostsEur, entity.defaultDutyRatePct, entity.extraRevenueEur,
                    entity.allocFreight, entity.allocOrigin, entity.allocDestination, entity.allocExtra,
                    entity.departurePort, entity.destinationPort, entity.receivingLocationId,
                    entity.groupVariants, entity.expectedArrival, entity.receivedOn, entity.paidTotalEur,
                    entity.stockBooked, entity.paymentTerms, entity.shippedOn, entity.trackingReference,
                    entity.createdBy == null ? null : new ActorRef(entity.createdBy,
                            entity.createdByDisplayName == null || entity.createdByDisplayName.isBlank()
                                    ? entity.createdBy : entity.createdByDisplayName),
                    entity.createdAt, entity.notes, lines).withInspectionCost(entity.inspectionCostEur)
                    .withOtherCosts(OtherCostsJson.read(entity.otherCostsJson))
                    .withArchivedAt(entity.archivedAt)
                    .withPartner(entity.partnerCustomerId, entity.partnerCostPct, entity.partnerSharePct)
                    .withSeparateAllocation(entity.allocSeparate)
                    .withPaymentSplit(entity.payPctOrdered, entity.payPctShipped, entity.payPctArrived)
                    .withFreightViaSupplier(entity.freightViaSupplier)
                    .withClearance(Clearance.parse(entity.clearance))
                    .withInspectionViaSupplier(entity.inspectionViaSupplier);
        }
    }
}
