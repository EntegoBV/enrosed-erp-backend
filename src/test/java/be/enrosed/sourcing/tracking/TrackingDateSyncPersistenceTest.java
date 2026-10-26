package be.enrosed.sourcing.tracking;

import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static be.enrosed.sourcing.tracking.TrackingDateSyncTest.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrackingDateSyncPersistenceTest {
    @Inject TrackingDateSync sync;
    @Inject EntityManager entities;
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject be.enrosed.sourcing.application.PurchaseOrderService purchases;
    @Inject be.enrosed.sourcing.application.SupplierService suppliers;

    PurchaseOrder purchase() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Date sync supplier", "CN", "Ningbo", null,
                null, null, be.enrosed.shared.Currency.USD, "FOB", "Ningbo", 30, null));
        long id = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.9"), BigDecimal.ZERO).id();
        var entity = entities.find(PurchaseOrderEntity.class, id);
        entity.status = PurchaseOrderStatus.ONDERWEG; entity.orderDate = LocalDate.of(2026, 10, 1);
        entity.trackingReference = "BL: EGLV143650155115 | EGSU0162841";
        entity.shippedOn = LocalDate.of(2026, 10, 5); entity.expectedArrival = LocalDate.of(2026, 11, 10);
        entity.stockBooked = false; entity.paidTotalEur = new BigDecimal("321.00");
        entity.departurePort = null; entity.destinationPort = null; // Existing operational defaults are Ningbo/Rotterdam.
        entities.flush(); entities.clear(); return orders.findById(id).orElseThrow();
    }

    @Test @TestTransaction void updatesOnlyTransportDatesAndIdempotentAuditWithLegacyDefaultRoute() {
        var baseline = purchase();
        long before = ActivityLogEntity.count("entityType = ?1 and entityId = ?2", "PURCHASE_ORDER", baseline.id().toString());
        var data = observation(evidence("2026-10-06T00:20:00+08:00", "2026-11-12"), NOW);
        assertNull(sync.shipment(baseline, data, NOW, "test source"));
        entities.flush(); entities.clear();
        var saved = entities.find(PurchaseOrderEntity.class, baseline.id());
        assertEquals(LocalDate.of(2026, 11, 12), saved.expectedArrival); assertEquals(LocalDate.of(2026, 10, 6), saved.shippedOn);
        assertEquals(PurchaseOrderStatus.ONDERWEG, saved.status); assertNull(saved.receivedOn); assertFalse(saved.stockBooked);
        assertEquals(0, new BigDecimal("321.00").compareTo(saved.paidTotalEur)); assertNull(saved.departurePort);
        assertNull(sync.shipment(orders.findById(baseline.id()).orElseThrow(), data, NOW, "test source"));
        assertEquals(before + 1, ActivityLogEntity.count("entityType = ?1 and entityId = ?2", "PURCHASE_ORDER", baseline.id().toString()));
    }

    @Test @TestTransaction void concurrentOrderDateOrManualEtaEditWinsAndReportsReason() {
        var baseline = purchase();
        var current = entities.find(PurchaseOrderEntity.class, baseline.id());
        current.orderDate = LocalDate.of(2026, 10, 4);
        entities.flush(); entities.clear();
        assertNotNull(sync.shipment(baseline, observation(evidence(null, "2026-11-12"), NOW), NOW, "test source"));
        assertEquals(LocalDate.of(2026, 11, 10), entities.find(PurchaseOrderEntity.class, baseline.id()).expectedArrival);
        baseline = orders.findById(baseline.id()).orElseThrow();
        current = entities.find(PurchaseOrderEntity.class, baseline.id());
        current.expectedArrival = LocalDate.of(2026, 11, 20); entities.flush(); entities.clear();
        assertNotNull(sync.shipment(baseline, observation(evidence(null, "2026-11-12"), NOW), NOW, "test source"));
        assertEquals(LocalDate.of(2026, 11, 20), entities.find(PurchaseOrderEntity.class, baseline.id()).expectedArrival);
    }

    @Test @TestTransaction void receivedDateAndMismatchedLinkedVesselPreventSourceWrites() {
        var baseline = purchase();
        var current = entities.find(PurchaseOrderEntity.class, baseline.id()); current.receivedOn = LocalDate.of(2026, 10, 25);
        entities.flush(); entities.clear();
        assertNotNull(sync.shipment(baseline, observation(evidence(null, "2026-11-12"), NOW), NOW, "test source"));
        current = entities.find(PurchaseOrderEntity.class, baseline.id()); current.receivedOn = null;
        var link = new VesselLinkEntity(); link.orderId = baseline.id(); link.referenceHash = VesselLinkStore.binding(baseline.trackingReference());
        link.imo = "9811000"; link.linkedAt = NOW; link.persist(); entities.flush(); entities.clear();
        assertTrue(sync.shipment(baseline, observation(evidence(null, "2026-11-12"), NOW), NOW, "test source").contains("schip"));
        assertEquals(LocalDate.of(2026, 11, 10), entities.find(PurchaseOrderEntity.class, baseline.id()).expectedArrival);
    }
}
