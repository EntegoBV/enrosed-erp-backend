package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.shared.Currency;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PurchaseOrderPersistenceMappingTest {

    @Test
    void historicalDistinctExchangeRatesSurviveDatabaseRead() {
        PurchaseOrderEntity entity = new PurchaseOrderEntity();
        entity.id = 1L;
        entity.number = "PO-HISTORY";
        entity.supplierId = 7L;
        entity.orderDate = LocalDate.of(2025, 1, 2);
        entity.status = PurchaseOrderStatus.BESTELD;
        entity.containerType = "40HQ";
        entity.cnyToUsd = new BigDecimal("0.14");
        entity.usdToEurGoods = new BigDecimal("0.81");
        entity.usdToEurTransport = new BigDecimal("0.93");
        entity.originCurrency = Currency.USD;
        entity.allocFreight = Allocation.CBM;
        entity.allocOrigin = Allocation.CBM;
        entity.allocDestination = Allocation.CBM;
        entity.allocExtra = Allocation.PIECES;

        PurchaseOrder restored = PanacheSourcingRepositories.PurchaseOrderAdapter.toDomain(entity);

        assertEquals(new BigDecimal("0.81"), restored.usdToEurGoods());
        assertEquals(new BigDecimal("0.93"), restored.usdToEurTransport());
    }

    @Test
    void legacyNullDeparturePortHasSafeNingboFallback() {
        PurchaseOrderEntity entity = new PurchaseOrderEntity();
        entity.departurePort = null;

        PurchaseOrder restored = PanacheSourcingRepositories.PurchaseOrderAdapter.toDomain(entity);

        assertEquals("Ningbo", restored.departurePort());
        assertNull(restored.createdBy(), "historical orders must not be attributed without evidence");
        assertNull(restored.createdAt());
    }

    @Test
    void creatorSnapshotSurvivesPersistenceAdapterRoundTrip() {
        PanacheSourcingRepositories.PurchaseOrderDao dao =
                mock(PanacheSourcingRepositories.PurchaseOrderDao.class);
        PanacheSourcingRepositories.PurchaseOrderAdapter adapter =
                new PanacheSourcingRepositories.PurchaseOrderAdapter(dao);
        Instant createdAt = Instant.parse("2026-08-27T08:15:30Z");
        PurchaseOrder order = new PurchaseOrder(
                null, "PO-CREATOR", null, 7L, LocalDate.of(2026, 8, 27),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("0.91"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Shenzhen", "Antwerpen", null, List.of())
                .withCreationMetadata(new ActorRef("emre", "Emre"), createdAt);

        PurchaseOrder restored = adapter.save(order);

        assertEquals(new ActorRef("emre", "Emre"), restored.createdBy());
        assertEquals(createdAt, restored.createdAt());
    }

    @Test
    void departurePortSurvivesPersistenceAdapterRoundTrip() {
        PanacheSourcingRepositories.PurchaseOrderDao dao =
                mock(PanacheSourcingRepositories.PurchaseOrderDao.class);
        PanacheSourcingRepositories.PurchaseOrderAdapter adapter =
                new PanacheSourcingRepositories.PurchaseOrderAdapter(dao);
        PurchaseOrder order = new PurchaseOrder(
                null, "PO-ROUNDTRIP", null, 7L, LocalDate.of(2026, 8, 20),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("0.91"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Shenzhen", "Antwerpen", null, List.of());

        PurchaseOrder restored = adapter.save(order);

        assertEquals("Shenzhen", restored.departurePort());
        assertEquals("Antwerpen", restored.destinationPort());
        assertEquals(new BigDecimal("2000"), restored.extraRevenueEur());
    }

    @Test
    void receiptUnitValueSurvivesDatabaseRead() {
        PurchaseOrderEntity entity = new PurchaseOrderEntity();
        entity.id = 41L;
        entity.number = "PO-RECEIPT";
        entity.status = PurchaseOrderStatus.ONTVANGEN;
        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity();
        line.id = 9L;
        line.order = entity;
        line.productId = 77L;
        line.quantity = 8;
        line.orderedQuantity = 10;
        line.damagedQuantity = 1;
        line.receiptUnitValueEur = new BigDecimal("4.1250");
        entity.lines.add(line);

        PurchaseOrder restored = PanacheSourcingRepositories.PurchaseOrderAdapter.toDomain(entity);

        assertEquals(new BigDecimal("4.1250"), restored.lines().getFirst().receiptUnitValueEur());
        assertEquals(2, restored.lines().getFirst().missing());
        assertEquals(new BigDecimal("8.25"), restored.lines().getFirst().missingValueEur());
        assertEquals(new BigDecimal("4.13"), restored.lines().getFirst().damagedValueEur());
    }
}
