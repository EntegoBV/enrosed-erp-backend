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
                        line.orderedQuantity));
            }
            return new PurchaseOrder(entity.id, entity.number, entity.alias,
                    entity.supplierId, entity.orderDate,
                    entity.status, ContainerType.fromCode(entity.containerType),
                    entity.cnyToUsd, entity.usdToEurGoods, entity.usdToEurTransport,
                    entity.freightUsd, entity.originCosts, entity.originCurrency,
                    entity.destinationCostsEur, entity.defaultDutyRatePct, entity.extraRevenueEur,
                    entity.allocFreight, entity.allocOrigin, entity.allocDestination, entity.allocExtra,
                    entity.departurePort, entity.destinationPort, entity.receivingLocationId,
                    entity.notes, lines);
        }
    }
}
