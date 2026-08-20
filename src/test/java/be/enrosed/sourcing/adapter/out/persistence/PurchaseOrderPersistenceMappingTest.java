package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
