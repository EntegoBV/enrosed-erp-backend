package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.*;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
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

        private static be.enrosed.sourcing.domain.PurchaseDocument toDomain(SourcingEntities.PurchaseDocumentEntity e) {
            return new be.enrosed.sourcing.domain.PurchaseDocument(e.id, e.orderId, e.kind, e.label, e.originalFilename,
                    e.contentType, e.sizeBytes, e.storageKey, e.paymentId, e.actor, e.addedAt);
        }
    }

    @ApplicationScoped
    public static class PaymentAdapter implements SourcingRepositories.Payments {
        private final PurchasePaymentDao dao;

        public PaymentAdapter(PurchasePaymentDao dao) {
            this.dao = dao;
        }

        @Override
        public List<be.enrosed.sourcing.domain.PurchasePayment> forOrder(long orderId) {
            return dao.list("orderId = ?1 order by paidOn, id", orderId).stream().map(PaymentAdapter::toDomain).toList();
        }

        @Override
        public be.enrosed.sourcing.domain.PurchasePayment save(be.enrosed.sourcing.domain.PurchasePayment payment) {
            SourcingEntities.PurchasePaymentEntity entity = new SourcingEntities.PurchasePaymentEntity();
            entity.orderId = payment.orderId();
            entity.paidOn = payment.paidOn();
            entity.amount = payment.amount();
            entity.currency = payment.currency();
            entity.amountEur = payment.amountEur();
            entity.label = payment.label();
            entity.actor = payment.actor();
            entity.recordedAt = payment.recordedAt();
            entity.payee = payment.payee();
            dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public boolean delete(long orderId, long paymentId) {
            return dao.delete("id = ?1 and orderId = ?2", paymentId, orderId) == 1;
        }

        private static be.enrosed.sourcing.domain.PurchasePayment toDomain(SourcingEntities.PurchasePaymentEntity e) {
            return new be.enrosed.sourcing.domain.PurchasePayment(e.id, e.orderId, e.paidOn, e.amount, e.currency,
                    e.amountEur, e.label, e.actor, e.recordedAt, e.payee);
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
        public Optional<PurchaseOrder> findById(long id) {
            return Optional.ofNullable(dao.findById(id)).map(PurchaseOrderAdapter::toDomain);
        }

        @Override
        public Optional<PurchaseOrder> findByIdForUpdate(long id) {
            return Optional.ofNullable(dao.findById(id, LockModeType.PESSIMISTIC_WRITE))
                    .map(PurchaseOrderAdapter::toDomain);
        }

        @Override
        public PurchaseOrder save(PurchaseOrder order) {
            PurchaseOrderEntity entity = order.id() == null ? null : dao.findById(order.id());
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
            }

            if (entity.id == null) dao.persist(entity);
            dao.flush();
            return toDomain(entity);
        }

        @Override
        public void deleteById(long id) {
            dao.deleteById(id);
        }

        static PurchaseOrder toDomain(PurchaseOrderEntity entity) {
            List<PurchaseOrderLine> lines = new ArrayList<>();
            for (PurchaseOrderLineEntity line : entity.lines) {
                lines.add(new PurchaseOrderLine(line.id, line.productId, line.quantity,
                        line.exwPrice, line.exwCurrency, line.extraUnitCost,
                        line.orderedQuantity, line.priceBasis, line.damagedQuantity));
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
                    entity.notes, lines);
        }
    }
}
